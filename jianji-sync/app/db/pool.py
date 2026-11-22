"""asyncpg pool：应用生命周期内单池；启动时确保 schema 存在（幂等）。"""

from pathlib import Path

import asyncpg

_pool: asyncpg.Pool | None = None

SCHEMA_SQL = (Path(__file__).parent / "schema.sql").read_text(encoding="utf-8")


async def init(database_url: str) -> asyncpg.Pool:
    """创建连接池并确保 schema 就绪。应用启动时调用一次。"""
    global _pool
    _pool = await asyncpg.create_pool(database_url, min_size=1, max_size=5)
    async with _pool.acquire() as conn:
        await conn.execute(SCHEMA_SQL)
    return _pool


async def pool() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("db pool 未初始化（init 未调用）")
    return _pool


async def close() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None
