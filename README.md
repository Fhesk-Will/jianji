# 简记 · jianji

一个本地记账的 Android 应用：安装即用，不需要注册或登录，数据只存在你自己的手机里。

## 功能

- [x] 记账 —— 每一笔花销和收入，都记下来
- [x] 统计 —— 清楚知道钱去了哪里
- [x] 分类管理 —— 按自己的习惯理账
- [x] 应用锁 —— 这本账只有你能看
- [x] 导入导出 —— 账目带得走，也带得回
- [ ] 多端同步 —— 换设备，还是同一本账
- [ ] AI 接口 —— 让 AI 帮你读账、算账

## 使用与安装

### 普通使用

这是 Android 应用（Android 8.0 及以上），下载安装包直接安装即可，不需要准备任何运行环境。

安装包发布后会出现在 [Releases](https://github.com/Fhesk-Will/jianji/releases/latest)，点进去下载最新的 APK。首次安装时若系统提示，需要在设置里允许「安装未知来源应用」。

安装完成后打开就能记账，无需注册。所有数据只保存在本机，不上传任何服务器；卸载应用会一并清除数据，建议先在「设置」里把账目导出成表格留一份。

桌面小部件：不同品牌手机入口略有差异，一般是长按桌面空白处（或长按应用图标）→ 小部件/插件，找到「简记」拖到桌面。

### 自己构建安装包

需要 JDK 17 与 Android SDK（安装 Android Studio 即自带）。在项目根目录执行：

```bash
git clone https://github.com/Fhesk-Will/jianji.git
cd jianji
```

release 包必须签名。没有现成密钥库的话先生成一个（一次即可，之后务必妥善保管）：

```bash
keytool -genkeypair -v -keystore jianji-release.jks -alias jianji \
  -keyalg RSA -keysize 2048 -validity 10000
```

在项目根目录创建 `keystore.properties`，填入上一步的密钥库与口令（字段名固定，值填自己的）：

```properties
storeFile=jianji-release.jks
storePassword=你的密钥库口令
keyAlias=你的别名
keyPassword=你的密钥口令
```

然后把密钥库文件放在同目录（文件名与 `storeFile` 一致），构建：

```bash
gradlew.bat assembleRelease      # Windows
./gradlew assembleRelease        # macOS / Linux
```

产物在 `app/build/outputs/apk/release/jianji-v<版本>-<versionCode>.apk`，经 R8 裁剪后约 2 MB。版本号取自 `gradle.properties` 的 `releaseVersionDate` 与 `releaseVersionSeq`（日期 + 当日序号），同日再发一版只需递增序号。（作者本机另有 `scripts/release.ps1` 一键出包，不随仓库分发。）

发布包请用脚本生成：直接跑 `assembleRelease` 只更新 `app/build/outputs/` 里的文件，不会同步根目录的 APK 与 `SHA256SUMS.txt`。

只想调试而不打包的话，用 Android Studio 打开本目录，等同步完成后点 Run 即可装到设备或模拟器上。

`keystore.properties`、`*.jks` 与 `local.properties` 都是本机文件，已被 `.gitignore` 排除，不会进入仓库。

## 许可证

[MIT](LICENSE)
