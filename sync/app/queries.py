"""只读查询接口的数据层。

M1 实现：versions / export / meta（恢复流程依赖）。
M2 实现：categories / transactions / stats（口径照抄 App RecordDao SQL + golden case 对账）。
金额转换唯一入口 money.yuan()，SQL 层不做任何换算。
"""

import gzip
import json

from asyncpg.pool import Pool

from .money import yuan

# 查询范围护栏：分页与日期跨度上限，避免一次拉全账本（docs §5 防护原则）
PAGE_SIZE_DEFAULT = 50
PAGE_SIZE_MAX = 200
RANGE_MAX_DAYS = 3700        # 约 10 年
CATEGORY_FILTER_MAX = 100    # categoryIds 多选上限

VALID_TYPES = ("EXPENSE", "INCOME")


class ParamError(ValueError):
    """查询参数非法。HTTP 层转 400，MCP 层转工具错误；消息即用户可见文案。"""


def check_range(from_day: int, to_day: int) -> None:
    """日期区间护栏：from<=to 且跨度不超上限（防一次拉全账本）。"""
    if from_day > to_day:
        raise ParamError("from 必须小于等于 to")
    if to_day - from_day > RANGE_MAX_DAYS:
        raise ParamError(f"查询区间过大（上限 {RANGE_MAX_DAYS} 天）")


def parse_category_ids(ids: list[str] | None) -> list[str] | None:
    """categoryIds 列表清洗；空项/超量都是 ParamError（HTTP 的逗号串先 split）。"""
    if ids is None:
        return None
    cleaned = [s.strip() for s in ids]
    if not cleaned or any(not s for s in cleaned):
        raise ParamError("categoryIds 含空 id")
    if len(cleaned) > CATEGORY_FILTER_MAX:
        raise ParamError(f"categoryIds 过多（上限 {CATEGORY_FILTER_MAX} 个）")
    return cleaned


def check_page(page: int, page_size: int) -> None:
    """分页护栏，与 HTTP Query(ge=1, le=PAGE_SIZE_MAX) 约束一致。"""
    if page < 1:
        raise ParamError("page 必须大于等于 1")
    if not 1 <= page_size <= PAGE_SIZE_MAX:
        raise ParamError(f"pageSize 必须在 1～{PAGE_SIZE_MAX} 之间")


async def list_versions(pool: Pool, limit: int = 100) -> list[dict]:
    """版本列表（恢复流程用），倒序。"""
    rows = await pool.fetch(
        """
        SELECT id, record_count, live_count, content_hash, created_at
        FROM snapshots ORDER BY id DESC LIMIT $1
        """,
        limit,
    )
    return [
        {
            "version": r["id"],
            "recordCount": r["record_count"],
            "liveCount": r["live_count"],
            "contentHash": r["content_hash"],
            "createdAt": r["created_at"].isoformat(),
        }
        for r in rows
    ]


async def export_snapshot(pool: Pool, version: int | None) -> dict | None:
    """按版本号取快照原文（gunzip → JSON）。version=None → 当前版本。"""
    if version is None:
        row = await pool.fetchrow(
            "SELECT payload_gz FROM snapshots "
            "WHERE id = (SELECT max(id) FROM snapshots)"
        )
    else:
        row = await pool.fetchrow(
            "SELECT payload_gz FROM snapshots WHERE id = $1", version
        )
    if row is None:
        return None
    return json.loads(gzip.decompress(row["payload_gz"]))


async def meta(pool: Pool) -> dict | None:
    """账本元信息：版本、计数、最新业务日期。"""
    row = await pool.fetchrow(
        """
        SELECT s.id AS version, s.record_count, s.live_count, s.created_at,
               (SELECT max(date_epoch_day) FROM mirror_records
                 WHERE is_deleted = false) AS latest_day,
               (SELECT count(*) FROM mirror_categories
                 WHERE is_deleted = false) AS live_categories
        FROM snapshots s
        WHERE s.id = (SELECT max(id) FROM snapshots)
        """
    )
    if row is None:
        return None
    return {
        "version": row["version"],
        "recordCount": row["record_count"],
        "liveCount": row["live_count"],
        "liveCategoryCount": row["live_categories"],
        "latestDateEpochDay": row["latest_day"],
        "serverTime": row["created_at"].isoformat(),
    }


