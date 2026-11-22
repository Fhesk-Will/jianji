"""FastAPI 入口：鉴权、路由装配、生命周期。

鉴权（docs §5）：
  - Bearer SYNC_KEY → 写路径：POST /api/sync、GET /api/export、GET /api/versions
  - Bearer READ_KEY → 读路径：GET /api/meta（M2 再挂 transactions/stats/categories）
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


def _auth(expected: str):
    async def check(
        cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
    ) -> None:
        if cred is None or cred.credentials != expected:
            raise HTTPException(status_code=401, detail="unauthorized")
    return check


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
