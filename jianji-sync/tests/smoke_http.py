"""端到端冒烟脚本（需要真实 PG；在服务器或本机 docker compose up 后执行）。

用法：
  cd jianji-sync
  docker compose up -d jianji-sync-db
  DATABASE_URL=postgres://jianji:<pw>@localhost:5432/jianji \
  SYNC_KEY=<k> READ_KEY=<k2> python tests/smoke_http.py

覆盖 docs §9 M1 验收路径：推送 → noop → 修改 → 再推 → 新版本 → 熔断 → force。
"""

import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402
from tests.gen_fixture import CAT_FOOD, CAT_SALARY, rec, snap  # noqa: E402


def run() -> None:
    with TestClient(main.app) as c:  # with → 触发 lifespan（连 DB + 建 schema）
        auth = {"Authorization": f"Bearer {os.environ['SYNC_KEY']}"}
        read = {"Authorization": f"Bearer {os.environ['READ_KEY']}"}

        s1 = snap([rec(f"smoke-{i}", dateEpochDay=20680 + i,
                        note=f"冒烟{i}", amountMinor=1000 + i)
                   for i in range(150)], [CAT_FOOD, CAT_SALARY])  # 150 条活记录

        r = c.post("/api/sync", json=s1, headers=auth)
        assert r.status_code == 200 and r.json()["status"] == "ok", r.text
        v1 = r.json()["version"]
        print(f"✓ 首推落版 v{v1}")

        r = c.post("/api/sync", json=s1, headers=auth)
        assert r.status_code == 200 and r.json() == {"status": "noop", "version": v1}, r.text
        print("✓ 同快照重推 → noop")

        s2 = snap(s1["records"][:3], s1["categories"])  # 3 条（< 150*30%，触发熔断）
        r = c.post("/api/sync", json=s2, headers=auth)
        assert r.status_code == 409 and r.json()["detail"]["code"] == "wipe_suspected", r.text
        print("✓ 疑似清空 → 409 拦截")

        r = c.post("/api/sync", params={"force": "true"}, json=s2, headers=auth)
        assert r.status_code == 200 and r.json()["status"] == "ok", r.text
        v2 = r.json()["version"]
        assert v2 > v1
        print(f"✓ force 放行落版 v{v2}")

        r = c.get("/api/versions", headers=auth)
        assert v2 in [v["version"] for v in r.json()["versions"]]
        print(f"✓ 版本列表 {len(r.json()['versions'])} 条")

        r = c.get("/api/export", params={"version": v1}, headers=auth)
        assert r.status_code == 200 and r.json() == s1, "导出与原快照不一致"
        print("✓ 按版本导出一致")

        r = c.get("/api/meta", headers=read)
        assert r.status_code == 200 and r.json()["liveCount"] == 3, r.text
        print(f"✓ meta 镜像 liveCount=3, version={r.json()['version']}")
        print("\nM1 冒烟全部通过 ✅")


if __name__ == "__main__":
    run()
