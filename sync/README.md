# sync · 简记云端同步服务

简记录账 App（jianji 仓库根目录的 Android 工程）的云端镜像 + AI 只读查询服务。同仓子项目，目录 `sync/`，部署独立。

- **备份**：接收 App 推送的全量快照（SnapshotV1），落 PostgreSQL，带版本历史、误删熔断、滚动保留
- **AI 只读查询**：HTTP + MCP 接口，服务端算好数字，AI 只引用不计算

接口契约、指纹规则与熔断协议以 `app/` 下的实现为准（`canonical.py` / `validate.py` / `sync.py`）。

## 本地开发

```bash
cd sync                  # 仓库内子目录
python3.12 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
pytest tests/ -q          # 纯函数层（指纹/金额/校验/鉴权），无需 PG
```

端到端冒烟（需要 PG）：

```bash
docker compose up -d jianji-sync-db
export DATABASE_URL=postgres://jianji:<pw>@localhost:5432/jianji
export SYNC_KEY=$(openssl rand -hex 32) READ_KEY=$(openssl rand -hex 32)
python tests/smoke_http.py   # 推送→noop→新版本→熔断→force→导出→meta
```

## 部署（服务器）

```bash
cp .env.example .env
# 编辑 .env：SYNC_KEY / READ_KEY / POSTGRES_PASSWORD 都用 openssl rand -hex 32 生成
docker compose up -d --build
curl -s localhost:8100/healthz   # {"ok": true}
```

Nginx 反代（示例，路径前缀 `/jianji/`）：

```nginx
location /jianji/ {
    proxy_pass http://127.0.0.1:8100/;
    proxy_set_header Host $host;
}
```

App 端填 `serverUrl = https://<域名>/jianji`，手动粘贴 `SYNC_KEY`。

## 安全与密钥

- 两把 key：`SYNC_KEY`（App 写入）与 `READ_KEY`（AI/MCP 读取），缺一启动即失败
- 两者不得相同；泄露 `READ_KEY` 不影响数据写入，泄露 `SYNC_KEY` 需换 `.env` 重启
- 服务只绑 `127.0.0.1`，公网入口全走 Nginx TLS

## 里程碑

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | sync 核心 + 镜像 + 去重/熔断 + versions/export/meta | ✅ 代码完成，待服务器集成验收 |
| M2 | 只读接口 transactions/stats + 口径对账（golden case） | 待做 |
| M3 | MCP 挂载 + 上线 | 待做 |
