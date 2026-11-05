# 简记 · jianji

Android 本地记录 App —— 一个极简的记录工具，无需注册即可上手。

- 现状：当前版本不需要账号，数据保存在本机，适合一台设备上的单个使用者。
- 规划：服务端同步上线后，将通过账号在多台设备之间同步数据。

## 技术栈

- Kotlin 2.0 + Jetpack Compose（Material 3）
- Room（本地 SQLite，唯一数据源）
- Navigation Compose + ViewModel + StateFlow
- DataStore（设置项）
- kotlinx-serialization（JSON 备份）
- 图表：Canvas 自绘（环形图 / 柱状图），无第三方图表库

## 架构关键决策

1. **本地优先**：所有数据存手机端 Room，应用完全离线可用。
2. **同步口子（成本≈0）**：
   - 记录 / 分类 id 均为客户端 UUID（非自增）
   - 每次修改刷新 `updatedAtEpochMs`
   - 删除一律软删除（`isDeleted`，墓碑保留）
   - 备份 JSON 含完整状态，恢复按 id 幂等合并（REPLACE）
   → 多端同步（规划中，后续版本提供）将接入服务端，直接在 Repository 层挂同步即可，数据模型不用动。
3. **金额单位为「分」（Long）**，全程整数运算，展示时才格式化。
4. **备份走 SAF**：用户自选目录（推荐网盘同步文件夹，如坚果云），
   每日自动写一份 JSON，保留最近 30 份 —— 目录被网盘同步即形成一份异地副本，不属于服务端同步功能。
5. **单设备定位的简化**：当前版本无需鉴权、无并发与权限系统；应用锁已实现（指纹 / PIN）。

## 目录结构

```
app/src/main/java/com/jianji/app/
├── JianJiApp.kt            # Application + AppContainer（手工 DI）+ 每日自动备份
├── MainActivity.kt
├── data/
│   ├── model/RecordType.kt        # 支出 / 收入（报销=收入分类）
│   ├── entity/                    # Room 实体（Record / Category）
│   ├── dao/                       # 查询与写入（含统计聚合）
│   ├── db/AppDatabase.kt
│   ├── DefaultCategories.kt       # 预置分类（含固定 id）
│   ├── repository/                # UI 唯一数据入口
│   ├── backup/                    # JSON 备份 + CSV 导出
│   └── prefs/SettingsStore.kt     # DataStore
├── ui/
│   ├── theme/                     # 色板与主题（与 HTML 原型同源）
│   ├── nav/AppRoot.kt             # 底部三 Tab + 路由
│   ├── components/                # MonthBar / SegTabs / EmojiBadge
│   ├── home/                      # 明细页 + 筛选抽屉
│   ├── record/                    # 记一笔（自定义键盘，新增/编辑共用）
│   ├── stats/                     # 统计（Canvas 环形图 + 柱状图 + 排行）
│   ├── categories/                # 分类管理
│   └── settings/                  # 设置（备份/恢复/导出/应用锁）
└── util/                      # Money（分↔字符串）/ Dates
```

## 构建

1. Android Studio（Ladybug+）→ File → Open → 选本目录，等 Gradle Sync。
   - JDK 17；本项目未附带 gradle wrapper，AS 会自动处理；
     命令行党可先 `gradle wrapper --gradle-version 8.7` 生成 wrapper。
2. Run ▶ 到设备 / 模拟器（minSdk 26）。
3. 发布版（R8 裁剪，约 2MB）：`./gradlew assembleRelease`
   - 需 `jianji-release.jks` + `keystore.properties`（随源码交付，密码 `jianji2026app`）
   - 两个文件已列入 .gitignore，自建仓库时注意保管
   - 无 keystore.properties 时自动回退 debug 签名，不影响他人构建

## 变更记录

- v0.1.1（2026-11-04）：**记账手感优化**——去掉 Navigation Compose 默认 700ms 淡入淡出：
  底部 Tab 零动画瞬切；记一笔页底部快速上滑 200ms / 返回 160ms；二级页右滑 220ms。
  出 release 瘦身版（2.1MB），签名密钥随源码交付。
- v0.1.0：首版骨架闭环（算式求值、备份、小部件、应用锁、深色主题）。

## 已实现（骨架闭环）

- 记一笔：**金额支持四则算式**（如 12.5+3.2*2，乘除优先、BigDecimal 精确求值、实时显示结果）、分类宫格、日期选择、备注、再记 / 编辑 / 软删除
- 明细：月份切换、**年月选择器（12 宫格直选）**、日分组流水、月汇总、筛选抽屉（类型 / 分类多选 / 关键词）
- 统计：环形图、图例、每日柱状图、分类排行（支出 / 收入两套口径）
- 分类管理：增改删（删除归入「其他」）、emoji 图标
- 设置：备份目录（SAF）、每日自动备份 + 保留 30 份、恢复（幂等合并）、**CSV 导出可选范围（全部/今年/近三月/本月）**
- **应用锁**：BiometricPrompt 指纹/PIN；冷启动与切后台回前台均需验证
- **桌面小部件（4×1）**：本月收支一览，点按直达「记一笔」（RemoteViews + 数据流直推）
- **深色主题**：双套色板（AppColors + CompositionLocal 同名 getter），跟随系统
- 预置分类：支出 8 + 收入 7（报销在收入分类里）

## 三期清单（按价值排序）

1. 统计图交互：环形图扇区联动图例、柱状图点柱跳当日明细
2. 分类宫格翻页 / 拖动排序
3. 小部件升级 Glance、多尺寸
4. 多端同步（规划中，后续版本提供）：服务端同步接入 Repository 层，UUID + updatedAt + 软删除已就绪
