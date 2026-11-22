# 简记 App 端：需求与修改（云同步）

> 状态：设计定稿，待实施 ｜ 2026-11-14
> 前置文档：`jianji-sync/docs/服务器端需求与架构.md`（接口契约、指纹规则、熔断协议的定义方）
> 原则：App 是唯一写入方；同步是单向推送（App → 服务器）；除"从服务器恢复"外，App 永不从服务器拉数据。

---

## 1. 改动总览

| # | 位置 | 改动 | 性质 |
|---|---|---|---|
| 1 | `LedgerRepository` | 写入前 diff：无语义变化不落库 | 行为修正 |
| 2 | 新增 `data/sync/` 包 | 同步客户端 + 快照指纹 + 触发调度 | 新模块 |
| 3 | `SettingsStore` | 新增同步配置项 | 小改 |
| 4 | `ui/settings` | 新增"云端同步"区块（配置/立即同步/从服务器恢复） | UI |
| 5 | `build.gradle.kts` | 新增 OkHttp 依赖（serialization 已有） | 依赖 |

不动的东西：SAF 每日备份、小部件、应用锁、统计页——零耦合。Room schema 无变化（无迁移）。

## 2. 写入边界：无变化不落库（改动 #1）

**问题**：当前 `updateRecord` 无脑 upsert，"打开→改→改回去→保存"会顶起 `updatedAtEpochMs`，数据没变但版本元数据脏了——后续同步、以及"这笔什么时候改的"的追问都会被污染。

**改法**：`LedgerRepository.updateRecord`（`LedgerRepository.kt:124`）构造新实体前先比对：

```kotlin
val next = old.copy(type=…, amountMinor=…, …)          // 不带 updatedAt
if (next == old) return                                  // 语义无变化：不写库、不顶时间戳
recordDao.upsert(next.copy(updatedAtEpochMs = now))
```

覆盖位置：`updateRecord`；`renameCategory` / `updateCategory` 同样加守卫。`addRecord`（UUID 新建）与 `softDelete`（状态真实变化）不需要。

这是**第一层防线**，收益在源头：Room 不发变更通知，小部件/备份/同步下游全部天然安静。

## 3. 同步模块（改动 #2）：`data/sync/`

### 3.1 SyncSettings（扩展 SettingsStore）

DataStore 新增键：`syncEnabled: Boolean`、`serverUrl: String`、`syncKey: String`、`lastSyncedHash: String`、`lastSyncedAt: Long`、`pendingWipeConfirm: Boolean`。

**配置值的来源**（部署流程，见服务器端文档 §7）：

- `serverUrl`：部署者在自有服务器上跑 docker compose 后，走已有 Nginx 暴露的 HTTPS 域名/路径，自己填
- `syncKey`：部署时在服务器 `.env` 里用 `openssl rand -hex 32` 生成，部署者自己查看后**手动粘贴**进 App（自用场景，人就是可信信道；剪贴板粘贴即可，不做扫码/配对服务器等机制）
- 配错的表现明确：地址错/服务器挂 → 测试连接直接失败；key 错 → 401

### 3.2 快照导出与指纹

- 快照**复用 BackupCodec 的 `SnapshotV1`**（`recordDao.snapshot()` + `categoryDao.snapshot()` 已含墓碑），同步与备份共用一套编解码，不另造格式
- 指纹（决定是否上传）：canonical 形式 = records 按 id 排序、categories 按 id 排序、字段按固定顺序拼接、**排除 `updatedAtEpochMs`**，sha256 hex。规则与服务端 `canonical.py` 严格一致（两边各有单测，用同几组 fixture 对答案）
- `lastSyncedHash == 本地指纹` → 本次触发直接跳过，不发请求

### 3.3 SyncClient（HTTP）

新增依赖仅 **OkHttp**（kotlinx-serialization 已在）。两个调用：

```
POST {serverUrl}/api/sync?force={bool}     body=SnapshotV1 JSON, Bearer SYNC_KEY
  200 noop      → 无新版本，更新 lastSyncedAt
  200 ok        → 记录返回的 version，更新 lastSyncedHash/At
  409 wipe_suspected → 进入熔断确认流程（§3.5）

GET {serverUrl}/api/export?version=        Bearer SYNC_KEY
  → 快照 JSON（恢复流程用，§5）
```

超时 30s；失败静默（见 §6 边界）。

### 3.4 触发调度（不做 WorkManager）

用法事实：一天多次打开。三个触发点足够，全部在进程存活期内，不引入后台执行框架：

| 触发点 | 实现 | 说明 |
|---|---|---|
| App 启动 | `JianJiApp` 启动协程 | 保证每日至少一次 |
| 退到后台 | ProcessLifecycleOwner `onStop` + 30s debounce | 合并连续操作的多次编辑 |
| 手动 | 设置页"立即同步"按钮 | 调试 + 用户安心 |

