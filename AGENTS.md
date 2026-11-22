# AGENTS.md · 简记（jianji）

## 1. 项目定位

本仓库含两部分：
- **简记 App**（仓库根 `app/`，Android/Kotlin）：本地优先记账应用，数据只存在本机（Room + DataStore），无账号、无强制服务器。
- **jianji-sync**（`jianji-sync/`，Python/FastAPI）：可选的自部署云同步 + AI 只读查询服务，App 端同步默认关闭。

作者自用，不做多用户与商业化取舍；稳定、够用、包体小优先于功能多。

## 2. 构建与验证

需要 JDK 17 与 Android SDK 34（compileSdk/targetSdk 34，minSdk 26）。

```bash
gradlew.bat :app:assembleDebug      # Windows
./gradlew :app:assembleDebug        # macOS / Linux
gradlew.bat :app:assembleRelease    # 需签名材料，否则构建直接失败
```

- debug 产物：`app/build/outputs/apk/debug/`
- release 产物：`app/build/outputs/apk/release/jianji-v<versionName>-<versionCode>.apk`
- 发布脚本（仅本机，被 `.gitignore` 排除）：`powershell -NoProfile -File scripts/release.ps1`；具体行为以脚本头部注释为准，脚本自身不打 tag、不推送。
- 产物只留最新一版：仓库上一级目录只保留最新上传用 APK 与 `SHA256SUMS.txt`；`scripts/release.ps1` 出包时自动删旧包，发布后跑一次 `-PostPublish` 删测试包。
- 发布用的是已测过的产物：直接上传本机 `jianji-v<versionName>.apk`，不为发布重新构建、不重算摘要。
- release 签名来自仓库根目录的 `keystore.properties`（字段：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`，指向 `jianji-release.jks`）。仓库不跟踪密钥文件；`keystore.properties` 在本仓库被 Git 跟踪但仅含字段名不含真实值时可直接构建，缺失则 release 构建失败。

## 3. 版本号机制

- 格式：`<yyyyMMdd>.<序号>`（例：`2026.11.04.1`），序号=当天第几版。
- 兜底值在 `gradle.properties`（`VERSION_NAME`/`VERSION_CODE`）；release.ps1 负责实际递增。
- 仓库外分发包命名：本地测试包带 `HHmm` 时间戳（`scripts/release.ps1` 设置），GitHub Release 资产名不带时间戳，`SHA256SUMS.txt` 记录的是上传名。
- 同日再发一版：日期不动、序号 +1；跨日：日期改为当天、序号回 1。
- 不要在代码里写版本号；界面显示用 `BuildConfig.VERSION_NAME`。

## 4. 代码组织

App 包根 `app/src/main/java/com/jianji/app/`（注意：仓库根 `app/` 是 Android 工程，`jianji-sync/app/` 是 Python 服务，路径不要混），规则：按层分包，一层一个职责。

- 分层原则：`data/` 持久化与仓库、`ui/` 按页面分包（含 `nav`/`components`/`theme`）、`util/` 纯函数、`widget/` 桌面小部件；具体包与页面以实际目录为准。
- 根目录 `JianJiApp.kt` 含 Application 与手写依赖容器，不引 DI 框架。
- 新增页面放 `ui/<页面名>/`；新增数据表按 `entity → dao → repository` 顺序补；只有被两个以上页面使用的 UI 才放 `ui/components/`。
- 技术事实：Compose + Material3，Room 存数据，DataStore 存设置；minSdk 26 可直接用 `java.time`。

## 5. jianji-sync 子项目（`jianji-sync/`）

- 结构：`app/`（FastAPI 服务：sync/queries/canonical/money/db）、`docs/`（需求与架构、App 端修改、实施计划——接口契约、指纹规则、熔断协议的定义方）、`tests/`（纯函数单测 + `smoke_http.py` 服务器冒烟）。
- 技术栈：Python 3.12 + FastAPI + asyncpg + PostgreSQL 16（独立容器，docker-compose）。
- 本地测试（无需 PG）：`cd jianji-sync && python -m pytest tests/ -q`。
- 集成验收（需 PG）：`docker compose up -d jianji-sync-db` 后跑 `python tests/smoke_http.py`，覆盖 推送→noop→新版本→熔断→force→导出→meta。
- 密钥：`SYNC_KEY`（App 写入）/ `READ_KEY`（AI 读取）从 `.env` 读，缺失启动即失败；`.env` 不入库（`.gitignore` 已排除）。
- 指纹规则改动 = 双端同步改：`app/canonical.py` 与 App 端 Kotlin 实现必须同提交，并重跑 `tests/gen_fixture.py` 重发 `tests/fixtures/canonical_cases.json`（App 端镜像同一文件）。
- 里程碑状态见 `jianji-sync/README.md`。

## 6. 硬性禁止项

- 把 `keystore.properties`、`*.jks`、`local.properties`、`jianji-sync/.env` 加入 Git（`.gitignore` 已排除，不要用 `git add -f`）。
- 在代码里硬编码版本号。
- 未经确认引入新依赖，尤其是会显著增大包体的库（体积以 `app/build/outputs/apk/release/` 的实际产物为准）。
- 改动签名配置，或让 release 回退到 debug 签名。
- 把 README 写成开发文档；README 面向普通用户。

## 7. 改动纪律

- 跨包改动先看本文件的「代码组织」与「事实核验入口」；文档与代码冲突时以代码为准，同次改动里修正文档。
- 只改受影响的最小范围；不顺手重排 import、不改无关命名。
- UI 改动后至少跑一次 `:app:assembleDebug` 确认编译。
- 新增设置项同步更新 SettingsStore；新增 Room 表必须出迁移方案，禁止 drop 重建。

## 8. 事实核验入口

不确定时去这些地方查，不要猜：

- 版本号字段、构建命令、签名要求：`gradle.properties`、`app/build.gradle.kts`、`scripts/release.ps1`（本机文件）。
- 依赖及版本：`gradle/libs.versions.toml` 与 `app/build.gradle.kts` 的 `dependencies`。
- 某功能是否存在：先在 `app/src/main/java/com/jianji/app/` 搜代码，再对照 README 功能清单；两者不一致以代码为准。
- 是否已砍：`.gitignore`、`git status`、当前包结构。
- 产物路径与包体大小：`app/build/outputs/apk/`。
- 同步接口契约、指纹规则、熔断协议：`jianji-sync/docs/`（定义方）与 `jianji-sync/app/`（实现）。

## 9. 文档自维护

必须同步更新本文档的触发条件（任一命中就改）：

1. 增删功能，导致 README 功能清单变化；
2. 版本号机制、字段名或派生规则变化；
3. 构建命令、产物路径、发布脚本行为变化；
4. 依赖策略变化（新增或移除体积较大的库）；
5. 包结构或分层职责变化；
6. 出现"被砍功能又加回来"这类方向性变化。

更新方式：

- 只改受影响的小节，不重写全文。
- 新增条目必须能指到具体文件或命令；指不到具体落点的，写进第 8 节作为排查入口，不写进正文当规则。
- 保持全文不超过 120 行；超限时合并或删除旧条目，而不是继续追加。
- 文末“最后核对”日期由 `scripts/release.ps1` 读取，超过 30 天会提示复核。

冲突处理：文档与代码不一致时以代码为准，并在同一次改动里修正文档。

最后核对：2026-11-21，按 `gradle.properties`、`app/build.gradle.kts`、`scripts/release.ps1`、`settings.gradle.kts`、`.gitignore`、`app/src/main/java/com/jianji/app/` 包结构与 `jianji-sync/` 子项目逐条核对。