# ---------------- M2：categories / transactions / stats ----------------
#
# 口径契约（与 App Room SQL 逐一对齐，golden fixture 是对答案的裁判）：
#   - 业务日期区间：dateEpochDay BETWEEN from AND to（闭区间）
#   - 软删除：默认一律排除（is_deleted = false）；只有显式诊断参数才放行
#   - 关键词：App 的 note LIKE '%kw%' 在 SQLite 下对 ASCII 大小写不敏感，
#     服务端用 ILIKE 对齐（中文场景两者等价）
#   - 明细排序：date DESC, createdAt DESC（与 App 一致），再按 id DESC 兜底
#     保证分页稳定


async def list_categories(pool: Pool, include_deleted: bool = False) -> list[dict]:
    """分类清单。排序对齐 App CategoryDao.active()：type, sortOrder, name。"""
    rows = await pool.fetch(
        """
        SELECT id, name, emoji, type, parent_id, sort_order, is_deleted
        FROM mirror_categories
        WHERE ($1::bool OR is_deleted = false)
        ORDER BY type, sort_order, name
        """,
        include_deleted,
    )
    return [
        {
            "id": r["id"],
            "name": r["name"],
            "emoji": r["emoji"],
            "type": r["type"],
            "parentId": r["parent_id"],
            "sortOrder": r["sort_order"],
            "isDeleted": r["is_deleted"],
        }
        for r in rows
    ]


_TRANSACTIONS_WHERE = """
    r.date_epoch_day BETWEEN $1 AND $2
    AND ($3::text IS NULL OR r.type = $3)
    AND ($4::text IS NULL OR r.note ILIKE '%' || $4 || '%')
    AND ($5::text[] IS NULL OR r.category_id = ANY($5))
    AND ($6::bool OR r.is_deleted = false)
"""


async def list_transactions(
    pool: Pool,
    *,
    from_day: int,
    to_day: int,
    rtype: str | None = None,
    keyword: str | None = None,
    category_ids: list[str] | None = None,
    include_deleted: bool = False,
    page: int = 1,
    page_size: int = PAGE_SIZE_DEFAULT,
) -> dict:
    """明细分页查询。返回 total/hasMore/items，金额同时给分（整数）与元（字符串）。"""
    args = (from_day, to_day, rtype, keyword, category_ids, include_deleted)
    total = await pool.fetchval(
        f"SELECT count(*) FROM mirror_records r WHERE {_TRANSACTIONS_WHERE}", *args
    )
    rows = await pool.fetch(
        f"""
        SELECT r.id, r.type, r.amount_minor, r.category_id, r.date_epoch_day,
               r.note, r.created_at_ms, r.updated_at_ms, r.is_deleted,
               c.name AS category_name, p.name AS parent_category_name
        FROM mirror_records r
        LEFT JOIN mirror_categories c ON c.id = r.category_id
        LEFT JOIN mirror_categories p ON p.id = c.parent_id
        WHERE {_TRANSACTIONS_WHERE}
        ORDER BY r.date_epoch_day DESC, r.created_at_ms DESC, r.id DESC
        LIMIT $7 OFFSET $8
        """,
        *args, page_size, (page - 1) * page_size,
    )
    items = [
        {
            "id": r["id"],
            "type": r["type"],
            "amountMinor": r["amount_minor"],
            "amount": yuan(r["amount_minor"]),
            "dateEpochDay": r["date_epoch_day"],
            "note": r["note"],
            "categoryId": r["category_id"],
            "categoryName": r["category_name"],
            "parentCategoryName": r["parent_category_name"],
            "createdAtEpochMs": r["created_at_ms"],
            "updatedAtEpochMs": r["updated_at_ms"],
            "isDeleted": r["is_deleted"],
        }
        for r in rows
    ]
    return {
        "from": from_day,
        "to": to_day,
        "page": page,
        "pageSize": page_size,
        "total": total,
        "hasMore": page * page_size < total,
        "items": items,
    }


