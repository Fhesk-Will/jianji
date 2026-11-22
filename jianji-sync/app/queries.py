"""只读查询接口的数据层。

M1 实现：versions / export / meta（恢复流程依赖）。
M2 实现：transactions / stats（口径照抄 App RecordDao SQL + golden case 对账）。
金额转换唯一入口 money.yuan()，SQL 层不做任何换算。
"""

import gzip
import json

from asyncpg.pool import Pool


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
