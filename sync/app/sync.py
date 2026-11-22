"""POST /api/sync：接收全量快照，落版 + 重建镜像 + 保留策略清理。

五步（docs §5.1）：
  1. 解析校验（validate.py，坏数据 400 明细）
  2. 内容指纹（canonical.py，排除 updatedAt）
  3. 去重：与当前版本（max id）hash 相同 → noop，不动库
  4. 熔断：存活数骤降（< 当前 30% 且当前 ≥ 100 条）→ 409 wipe_suspected，
     force=true 放行（App 端弹确认后带 force 重传）
  5. 落版（只追加）+ TRUNCATE 重建镜像 + 保留策略清理 —— 同一事务

换机合并（M4，配对 needs_merge 时）：新配对设备的首次推送不与镜像整本替换，
而是按 id 并集合并——服务器旧数据不丢，本机新数据并进去；响应带 merged 标记，
App 回拉全量完成收敛后，下一次推送即转正常整本替换。合并路由用内容指纹判定：
  - 合并结果 == 当前版本 ⟹ 客户端落后（noop + merged，保留待办等回拉）
  - 合并结果 == 传入内容 ⟹ 客户端已全量（正常落版并清除待办，转替换模式）
  - 两者都不同于 ⟹ 真双向差异（落合并版，保留待办直到收敛）

保留策略（docs §6，搭 sync 的车执行，不设 cron）：
  近 7 天全留 / 7~30 天每天最新 1 版 / 1~3 个月每周最新 1 版 / 3 个月以上每月 1 版；
  当前版本（max id）永不删。区间右开左闭重叠处多保留不丢数据，方向安全。
"""

import gzip
import json

from asyncpg.pool import Pool

from . import canonical
from . import pairing
from . import validate
from .config import config

RETENTION_SQL = """
DELETE FROM snapshots
WHERE id <> (SELECT max(id) FROM snapshots)
  AND id NOT IN (
    SELECT id FROM snapshots WHERE created_at > now() - interval '7 days'
    UNION
    SELECT max(id) FROM snapshots
      WHERE created_at <= now() - interval '7 days'
        AND created_at >  now() - interval '30 days'
      GROUP BY (created_at AT TIME ZONE 'UTC')::date
    UNION
    SELECT max(id) FROM snapshots
      WHERE created_at <= now() - interval '30 days'
        AND created_at >  now() - interval '90 days'
      GROUP BY date_trunc('week', created_at)
    UNION
    SELECT max(id) FROM snapshots
      WHERE created_at <= now() - interval '90 days'
      GROUP BY date_trunc('month', created_at)
  )
"""

_INSERT_RECORDS = """
INSERT INTO mirror_records
  (id, type, amount_minor, category_id, date_epoch_day, note,
   created_at_ms, updated_at_ms, is_deleted)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
"""

_INSERT_CATEGORIES = """
INSERT INTO mirror_categories
  (id, name, emoji, type, parent_id, sort_order, updated_at_ms, is_deleted)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
"""


class WipeSuspected(Exception):
    """存活数骤降，疑似误清空 → 409。携带 cur/new 供响应。"""

    def __init__(self, cur_live: int, new_live: int) -> None:
        self.cur_live = cur_live
        self.new_live = new_live
        super().__init__(
            f"wipe suspected: live {cur_live} -> {new_live}"
        )


def merge_snapshot(server: dict, incoming: dict) -> dict:
    """镜像 ∪ 传入快照（换机合并，纯函数，可无 PG 单测）。

    规则：按 id 并集；同 id 取 updatedAtEpochMs 较大者，平局取传入（活设备优先）。
    墓碑随记录走——删除产生的墓碑 updatedAt 晚于活副本，不会被旧数据复活。
    内容指纹排除 updatedAt，所以"传入 ⊇ 镜像"按语义内容即可判定，与字段时间戳无关。
    """

    def pick(server_items: list, incoming_items: list) -> list:
        merged = {i["id"]: i for i in server_items}
        for item in incoming_items:
            old = merged.get(item["id"])
            if old is None or item["updatedAtEpochMs"] >= old["updatedAtEpochMs"]:
                merged[item["id"]] = item
        return list(merged.values())

    return {
        "version": 1,
        "exportedAtEpochMs": incoming["exportedAtEpochMs"],
        "records": pick(server["records"], incoming["records"]),
        "categories": pick(server["categories"], incoming["categories"]),
    }


def _record_from_row(r) -> dict:
    return {
        "id": r["id"], "type": r["type"], "amountMinor": r["amount_minor"],
        "categoryId": r["category_id"], "dateEpochDay": r["date_epoch_day"],
        "note": r["note"], "createdAtEpochMs": r["created_at_ms"],
        "updatedAtEpochMs": r["updated_at_ms"], "isDeleted": r["is_deleted"],
    }


