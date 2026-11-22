"""FastAPI 入口：鉴权、路由装配、生命周期。

鉴权（docs §5）：
  - Bearer SYNC_KEY → 写路径：POST /api/sync、GET /api/export、GET /api/versions
  - Bearer READ_KEY → 读路径：GET /api/meta、GET /api/categories、
    GET /api/transactions、GET /api/stats
  - 401 统一 {"detail": "unauthorized"}，不区分 key 类型（不泄露信息）
"""

from contextlib import asynccontextmanager
import json

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import queries, sync
from .config import config
from .db import pool as dbpool

_bearer = HTTPBearer(auto_error=False)

_VALID_TYPES = ("EXPENSE", "INCOME")


def _auth(expected: str):
    async def check(
        cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
    ) -> None:
        if cred is None or cred.credentials != expected:
            raise HTTPException(status_code=401, detail="unauthorized")
    return check


def _check_range(from_day: int, to_day: int) -> None:
    """日期区间护栏：from<=to 且跨度不超上限（防一次拉全账本）。"""
    if from_day > to_day:
        raise HTTPException(status_code=400, detail="from 必须小于等于 to")
    if to_day - from_day > queries.RANGE_MAX_DAYS:
        raise HTTPException(
            status_code=400,
            detail=f"查询区间过大（上限 {queries.RANGE_MAX_DAYS} 天）",
        )


def _parse_category_ids(raw: str | None) -> list[str] | None:
    """categoryIds=a,b,c → 列表；空项/超长/超量都是 400。"""
    if raw is None:
        return None
    ids = [s.strip() for s in raw.split(",")]
    if not ids or any(not s for s in ids):
        raise HTTPException(status_code=400, detail="categoryIds 含空 id")
    if len(ids) > queries.CATEGORY_FILTER_MAX:
        raise HTTPException(
            status_code=400,
            detail=f"categoryIds 过多（上限 {queries.CATEGORY_FILTER_MAX} 个）",
        )
    return ids


require_sync_key = _auth(config.sync_key)
require_read_key = _auth(config.read_key)


@asynccontextmanager
async def _lifespan(app: FastAPI):
    config.validate_startup()
    await dbpool.init(config.database_url)
    yield
    await dbpool.close()


app = FastAPI(title="jianji-sync", version="0.1.0", lifespan=_lifespan)


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
    _check_range(from_day, to_day)
    if type is not None and type not in _VALID_TYPES:
        raise HTTPException(status_code=400, detail="type 必须是 EXPENSE 或 INCOME")
    return await queries.list_transactions(
        await dbpool.pool(),
        from_day=from_day,
        to_day=to_day,
        rtype=type,
        keyword=keyword,
        category_ids=_parse_category_ids(categoryIds),
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
    _check_range(from_day, to_day)
    if type is not None and type not in _VALID_TYPES:
        raise HTTPException(status_code=400, detail="type 必须是 EXPENSE 或 INCOME")
    types = [type] if type is not None else list(_VALID_TYPES)
    return await queries.stats(
        await dbpool.pool(), from_day=from_day, to_day=to_day, types=types
    )