async def stats(
    pool: Pool,
    *,
    from_day: int,
    to_day: int,
    types: list[str],
) -> dict:
    """区间统计（月度/任意区间通用），types 控制返回哪些收支类型。

    四种聚合口径全部照抄 App RecordDao：区间闭区间 + 软删除过滤 + 分类 JOIN。
    缺失类型返回 totalMinor=0（空月份也有确定响应）。
    """
    totals = {t: 0 for t in types}
    for r in await pool.fetch(
        """
        SELECT type, COALESCE(SUM(amount_minor), 0)::bigint AS total
        FROM mirror_records
        WHERE date_epoch_day BETWEEN $1 AND $2 AND is_deleted = false
          AND type = ANY($3::text[])
        GROUP BY type
        """,
        from_day, to_day, types,
    ):
        totals[r["type"]] = r["total"]

    category_totals: dict[str, list] = {t: [] for t in types}
    for r in await pool.fetch(
        """
        SELECT r.type, r.category_id, c.name, c.emoji,
               SUM(r.amount_minor)::bigint AS total
        FROM mirror_records r
        JOIN mirror_categories c ON c.id = r.category_id
        WHERE r.date_epoch_day BETWEEN $1 AND $2 AND r.is_deleted = false
          AND r.type = ANY($3::text[])
        GROUP BY r.type, r.category_id, c.name, c.emoji
        ORDER BY r.type, total DESC, r.category_id
        """,
        from_day, to_day, types,
    ):
        category_totals[r["type"]].append({
            "type": r["type"],
            "categoryId": r["category_id"],
            "name": r["name"],
            "emoji": r["emoji"],
            "totalMinor": r["total"],
            "total": yuan(r["total"]),
        })

    daily_totals: dict[str, list] = {t: [] for t in types}
    for r in await pool.fetch(
        """
        SELECT type, date_epoch_day, SUM(amount_minor)::bigint AS total
        FROM mirror_records
        WHERE date_epoch_day BETWEEN $1 AND $2 AND is_deleted = false
          AND type = ANY($3::text[])
        GROUP BY type, date_epoch_day
        ORDER BY type, date_epoch_day
        """,
        from_day, to_day, types,
    ):
        daily_totals[r["type"]].append({
            "type": r["type"],
            "dateEpochDay": r["date_epoch_day"],
            "totalMinor": r["total"],
            "total": yuan(r["total"]),
        })

    daily_category_totals: dict[str, list] = {t: [] for t in types}
    for r in await pool.fetch(
        """
        SELECT r.type, r.date_epoch_day, r.category_id, c.name,
               SUM(r.amount_minor)::bigint AS total
        FROM mirror_records r
        JOIN mirror_categories c ON c.id = r.category_id
        WHERE r.date_epoch_day BETWEEN $1 AND $2 AND r.is_deleted = false
          AND r.type = ANY($3::text[])
        GROUP BY r.type, r.date_epoch_day, r.category_id, c.name
        ORDER BY r.type, r.date_epoch_day, r.category_id
        """,
        from_day, to_day, types,
    ):
        daily_category_totals[r["type"]].append({
            "type": r["type"],
            "dateEpochDay": r["date_epoch_day"],
            "categoryId": r["category_id"],
            "name": r["name"],
            "totalMinor": r["total"],
            "total": yuan(r["total"]),
        })

    return {
        "from": from_day,
        "to": to_day,
        "totals": [
            {"type": t, "totalMinor": totals[t], "total": yuan(totals[t])}
            for t in types
        ],
        "categoryTotals": category_totals,
        "dailyTotals": daily_totals,
        "dailyCategoryTotals": daily_category_totals,
    }
