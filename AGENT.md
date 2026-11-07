# AGENT.md · 简记（jianji）

## 1. 项目定位

本地记账 Android 应用：数据只存在本机（Room + DataStore），无账号、无服务器、无云同步。
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
- 发布脚本（仅本机，已被 `.gitignore` 排除）：`powershell -NoProfile -File scripts/release.ps1`
  它依次做：校验版本 → `git ls-remote` 查远端同名 tag → `assembleRelease` → 复制 APK 与 `SHA256SUMS.txt` 到仓库上一级目录 → 打印后续 git/gh 命令，自身不打 tag、不推送。
- release 签名来自仓库根目录的 `keystore.properties`（字段：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），缺失时 `app/build.gradle.kts` 的 `taskGraph` 校验会直接报错，不会回退 debug 签名。
- 验收下限：`:app:assembleDebug` 与 `:app:assembleRelease` 都能通过，且 release 产物由正式密钥签名。

## 3. 版本号机制

- 唯一来源：`gradle.properties` 的 `releaseVersionDate`（`YYYY.MM.DD`，月日补零）与 `releaseVersionSeq`（1..99）。
- 派生规则：`versionName = 日期.序号`，`versionCode = 去掉点的日期 × 100 + 序号`
  （例：`2026.11.05` + `1` → `2026.11.05.1` / `2026110501`）。
- 改版本只改这两行；校验与派生都在 `app/build.gradle.kts` 顶部，非法值会让构建失败。
- APK 文件名由 `app/build.gradle.kts` 的 `androidComponents.onVariants` 决定，格式固定为 `jianji-v<versionName>-<versionCode>.apk`，同日多版不覆盖。
- 同日再发一版：日期不动、序号 +1；跨日：日期改为当天、序号回 1。
- 不要在代码里写版本号；界面显示用 `BuildConfig.VERSION_NAME`。

## 4. 代码组织

包根 `app/src/main/java/com/jianji/app/`，规则：按层分包，一层一个职责。

- 根目录：`JianJiApp.kt`（Application + 手写依赖容器 `AppContainer`，不引 DI 框架）、`MainActivity.kt`。
- `data/db` Room 数据库与迁移；`data/dao` 查询；`data/entity` 表实体；`data/repository` 仓库，UI 只经仓库读写；`data/prefs` DataStore 设置；`data/model` 枚举等模型；`data/csv` CSV/XLSX 导入导出。
- `ui/` 按页面分包：`home`、`record`、`stats`、`categories`、`settings`、`lock`；`ui/nav` 路由；`ui/components` 跨页组件（非页面私有）；`ui/theme` 主题。
- `util/` 无 Android 依赖的纯函数：`Money`（金额与算式）、`Dates`（日期）。
- `widget/` 桌面小部件。
- 新增页面放 `ui/<页面名>/`；新增数据表按 `entity → dao → repository` 顺序补；只有被两个以上页面使用的 UI 才放 `ui/components/`。
- 技术事实：Compose + Material3 做 UI，Room 存数据，DataStore 存设置；minSdk 26 可直接用 `java.time`，不需要脱糖。

## 5. 硬性禁止项

- 把 `keystore.properties`、`*.jks`、`local.properties` 加入 Git（`.gitignore` 已排除，不要用 `git add -f`）。
- 在代码里硬编码版本号。
- 未经确认引入新依赖，尤其是会显著增大包体的库（当前 release 约 2.1 MB，改完看 `app/build/outputs/apk/release/` 的实际大小）。
- 改动签名配置，或让 release 回退到 debug 签名。
- 把 README 写成开发文档；README 面向普通用户。
- 恢复已砍掉的备份功能（备份目录、自动备份、从备份恢复）；`data/backup/` 已是空目录，不要再加回来。

## 6. 改动纪律

- 先读相关文件再改，不凭印象写 API、命令或字段名。
- 一次只处理一个明确目标；不顺手重构无关代码、不改无关文案。
- 每次改动后至少跑一遍 `:app:assembleDebug`；动到签名、混淆、发布逻辑时再跑 `:app:assembleRelease`。
- 默认不 commit、不 push、不打 tag、不改写 Git 历史；只有用户明确要求才做。
- 不擅自扩大改动范围，不新增未被要求的功能。

## 7. 事实核验入口

不确定时去这些地方查，不要猜：

- 版本号字段、构建命令、签名要求：`gradle.properties`、`app/build.gradle.kts`、`scripts/release.ps1`（本机文件）。
- 依赖及版本：`gradle/libs.versions.toml` 与 `app/build.gradle.kts` 的 `dependencies`。
- 某功能是否存在：先在 `app/src/main/java/com/jianji/app/` 搜代码，再对照 README 功能清单；两者不一致以代码为准。
- 是否已砍：`.gitignore`、`git status`、包目录（如 `data/backup/` 为空即未实现）。
- 产物路径与包体大小：`app/build/outputs/apk/`。

## 8. 文档自维护

必须同步更新本文档的触发条件（任一命中就改）：

1. 增删功能，导致 README 功能清单变化；
2. 版本号机制、字段名或派生规则变化；
3. 构建命令、产物路径、发布脚本行为变化；
4. 依赖策略变化（新增或移除体积较大的库）；
5. 包结构或分层职责变化；
6. 出现"被砍功能又加回来"这类方向性变化。

更新方式：

- 只改受影响的小节，不重写全文。
- 新增条目必须能指到具体文件或命令；指不到具体落点的，写进第 7 节作为排查入口，不写进正文当规则。
- 保持全文不超过 120 行；超限时合并或删除旧条目，而不是继续追加。

定期校验：每次发版前，按第 7 节逐条核对一遍本文档的事实。

冲突处理：文档与代码不一致时以代码为准，并在同一次改动里修正文档。

最后核对：2026-11-06，按 `gradle.properties`、`app/build.gradle.kts`、`scripts/release.ps1`、`settings.gradle.kts`、`.gitignore` 与 `app/src/main/java/com/jianji/app/` 包结构逐条核对。
