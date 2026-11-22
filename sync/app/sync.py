"""POST /api/sync：接收全量快照，落版 + 重建镜像 + 保留策略清理。

五步（docs §5.1）：
  1. 解析校验（validate.py，坏数据 400 明细）
  2. 内容指纹（canonical.py，排除 updatedAt）
  3. 去重：与当前版本（max id）hash 相同 → noop，不动库
  4. 熔断：存活数骤降（< 当前 30% 且当前 ≥ 100 条）→ 409 wipe_suspected，
     force=true 放行（App 端弹确认后带 force 重传）
  5. 落版（只追加）+ TRUNCATE 重建镜像 + 保留策略清理 —— 同一事务

保留策略（docs §6，搭 sync 的车执行，不设 cron）：
  近 7 天全留 / 7~30 天每天最新 1 版 / 1~3 个月每周最新 1 版 / 3 个月以上每月 1 版；
  当前版本（max id）永不删。区间右开左闭重叠处多保留不丢数据，方向安全。
"""

import gzip
import json

from asyncpg.pool import Pool

from . import canonical
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


async def sync_snapshot(pool: Pool, raw: bytes, force: bool) -> dict:
    """同步一个快照。返回响应体（见 docs §5.1 响应）。

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
            return {"status": "noop", "version": cur["id"]}

        # 熔断（仅当存在历史版本且非 force）
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

    return {"status": "ok", "version": version}
