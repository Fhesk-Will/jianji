"""MCP 只读挂载（里程碑 M3）：把只读查询以 MCP 工具提供给 AI。

约束（阶段 4 规划 §1）：
  - 只复用 queries.py 的数据层与校验，禁止另写一套统计口径；
  - 只读：不提供推送、导出历史版本、force 或任何数据库写能力；
  - 不持有密钥——鉴权由 main.py 的 /mcp 守卫（Bearer READ_KEY）完成；
  - 输入约束与 HTTP API 一致（区间上限、分页上限、type 枚举、categoryIds 上限）。
"""

from fastmcp import FastMCP
from fastmcp.exceptions import ToolError

from . import queries
from .db import pool as dbpool

mcp = FastMCP(
    "jianji-ledger",
    instructions=(
        "简记账本的只读查询接口。账本以「快照版本」为最小单位，"
        "可用 get_meta 查看当前版本与计数，用 list_categories 看分类，"
        "用 list_transactions 翻查明细，用 get_stats 做区间统计"
        "（收支口径与记账 App 统计页一致）。"
        "日期一律用 epoch day 整数（from/to 必填且跨度不超过 "
        f"{queries.RANGE_MAX_DAYS} 天）。本服务只读，不能修改任何数据。"
    ),
)


@mcp.tool()
async def get_meta() -> dict:
    """账本元信息：当前快照版本、记录/存活计数、分类数、最新业务日期。

    服务器还没有任何快照时返回 version=0 与各计数 0。
    """
    m = await queries.meta(await dbpool.pool())
    if m is None:
        return {"version": 0, "recordCount": 0, "liveCount": 0,
                "liveCategoryCount": 0, "latestDateEpochDay": None,
                "serverTime": None}
    return m


@mcp.tool()
async def list_categories(include_deleted: bool = False) -> dict:
    """分类清单（默认不含已删除分类）。排序与 App 分类管理页一致。

    Args:
        include_deleted: 是否包含已删除（墓碑）分类，仅排障时用。
    """
    return {"items": await queries.list_categories(
        await dbpool.pool(), include_deleted)}


@mcp.tool()
async def list_transactions(
    from_day: int,
    to_day: int,
    type: str | None = None,
    keyword: str | None = None,
    category_ids: list[str] | None = None,
    include_deleted: bool = False,
    page: int = 1,
    page_size: int = queries.PAGE_SIZE_DEFAULT,
) -> dict:
    """明细分页查询，排序 date DESC + createdAt DESC + id 兜底（与 App 一致）。

    Args:
        from_day: 起始日期（含）。epoch day：1970-01-01 为 0，2026-10-01 = 20727。
        to_day: 结束日期（含）。from>to 或跨度超上限会报错。
        type: 过滤收支类型："EXPENSE" 或 "INCOME"，不传则两者都含。
        keyword: 备注关键词模糊匹配（大小写不敏感）。
        category_ids: 按分类过滤（多选，上限 100 个）。
        include_deleted: 是否含已删除记录，仅排障时用。
        page: 页码，从 1 开始。
        page_size: 每页条数，1～200，默认 50；返回 hasMore 判断是否还有下一页。
    """
    try:
        queries.check_range(from_day, to_day)
        queries.check_page(page, page_size)
        ids = queries.parse_category_ids(category_ids)
    except queries.ParamError as e:
        raise ToolError(str(e))
    if type is not None and type not in queries.VALID_TYPES:
        raise ToolError("type 必须是 EXPENSE 或 INCOME")
    return await queries.list_transactions(
        await dbpool.pool(),
        from_day=from_day,
        to_day=to_day,
        rtype=type,
        keyword=keyword,
        category_ids=ids,
        include_deleted=include_deleted,
        page=page,
        page_size=page_size,
    )


@mcp.tool()
async def get_stats(
    from_day: int,
    to_day: int,
    type: str | None = None,
) -> dict:
    """区间统计：收支合计 / 分类合计 / 每日合计 / 每日分类合计。

    口径与记账 App 统计页一致（闭区间、排除已删除记录）。
    不传 type 时收支两类各自独立成块；缺失类型合计为 0。

    Args:
        from_day: 起始日期（含）。epoch day：1970-01-01 为 0，2026-10-01 = 20727。
        to_day: 结束日期（含）。from>to 或跨度超上限会报错。
        type: 只统计 "EXPENSE" 或 "INCOME"，不传则两者都返回。
    """
    try:
        queries.check_range(from_day, to_day)
    except queries.ParamError as e:
        raise ToolError(str(e))
    if type is not None and type not in queries.VALID_TYPES:
        raise ToolError("type 必须是 EXPENSE 或 INCOME")
    types = [type] if type is not None else list(queries.VALID_TYPES)
    return await queries.stats(
        await dbpool.pool(), from_day=from_day, to_day=to_day, types=types
    )
