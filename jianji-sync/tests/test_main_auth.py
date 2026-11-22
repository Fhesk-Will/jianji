"""鉴权与路由装配测试（无 DB：401 依赖先于 handler 执行，不触连接池）。

带 PG 的完整 sync/versions/export 集成测试见 tests/smoke_http.py（服务器执行）。
"""

import pytest
from fastapi.testclient import TestClient

from app import main
from app.config import config

client = TestClient(main.app)  # 不用 with：不触发 lifespan（避免连 DB）

SYNC = {"Authorization": f"Bearer {config.sync_key}"}
READ = {"Authorization": f"Bearer {config.read_key}"}


@pytest.mark.parametrize("path,headers", [
    ("/api/sync", {}),
    ("/api/sync", READ),            # 用 READ_KEY 调写接口 → 401
    ("/api/versions", {}),
    ("/api/versions", READ),
    ("/api/export", {}),
    ("/api/export", READ),
    ("/api/meta", {}),
    ("/api/meta", SYNC),            # 用 SYNC_KEY 调读接口 → 401
])
def test_unauthorized(path: str, headers: dict) -> None:
    r = client.post(path, headers=headers) if path == "/api/sync" \
        else client.get(path, headers=headers)
    assert r.status_code == 401, f"{path} 未鉴权访问应 401"
    assert r.json() == {"detail": "unauthorized"}


def test_keys_distinct() -> None:
    """配置层防线：两把 key 不同值（config 已在 import 时校验，此处兜底断言）。"""
    assert config.sync_key and config.read_key and config.sync_key != config.read_key
