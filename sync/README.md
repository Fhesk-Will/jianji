# sync · 简记云端同步服务

简记录账 App（jianji 仓库根目录的 Android 工程）的云端镜像 + AI 只读查询服务。同仓子项目，目录 `sync/`，部署独立。

- **备份**：接收 App 推送的全量快照（SnapshotV1），落 PostgreSQL，带版本历史、误删熔断、滚动保留
- **AI 只读查询**：HTTP + MCP 接口，服务端算好数字，AI 只引用不计算

接口契约、指纹规则与熔断协议以 `app/` 下的实现为准（`canonical.py` / `validate.py` / `sync.py`）。

## 里程碑

- [x] M1：sync 核心 + 镜像 + 去重/熔断 + versions/export/meta —— 已在服务器通过集成冒烟
- [x] M2：只读接口 categories/transactions/stats + 口径 golden 对账 —— 已在服务器通过集成验收
- [ ] M3：MCP 挂载 + 上线

## 接口一览

统一前缀 `/api`；鉴权均为 `Authorization: Bearer <key>`。

| 接口 | key | 说明 |
| --- | --- | --- |
| `POST /sync?force=` | SYNC | 推送全量快照（409 = 疑似误删熔断） |
| `GET /versions` / `GET /export?version=` | SYNC | 版本列表 / 按版本导出（App 恢复用） |
| `GET /meta` | READ | 版本、计数、最新业务日期 |
| `GET /categories?includeDeleted=` | READ | 分类清单，排序同 App（type, sortOrder, name） |
| `GET /transactions?from=&to=&type=&keyword=&categoryIds=&page=&pageSize=` | READ | 明细分页（date/createdAt 倒序，pageSize ≤ 200，区间 ≤ 3700 天） |
| `GET /stats?from=&to=&type=` | READ | 合计 / 分类合计 / 每日合计 / 每日分类合计（整数分 + 元字符串） |

未带 key、key 错误、SYNC/READ 混用一律 401，不区分原因。统计口径与 App Room SQL 对齐，
golden fixture（`dev/tests/sync/fixtures/query_cases.json`）是双端对账的裁判。

## 部署流程

已在 Linux 服务器（Docker + docker compose 插件）实测通过，适用于迁移到新服务器。

### 1. 准备代码与密钥

```bash
# 把 sync/ 目录放到服务器任意位置，例如 /opt/jianji-sync
cd /opt/jianji-sync
cp .env.example .env   # 没有 .env.example 时直接新建 .env
```

`.env` 填三个值，全部用 `openssl rand -hex 32` 生成：

- `SYNC_KEY`：App 写入用，不得泄露
- `READ_KEY`：AI 只读用，与 `SYNC_KEY` 必须不同
- `POSTGRES_PASSWORD`：数据库口令

`.env` 权限 `chmod 600 .env`，只留在服务器，不进 Git。

### 2. 构建启动

```bash
docker compose up -d --build
for i in 1 2 3 4 5; do
  curl -fsS http://127.0.0.1:8100/healthz && break
  sleep 3
done
# 预期 {"ok": true}
```

应用只绑定 `127.0.0.1:8100`，数据库不对外；容器 `restart: unless-stopped`，数据在卷 `jianji-sync-pgdata`。

### 3. 反代暴露 HTTPS

App 端只允许 HTTPS。在现有反代（Caddy 或 Nginx）加一条规则，示例用 `/jianji/` 前缀：

```caddy
# Caddyfile
handle_path /jianji/* {
    reverse_proxy 127.0.0.1:8100
}
```

```nginx
# Nginx
location /jianji/ {
    proxy_pass http://127.0.0.1:8100/;
    client_max_body_size 20m;   # 与 SNAPSHOT_MAX_BYTES 默认 20MB 一致
}
```

反代要放开请求体上限（快照默认上限 20MB），路径前缀可有可无——用独立子域名时可不加前缀。真实域名、密钥只留服务器，不进仓库。

入口层是可替换的：Tailscale serve 或任意 Nginx/Caddy 反代都等价，前提是满足三件事——HTTPS（App 只允许 HTTPS，裸 HTTP 仅本机调试可显式放开）、路径前缀正确剥离、请求体上限与 `SNAPSHOT_MAX_BYTES` 对齐。服务端只绑 `127.0.0.1:8100` 说 plain HTTP，不感知入口是 Tailscale 还是域名反代。

### 4. 部署后验证

把仓库的 `dev/tests/sync/` 也传到服务器（如 `/opt/jianji-sync-tests`），然后复用 app 镜像跑，不依赖宿主机 Python：

```bash
cd /opt/jianji-sync
set -a && . ./.env && set +a
img=$(docker compose images -q jianji-sync-app)

# 服务端单元测试
docker run --rm -v /opt/jianji-sync-tests:/t:ro -w /t "$img" python -m pytest /t -q

# 集成冒烟：首推 → noop → 409 熔断 → force → 版本列表 → 导出一致 → meta
docker run --rm --network host -v /opt/jianji-sync-tests:/t:ro -w /t \
  -e DATABASE_URL="postgres://jianji:$POSTGRES_PASSWORD@127.0.0.1:5432/jianji" \
  -e SYNC_KEY="$SYNC_KEY" -e READ_KEY="$READ_KEY" \
  "$img" python smoke_http.py
```

冒烟会写测试数据到数据库，正式环境通过后清掉测试版本再用。若临时需要宿主机直连数据库排障，可在 compose 上加 `ports: ["127.0.0.1:55432:5432"]` 的本地映射，用完即删。

### 5. 升级与回滚

```bash
git pull                       # 或重新上传 sync/
docker compose up -d --build   # 重新构建并滚动重启
curl -fsS http://127.0.0.1:8100/healthz
# 回滚：切回旧代码后重复上面两步；数据库卷不回滚
```

升级前备份数据库卷：`docker run --rm -v jianji-sync-pgdata:/data -v $PWD:/backup alpine tar czf /backup/pgdata.tgz -C /data .`
