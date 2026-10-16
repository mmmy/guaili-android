# Guaili Android

行情与乖离分析、XBot 信号设置、TradingView 警报管理的 Android 客户端。

## 性能验证

正式 release 包启用 R8 与资源收缩。`benchmark` 包使用独立的 `com.gouge.guaili.perf` 应用 ID 和本地模拟行情，不读取或修改正式安装的账户、警报和小组件；测试入口只存在于 benchmark 构建。

本轮可安装包：[GuailiAndroid-performance.apk](dist/performance-optimized/GuailiAndroid-performance.apk)，版本 0.5.1 / 8，沿用项目既有开发证书签名的 Release。已校验与历史交付包的证书一致，支持覆盖同证书版本并保留数据；v2/v3 签名与 APK 对齐验证通过，校验和见同目录 `build-manifest.json`。

重新生成这个安装包，见下方[构建和签名命令](#构建可安装的-release)。

```powershell
.\gradlew.bat :app:testDebugUnitTest :feature-xbot:testDebugUnitTest :app:assembleRelease :app:lintDebug
# 在已连接的 API 33+ 测试设备生成启动、矩阵与 K 线 Baseline/Startup Profiles。
.\scripts\measure-performance.ps1 -Serial <设备序列号> -Mode Profiles
# 重新构建优化包后测量冷启动及 K 线平移，输出 JSON 和 Perfetto trace。
.\scripts\measure-performance.ps1 -Serial <设备序列号> -Mode Benchmark
```

只有模拟器功能验证时才添加 `-AllowEmulator`；其耗时不能作为真机性能结论。结果保存在 `build/performance/`，生成的规则保存到 `app/src/main/baselineProfiles/`，正常 release 构建不需要连接设备。每项基准会清理独立的 `.perf` 测试数据，修改核心用户路径后重新生成规则，勿用 debug 包评价发布性能。

Benchmark 模式先在独立 instrumentation 进程检查优化 APK 中 Room 数据库与 Glance 刷新回调的反射构造，再以新进程测量。保留 `app/proguard-rules.pro` 中两类无参构造规则，更新相关依赖后继续运行该检查。

运行效率约定：映射、指标及快照序列化在计算线程执行；并发相同观测共享请求且保留每位调用者的取消和持久化要求；快照按实际存储内容复用解析结果。前台已有数据直接更新对应小组件，局部修改不发布旧的完整列表。V2 展示内容与时间边界分离，K 线静态行情图层与交互预览分离；这些优化不降低行情刷新频率，也不改变失效、候选、声音及导航语义。

2026-10-16 第二轮优化包括矩阵可见列、按来源更新组件、TV 警报后台合并同步、V2/回调后台投影、共享 K 线基础计算和启动缓存。基准新增 40 品种 × 28 周期的 V2/回调数据，详情见 [Android 性能优化第二轮与验证](docs/Android性能优化第二轮与验证.md)。

价格轴缩放修复（2026-10-07）：价格轴下拖压缩、上拖展开，主图上下拖动平移价格，双击价格轴或点击「自动」恢复。行情刷新保留手动范围，选中警报的绘制和触摸命中使用相同坐标。见 [修复与验证](docs/手机价格轴缩放修复与验证.md)；[修复版 APK](dist/price-axis-fix/GuailiAndroid-price-axis-fix.apk) 为同开发证书签名的 Release 构建。

## 回调观察桌面小组件（2026-10-16）

在现有「乖离速览」小组件配置中选择「回调观察」，最多关注 10 个品种。多头在上、空头在下，候选目标 32sp 加粗、等待目标 28sp，中性小级别紧邻显示；候选筛选按小组件独立保存且默认关闭，规则与应用共用。顶栏始终一行，无底栏；点左上角查看采样、状态、参数与更多入口，宽版显示编辑和价格警报。

点目标查看固定采样依据，点小级别进入原品种的小周期 K 线，点警报进入原 ID 与周期。过期采样保留为明确标记的历史观察，后台约 15 分钟更新且受系统调度限制；支持手动刷新并复用已有前台同步，不增加后台声音或独立轮询。[实现、原生截图与验证范围](docs/Android回调观察小组件实现与验证.md)。本次调试包为 `app/build/outputs/apk/debug/app-debug.apk`。

## 回调行精简（0.5.1）

2026-10-14：移除主行上方重复的“多头／空头”标签，以固定上下顺序和级别颜色表达方向；行高收紧，保留原级别字号及 48dp 点击区，无障碍朗读继续说明方向。

[安装包 GuailiAndroid-v0.5.1.apk](dist/pullback-compact/GuailiAndroid-v0.5.1.apk) 沿用同一开发证书，可覆盖安装并保留配置及警报记录。

## 回调价格警报同步与延期（0.5.0）

2026-10-14：回调主信号右侧显示价格警报意图、绑定周期、价格与状态，+N 展开并指定主警报；单击定位原 K 线。编辑器提供做多、做空、平多、平空，同品种/周期/意图最多一条启用警报，支持明确编辑或原子替换。范围结束可预览后延期，全局默认 20 根（1–1000），保留原时长、价格与历史，10 秒内可撤销且防止覆盖并发修改。

[安装包 GuailiAndroid-v0.5.0.apk](dist/pullback-price-alerts/GuailiAndroid-v0.5.0.apk) 沿用同一开发证书，覆盖安装保留配置；[实现与验证](docs/Android回调价格警报同步与延期.md)。

## 回调入场小级别（0.4.2）

2026-10-13 同步桌面逻辑：在回调级别旁显示推荐入场小级别，默认除以 20，从全局固定 28 周期中取最接近的更小周期；参数可修改并保存。只显示数值，单击进入同品种的小周期 K 线，原回调级别仍查看依据，返回保持观察位置。

[安装包 GuailiAndroid-v0.4.2.apk](dist/pullback-entry/GuailiAndroid-v0.4.2.apk) 沿用现有开发证书签名，可覆盖同证书版本并保留数据；[规则与验证](docs/Android回调观察实现与验证.md)。

## 回调观察单行工具栏（0.4.1）

2026-10-12 按用户确认原型将回调工具栏收为一行：级数打开参数，候选与声音直接切换，开启时高亮，右侧刷新。移除重复标题与常驻规则说明，窄屏及大字体也不恢复双行；保留原有参数、行情依据、声音去重与偏好。

[安装包 GuailiAndroid-v0.4.1.apk](dist/pullback-one-row/GuailiAndroid-v0.4.1.apk) 为同开发证书签名的 Release 构建，可覆盖安装保留数据。[本次截图与验证](docs/Android回调观察单行工具栏与验证.md)。

## 回调观察（0.4.0）

在「行情 → 回调观察」按品种查看多头／空头目标级别，候选以绿色／红色级别突出。可独立保存候选筛选、声音开关及连续级数、强弱阈值、强势背景级数与高级参数；点击目标查看固定的行情依据，关闭或返回取消选择，进入原品种／周期 K 线后返回保留观察位置。

复用完整服务器信号快照、缓存及前台刷新，区分真实触及均线与整数截断零。声音默认关闭，仅在前台观察页新增候选时按批次提醒，持续存在不重响。原生手机、窄屏／大字体与宽屏截图和验证范围见 [Android 回调观察实现与验证](docs/Android回调观察实现与验证.md)。

安装包为 [GuailiAndroid-v0.4.0.apk](dist/pullback/GuailiAndroid-v0.4.0.apk)，沿用现有开发证书签名的 Release 构建，可覆盖同证书 `com.gouge.guaili`；尚非生产证书签名。模拟器已验证提醒事件和去重，真机听感尚未实测。

## 手机价格线警报（0.3.0）

在K线页选择「水平线」或「趋势线」，依次点选起点和终点，再设置「条件 / 消息 / 通知」。点选已保存线段后可拖动整条线或端点，并通过底部工具栏编辑、暂停、重新启用、查看触发记录、撤销最近一次已确认移动或删除。拖动过程仅本地预览，松手提交；保存结果待确认与版本冲突会显示核对、重试或放弃入口。

价格线绑定创建时的品种与周期，支持精确坐标、延伸方式、到期日历、文字、颜色、线宽和默认预设。原有固定价位警报继续在「警报 → 旧版固定价位」管理，沿用原接口。触发检测及 Webhook 投递由既有服务器负责。

实现说明、手机截图和验证范围见 [手机价格线警报实现与验证](docs/手机价格线警报实现与验证.md)。安装包为 [GuailiAndroid-v0.3.0.apk](dist/v0.3.0/GuailiAndroid-v0.3.0.apk)，使用现有开发调试证书签名，保留 `com.gouge.guaili`；这是 Release 构建，尚非生产证书签名。

## 第一版整合（0.2.0）

- 「行情」提供「表格 / v2 信号」切换，支持品种与类型筛选、动态信号详情、数据质量状态和K线跳转；主程序与v2小组件共享服务器完整快照。
- 「信号」管理 XBot 信号级别、有效期及信号图标。
- 「TV警报」查看 TradingView 警报配置、业务有效期，支持添加、删除和确认再设。
- 「设置」分别配置行情服务、XBot 服务及账户。未登录 XBot 也能查看行情。
- 手机使用底部导航，宽屏使用侧栏；切换页面保留页面状态，行情轮询在离开行情页后暂停。
- 乖离速览、XBot 信号设置、TV 警报到期组件均在 Guaili 下添加，组件点击打开对应功能。

竞品依据与第一版取舍见 [整合调研](docs/integration-v1-research.md)。接口仍由各自后端实现，本次不迁移后端或新增订单执行。

## 服务与账户

行情服务默认模拟器地址为 `http://10.0.2.2:3005/`，生产服务地址按项目的 `AGENTS.md` 配置。使用「设置 → 行情与指标设置」修改。

新版乖离矩阵消费后端的可空指标与 `availability/reasonCode/reason/historyCount`：有效 0 保留，空值在应用与小组件显示 `—`，详情展示后端原因，快照缓存保留这些字段。矩阵指标预热与信号额外历史门槛分别由后端判定；客户端不按历史根数重算可用性。

XBot 服务使用独立地址和登录凭据，通过「设置 → XBot 账户与连接」登录。XBot 本地示例端口为 `3002`；模拟器访问宿主机使用 `10.0.2.2`，真机使用可访问的服务器或局域网地址。XBot 凭据仅用于该服务，切换账户或退出登录不会修改行情服务设置。

Guaili 沿用 `com.gouge.guaili`，可覆盖安装并保留原有行情设置及乖离组件。原独立 XBot App 使用另一个 applicationId；合并版需重新登录并重新添加其组件，原 App 保持可用。

## 工程结构

- `app`：Guaili 宿主、统一导航、行情、K线、价格警报和 Glance 组件。
- `feature-xbot`：独立 Android Library，保留 XBot 数据/领域/UI、RemoteViews 组件及业务测试。资源使用 `xbot_` 前缀，库通过宿主 launcher 跳转，不依赖宿主 Activity 类。

## 构建与验证

要求 JDK 17 或以上、Android SDK 36，最低设备 Android 8.0（API 26）。

```powershell
.\gradlew.bat :app:testDebugUnitTest :feature-xbot:testDebugUnitTest :app:lintDebug :feature-xbot:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。

### 构建可安装的 Release

推荐使用一键脚本（可从任意工作目录调用，默认输出到 `dist/release/GuailiAndroid-release.apk`）：

```powershell
.\scripts\build-release.ps1
```

脚本自动读取本机 SDK／JDK，优先选择最新已安装稳定版 Build Tools，依次完成 Release 构建、16KB 对齐、签名、签名与对齐校验，并生成 `.apk.sha256`。默认复用原有开发密钥，不会创建或替换密钥；只有校验成功才替换输出 APK，不自动安装。需要显式选择工具时可传 `-JavaHome`、`-SdkPath`、`-BuildToolsVersion`，自定义输出使用 `-OutputApk`。

使用自己的正式密钥时，必须同时指定路径与别名；未设置密码环境变量时，由 `apksigner` 交互询问：

```powershell
.\scripts\build-release.ps1 -KeystorePath 'D:\keys\guaili.jks' -KeyAlias guaili
```

无人值守构建可通过外部环境注入 `GUAILI_STORE_PASSWORD` 和 `GUAILI_KEY_PASSWORD`；脚本只向工具传递环境变量名称，不记录密码。更换签名证书将影响覆盖安装兼容性，沿用原证书时需保留原密钥。脚本入口见 [build-release.ps1](scripts/build-release.ps1)。下面保留手动步骤，便于排查问题。

本次采用「Gradle 构建 Release → apksigner 签名 → 验证签名和对齐」。当前 `release` 没有配置 Gradle 自动签名，单独运行 `assembleRelease` 会生成未签名 APK，需完成签名后安装。R8、资源收缩和已有的 `app/src/main/baselineProfiles/` 规则会在 Release 构建时生效，普通打包无需连接设备或重新采集 Profile。

以下是本机实际使用的 PowerShell 命令；换机器时调整项目、JDK 和 SDK Build Tools 路径。要覆盖本机历史开发版本，签名需继续使用原有的 `%USERPROFILE%\.android\debug.keystore`（别名 `androiddebugkey`，本机既有开发密钥的密码为 `android`）。换机器需复制原密钥文件，新生成的同名密钥无法覆盖旧版。

```powershell
Set-Location 'F:\test\guili-andriod'
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
$buildTools = 'D:\Andriod\AndriodStudio\build-tools\36.1.0'
$releaseKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$unsignedApk = 'app\build\outputs\apk\release\app-release-unsigned.apk'
$signedApk = 'dist\performance-optimized\GuailiAndroid-performance.apk'

if (-not (Test-Path -LiteralPath $releaseKeystore)) {
    throw '缺少原有开发签名密钥，请先恢复 debug.keystore'
}

# 1. 构建启用 R8、资源收缩及 Profile 的 Release。
.\gradlew.bat :app:assembleRelease --console=plain --max-workers=2
if ($LASTEXITCODE -ne 0) { throw 'Release 构建失败' }

# 2. 检查 Gradle 输出的 APK 对齐，再用原证书签名。
New-Item -ItemType Directory -Force (Split-Path -Parent $signedApk) | Out-Null
& "$buildTools\zipalign.exe" -c -P 16 4 $unsignedApk
if ($LASTEXITCODE -ne 0) { throw '未签名 APK 对齐检查失败' }

& "$buildTools\apksigner.bat" sign --ks $releaseKeystore `
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android `
    --v4-signing-enabled false --out $signedApk $unsignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名失败' }

# 3. 验证最终安装包。输出中 v2、v3 签名应均为 true。
& "$buildTools\apksigner.bat" verify --verbose --print-certs $signedApk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名验证失败' }
& "$buildTools\zipalign.exe" -c -P 16 4 $signedApk
if ($LASTEXITCODE -ne 0) { throw '签名 APK 对齐检查失败' }
Get-FileHash -Algorithm SHA256 -LiteralPath $signedApk
```

最终可安装文件：`dist/performance-optimized/GuailiAndroid-performance.apk`。本机历史交付证书 SHA-256 为 `6d045593162ec0aa0aff731c63e95db860e88cf68d8eed95a197d45c9890d653`，可与 `--print-certs` 输出的 `Signer #1 certificate SHA-256 digest` 核对。

需要通过 USB 覆盖安装时，在同一 PowerShell 窗口执行以下命令，将 `设备序列号` 替换为 `adb devices` 显示的值；`-r` 保留应用数据。

```powershell
$adbTool = 'D:\Andriod\AndriodStudio\platform-tools\adb.exe'
& $adbTool devices
& $adbTool -s '设备序列号' install -r $signedApk
```

### 设备集成测试

宿主整合的 instrumentation 测试位于 `app/src/androidTest/java/com/gouge/guaili/integration`；原有 XBot 警报页面和组件测试移入 `app/src/androidTest/java/com/gouge/xbot`，确保点击路径使用真实宿主。账户与警报数据使用设备内 MockWebServer，不向真实服务提交业务操作；测试临时修改的 XBot 本机配置在结束时恢复。运行前可执行 `python scripts/backup-widget-device.py --serial emulator-5554` 保存现有 Guaili 配置备份。

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e package com.gouge.guaili.integration com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
```

仅覆盖安装，不清除或卸载已有用户应用。后端每几秒采样和 Android 小组件后台刷新是不同调度机制，桌面更新仍受系统后台限制。