def _category_from_row(c) -> dict:
    return {
        "id": c["id"], "name": c["name"], "emoji": c["emoji"], "type": c["type"],
        "parentId": c["parent_id"], "sortOrder": c["sort_order"],
        "updatedAtEpochMs": c["updated_at_ms"], "isDeleted": c["is_deleted"],
    }


async def _merged_with_mirror(conn, snap: dict) -> dict | None:
    """当前镜像 ∪ 传入快照；镜像为空返回 None（传入即全量，无需合并）。"""
    rec_rows = await conn.fetch("SELECT * FROM mirror_records")
    cat_rows = await conn.fetch("SELECT * FROM mirror_categories")
    if not rec_rows and not cat_rows:
        return None
    return merge_snapshot(
        {
            "records": [_record_from_row(r) for r in rec_rows],
            "categories": [_category_from_row(c) for c in cat_rows],
        },
        snap,
    )


async def sync_snapshot(pool: Pool, raw: bytes, force: bool) -> dict:
    """同步一个快照。返回响应体（见 docs §5.1 响应）。

    换机合并见模块 docstring；响应在合并待办未清时带 "merged": true，
    App 据此回拉全量完成收敛。

    Raises:
        validate.ValidationError: 400
        WipeSuspected: 409
    """
    snap = validate.validate_snapshot(json.loads(raw))
    record_count, live_count, max_updated = validate.counts(snap)
    digest = canonical.content_hash(snap)

    async with pool.acquire() as conn:
        cur = await conn.fetchrow(
            "SELECT id, content_hash, live_count FROM snapshots "
            "WHERE id = (SELECT max(id) FROM snapshots)"
        )
        if cur is not None and cur["content_hash"] == digest:
            # 内容未变：客户端与服务端一致，合并待办（若有）一并清除
            await pairing.clear_merge(conn)
            return {"status": "noop", "version": cur["id"]}

        merged_response = False
        if await pairing.should_merge(conn):
            if cur is None:
                # 空服务器：传入即基线，直接转正常替换
                await pairing.clear_merge(conn)
            else:
                merged = await _merged_with_mirror(conn, snap)
                if merged is None:
                    await pairing.clear_merge(conn)
                else:
                    merged_digest = canonical.content_hash(merged)
                    if merged_digest == cur["content_hash"]:
                        # 客户端落后（传入 ⊆ 当前）：不动库，等 App 回拉收敛
                        return {
                            "status": "noop", "version": cur["id"], "merged": True,
                        }
                    if merged_digest == digest:
                        # 客户端已全量（传入 ⊇ 镜像）：正常落版，转替换模式
                        await pairing.clear_merge(conn)
                    else:
                        # 真双向差异：落合并版，待办保留到 App 收敛
                        snap = merged
                        record_count, live_count, max_updated = validate.counts(snap)
                        raw = json.dumps(
                            {
                                "version": 1,
                                "exportedAtEpochMs": snap["exportedAtEpochMs"],
                                "records": snap["records"],
                                "categories": snap["categories"],
                            },
                            ensure_ascii=False,
                        ).encode("utf-8")
                        digest = merged_digest
                        merged_response = True

        # 熔断（仅当存在历史版本且非 force）；合并模式下 live_count 为合并后数量
        if (
            not force
            and cur is not None
            and cur["live_count"] >= config.wipe_min_live
            and live_count < cur["live_count"] * config.wipe_ratio
        ):
            raise WipeSuspected(cur["live_count"], live_count)

        async with conn.transaction():
            row = await conn.fetchrow(
                """
                INSERT INTO snapshots
                  (content_hash, record_count, live_count, max_updated_at, payload_gz)
                VALUES ($1, $2, $3, $4, $5)
                RETURNING id
                """,
                digest, record_count, live_count, max_updated,
                gzip.compress(raw),
            )
            version = row["id"]

            await conn.execute("TRUNCATE mirror_records, mirror_categories")
            await conn.executemany(
                _INSERT_RECORDS,
                [
                    (r["id"], r["type"], r["amountMinor"], r["categoryId"],
                     r["dateEpochDay"], r["note"], r["createdAtEpochMs"],
                     r["updatedAtEpochMs"], r["isDeleted"])
                    for r in snap["records"]
                ],
            )
            await conn.executemany(
                _INSERT_CATEGORIES,
                [
                    (c["id"], c["name"], c["emoji"], c["type"],
                     c.get("parentId"), c["sortOrder"],
                     c["updatedAtEpochMs"], c["isDeleted"])
                    for c in snap["categories"]
                ],
            )
            await conn.execute(RETENTION_SQL)

    result = {"status": "ok", "version": version}
    if merged_response:
        result["merged"] = True
    return result
