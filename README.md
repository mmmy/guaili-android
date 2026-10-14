# Guaili Android

行情与乖离分析、XBot 信号设置、TradingView 警报管理的 Android 客户端。

价格轴缩放修复（2026-10-07）：价格轴下拖压缩、上拖展开，主图上下拖动平移价格，双击价格轴或点击「自动」恢复。行情刷新保留手动范围，选中警报的绘制和触摸命中使用相同坐标。见 [修复与验证](docs/手机价格轴缩放修复与验证.md)；[修复版 APK](dist/price-axis-fix/GuailiAndroid-price-axis-fix.apk) 为同开发证书签名的 Release 构建。

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

宿主整合的 instrumentation 测试位于 `app/src/androidTest/java/com/gouge/guaili/integration`；原有 XBot 警报页面和组件测试移入 `app/src/androidTest/java/com/gouge/xbot`，确保点击路径使用真实宿主。账户与警报数据使用设备内 MockWebServer，不向真实服务提交业务操作；测试临时修改的 XBot 本机配置在结束时恢复。运行前可执行 `python scripts/backup-widget-device.py --serial emulator-5554` 保存现有 Guaili 配置备份。

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e package com.gouge.guaili.integration com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
```

仅覆盖安装，不清除或卸载已有用户应用。后端每几秒采样和 Android 小组件后台刷新是不同调度机制，桌面更新仍受系统后台限制。