每日定时兜底**不做**——启动触发在"一天用几次"的使用模式下已覆盖；数据不会丢，只是同步延迟到下次打开。

### 3.5 误删熔断交互

收到 `409 wipe_suspected`（携带 current_live / new_live）：

1. 置 `pendingWipeConfirm = true` 并暂存本次快照；
2. App 在前台 → 立即弹 AlertDialog：「数据将从 **{current_live}** 条变为 **{new_live}** 条，确认用当前手机数据覆盖服务器？」
3. 用户确认 → 带 `force=true` 重传；取消 → 丢弃，`lastSyncedHash` 不更新（下次触发重新走熔断判断，直到处理）；
4. 触发时 App 在后台 → 挂起，回前台时再弹。

### 3.6 从服务器恢复（唯一的服务器→手机通道）

设置页 → "从服务器恢复" → 调 `GET /api/export` 拿版本列表接口（`/api/meta` + 服务器可加轻量 `GET /api/versions`）→ 用户选版本 → 二次确认（红色警告：将**完全替换**本地数据）→ 事务内 `DELETE FROM records/categories` + `upsertAll(snapshot)` 全量替换（不是合并）→ 恢复完成后清 `lastSyncedHash`，下次同步作为新版本推上去。

## 4. 设置页 UI（改动 #4）

"云端同步"区块：

```
[ 开关 ] 云端同步
  服务器地址  https://________
  同步密钥    ••••••••
  [测试连接]            ← GET /api/meta，通则显示"已连接 · 版本 N · 最后同步 xx:xx"
  状态        已同步 · 版本 12 · 3 分钟前
  [立即同步]  [从服务器恢复…]
```

## 5. 同步范围（明确边界）

**同步的**：`records` 全部（含墓碑）+ `categories` 全部（含 emoji、排序、墓碑）——两级结构"支出/收入 → 分类"由 `categories[].type` + `records[].type` 天然携带。账本语义数据全部在快照内。

**不同步的**（设备级偏好，DataStore）：应用锁开关、SAF 自动备份开关及其目录 URI、主题。理由：它们是**设备偏好**而非账本数据——换机恢复时应重新选择；SAF 目录 URI 是 Android 授权凭据，换设备无意义；主题/锁屏与账本无关。将来若出现真正值得跨设备的设置，走 `SnapshotV1.version` 升级（前向兼容已就位），不回头改 v1 语义。

## 6. 快照与恢复的语义

- 同步上传 = 备份的在线版（同一 SnapshotV1），SAF 备份照旧独立运行
- 恢复 = 回滚到指定版本，全量替换、幂等、可重复执行
- 恢复后 `lastSyncedHash` 清空 → 恢复结果会作为**新版本**推上服务器（历史版本都还在，双向都可再回滚）

## 7. 边界情况处理

| 情况 | 行为 |
|---|---|
| 无网络 / 服务器不可达 | 静默失败，下次触发重试；不打扰用户（手动点"立即同步"时才 toast 报错） |
| Token 错误 / 401 | 手动同步时明确提示；自动触发静默 |
| 快照 > 20MB | 上传前检查，超限跳过并记日志（个人账本正常用不到，防异常数据炸内存） |
| 恢复过程中断 | Room 事务保证原子性：要么完整替换要么原样 |
| 服务器版本比本地新（恢复过旧版本） | 允许：内容指纹不同即新版本，服务端追加落版，不拒绝 |

## 8. 测试要点

1. **无变化不落库**：改回原值保存 → `updatedAtEpochMs` 不变、Room Flow 不发射（改动 #1 的直接验收）
2. **指纹排他性**：只改 `updatedAt` 的"假快照"→ hash 不变 → 不上传；改 note 一个字 → hash 变
3. **双端指纹一致性**：与服务端 `canonical.py` 用同一组 fixture 各自算 sha256，必须相等
4. **熔断**：409 → 弹窗 → force 重传成功；取消 → pending 状态保持
5. **恢复**：替换后本地与所选版本逐字段一致；中断场景库完好
6. **真实链路冒烟**：连 jianji-sync M1 环境，完整走 推送→noop→修改→再推→新版本

## 9. 里程碑

| 阶段 | 内容 | 验收 |
|---|---|---|
| A1 | 改动 #1（无变化不落库）+ 测试 | 测试要点 1 |
| A2 | sync 模块 + 设置页 | 真机：记一笔 → 退后台 30s → 服务器出现新版本 |
| A3 | 熔断 + 恢复 | 测试要点 4/5 |

版本号按现行「日期+序号」规则，实际发布时确定；升级安装，签名密钥不变。
