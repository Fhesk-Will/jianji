"""FastAPI 入口：鉴权、路由装配、生命周期。

鉴权（docs §5）：
  - Bearer SYNC_KEY → 写路径：POST /api/sync、GET /api/export、GET /api/versions
  - Bearer READ_KEY → 读路径：GET /api/meta、GET /api/categories、
    GET /api/transactions、GET /api/stats，以及 /mcp（MCP 只读挂载）
  - 401 统一 {"detail": "unauthorized"}，不区分 key 类型（不泄露信息）
"""

from contextlib import asynccontextmanager
import json

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.types import ASGIApp, Receive, Scope, Send

from . import mcp_server, queries, sync
from .config import config
from .db import pool as dbpool

_bearer = HTTPBearer(auto_error=False)


def _auth(expected: str):
    async def check(
        cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
    ) -> None:
        if cred is None or cred.credentials != expected:
            raise HTTPException(status_code=401, detail="unauthorized")
    return check


def _bad_params(e: queries.ParamError) -> HTTPException:
    return HTTPException(status_code=400, detail=str(e))


class _ReadKeyGuard:
    """MCP 子应用的 Bearer READ_KEY 守卫（MCP 不走 FastAPI 依赖注入）。

    校验失败返回与 API 一致的 401，不区分「无 key」与「key 错误」。
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http":
            headers = dict(scope.get("headers") or [])
            auth = headers.get(b"authorization", b"").decode("latin-1")
            if auth != f"Bearer {config.read_key}":
                body = b'{"detail":"unauthorized"}'
                await send({
                    "type": "http.response.start",
                    "status": 401,
                    "headers": [(b"content-type", b"application/json")],
                })
                await send({"type": "http.response.body", "body": body})
                return
        await self.app(scope, receive, send)


class _McpDispatch:
    """把 /mcp（及 /mcp/ 子路径）直接转给 MCP 只读挂载。

    不用 app.mount：Starlette 0.41 的 Mount 对无尾斜杠的 /mcp 一律 307 到 /mcp/，
    在带路径前缀的反代（Nginx /jianji/ 等）下重定向会丢前缀。中间件直转让
    /mcp 与 /mcp/ 等价可用；其余请求原样透传给主应用。
    """

    def __init__(self, app: ASGIApp, mcp: ASGIApp) -> None:
        self.app = app
        self.mcp = mcp

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = scope.get("path", "")
        if scope["type"] in ("http", "websocket") and (
            path == "/mcp" or path.startswith("/mcp/")
        ):
            child_scope = dict(scope, path=path[len("/mcp"):] or "/")
            await self.mcp(child_scope, receive, send)
            return
        await self.app(scope, receive, send)


require_sync_key = _auth(config.sync_key)
require_read_key = _auth(config.read_key)

# MCP 子应用（streamable HTTP，无状态模式）；lifespan 由主应用在下面一并启动。
_mcp_app = mcp_server.mcp.http_app(
    path="/", transport="streamable-http", stateless_http=True)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    config.validate_startup()
    await dbpool.init(config.database_url)
    try:
        # FastMCP 要求父应用把 MCP 子应用的 lifespan 一并启动（session manager 的
        # 后台任务组依赖它）；调用 lifespan(app) 即得上下文管理器。
        async with _mcp_app.lifespan(app):
            yield
    finally:
        await dbpool.close()


app = FastAPI(title="jianji-sync", version="0.1.0", lifespan=_lifespan)

# MCP 只读挂载（里程碑 M3）：/mcp 直转 + _ReadKeyGuard 以 READ_KEY 鉴权。
# 与 API 共用同一个 db pool（主应用 lifespan 初始化），MCP 层不持有任何密钥。
app.add_middleware(_McpDispatch, mcp=_ReadKeyGuard(_mcp_app))


@app.get("/healthz")
async def healthz() -> dict:
    """无鉴权存活探针（compose healthcheck 用）。"""
    return {"ok": True}


@app.post("/api/sync", dependencies=[Depends(require_sync_key)])
async def api_sync(request: Request, force: bool = Query(default=False)) -> dict:
    raw = await request.body()
    if len(raw) > config.snapshot_max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"快照超过大小限制（{config.snapshot_max_bytes // 1024 // 1024}MB）",
        )
    try:
        return await sync.sync_snapshot(await dbpool.pool(), raw, force)
    except sync.WipeSuspected as e:
        raise HTTPException(
            status_code=409,
            detail={
                "code": "wipe_suspected",
                "currentLiveCount": e.cur_live,
                "incomingLiveCount": e.new_live,
                "hint": "疑似误清空。确认后带 ?force=true 重传。",
            },
        )
    except sync.validate.ValidationError as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/api/versions", dependencies=[Depends(require_sync_key)])
async def api_versions() -> dict:
    return {"versions": await queries.list_versions(await dbpool.pool())}


@app.get("/api/export", dependencies=[Depends(require_sync_key)])
async def api_export(version: int | None = Query(default=None)) -> Response:
    snap = await queries.export_snapshot(await dbpool.pool(), version)
    if snap is None:
        raise HTTPException(status_code=404, detail="版本不存在")
    return Response(
        content=json.dumps(snap, ensure_ascii=False),
        media_type="application/json; charset=utf-8",
    )


@app.get("/api/meta", dependencies=[Depends(require_read_key)])
async def api_meta() -> dict:
    m = await queries.meta(await dbpool.pool())
    if m is None:
        return {"version": 0, "recordCount": 0, "liveCount": 0,
                "liveCategoryCount": 0, "latestDateEpochDay": None, "serverTime": None}
    return m


@app.get("/api/categories", dependencies=[Depends(require_read_key)])
async def api_categories(includeDeleted: bool = Query(default=False)) -> dict:
    """分类清单（默认不含墓碑）。排序与 App 分类管理页一致。"""
    return {"items": await queries.list_categories(await dbpool.pool(), includeDeleted)}


@app.get("/api/transactions", dependencies=[Depends(require_read_key)])
async def api_transactions(
    from_day: int = Query(alias="from"),
    to_day: int = Query(alias="to"),
    type: str | None = Query(default=None),
    keyword: str | None = Query(default=None, max_length=100),
    categoryIds: str | None = Query(default=None),
    includeDeleted: bool = Query(default=False),
    page: int = Query(default=1, ge=1),
    pageSize: int = Query(default=queries.PAGE_SIZE_DEFAULT, ge=1,
                          le=queries.PAGE_SIZE_MAX),
) -> dict:
    """明细分页：起止日期必填，排序 date DESC + createdAt DESC + id 兜底。"""
    try:
        queries.check_range(from_day, to_day)
        category_ids = queries.parse_category_ids(
            categoryIds.split(",") if categoryIds is not None else None
        )
    except queries.ParamError as e:
        raise _bad_params(e)
    if type is not None and type not in queries.VALID_TYPES:
        raise HTTPException(status_code=400, detail="type 必须是 EXPENSE 或 INCOME")
    return await queries.list_transactions(
        await dbpool.pool(),
        from_day=from_day,
        to_day=to_day,
        rtype=type,
        keyword=keyword,
        category_ids=category_ids,
        include_deleted=includeDeleted,
        page=page,
        page_size=pageSize,
    )


@app.get("/api/stats", dependencies=[Depends(require_read_key)])
async def api_stats(
    from_day: int = Query(alias="from"),
    to_day: int = Query(alias="to"),
    type: str | None = Query(default=None),
) -> dict:
    """区间统计：月度合计 / 分类合计 / 每日合计 / 每日分类合计。

    不传 type 时收支两类都返回（各自独立成块）；口径与 App 统计页一致。
    """
    try:
        queries.check_range(from_day, to_day)
    except queries.ParamError as e:
        raise _bad_params(e)
    if type is not None and type not in queries.VALID_TYPES:
        raise HTTPException(status_code=400, detail="type 必须是 EXPENSE 或 INCOME")
    types = [type] if type is not None else list(queries.VALID_TYPES)
    return await queries.stats(
        await dbpool.pool(), from_day=from_day, to_day=to_day, types=types
    )
