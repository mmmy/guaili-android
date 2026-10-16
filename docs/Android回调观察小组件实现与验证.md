# Android 回调观察小组件实现与验证

## 2026-10-16 桌面无法显示修复

用户模拟器桌面出现 `Can't show content`。日志确认 `LazyColumn` 的品种哈希 ID 小于 Glance 的保留边界 `-4611686018427387904`，导致整个组件组合失败；真实配置中的 CLUSDT、SOXLUSDT 可触发。稳定哈希现限制为非负 63 位，仍按品种保持身份，不依赖排序或候选筛选，不修改配置和刷新策略。

回归覆盖 10,010 个品种标识的合法范围、样本唯一性和顺序稳定性，并新增 10 品种的生产 `PullbackObservationWidgetContent`／`LazyColumn` 窄宽组合测试。原来仅三个品种的宿主测试未覆盖保留区哈希，普通 Column 预览也不能验证惰性列表 ID。此次构建、lint、359 项单元测试与 5 项原生测试全部通过；在用户的 emulator-5556 保留原组件 ID 36 和设置，覆盖安装后确认桌面恢复，当前进程无 Glance／RemoteViews 错误。桌面证据：[恢复截图](../build/pullback-widget-id-fix/widget-restored.png)；检查日志：[构建与单元测试](../build/pullback-widget-id-fix-checks.log)、[5 项原生测试](../build/pullback-widget-id-fix-instrumentation.txt)。最新验证结果见本节链接日志，后文保留首轮实现过程记录。

日期：2026-10-16。基于现有 Kotlin、Glance／RemoteViews 与 Material 3 实现，是既有乖离组件的新增模式。产品与视觉约定见 [PRODUCT.md](../PRODUCT.md)、[DESIGN.md](../DESIGN.md) 和 [局部规范](../.impeccable/surfaces/pullback-widget.md)。

## 使用与行为

在「乖离速览」组件配置中选择「回调观察」，关注最多 10 个品种。每实例候选筛选独立保存、默认关闭，规则与手机回调页共用。每品种多头在上、空头在下，候选 32sp 加粗、等待 28sp 中等字重，小级别 16sp；中性背景和方向色共同区分候选与等待。顶栏始终一行 48dp，没有底栏；窄版保留菜单、候选和刷新，至少 250dp 增加编辑，320dp 普通字体额外显示计数。

菜单包含采样、计数、状态、参数、编辑、刷新及应用入口。点击目标打开固定采样依据；点击推荐小级别打开原品种的小周期 K 线；宽版点击警报打开原绑定周期并定位原 ID。三类入口各自独立。关闭、遮罩或返回取消依据，刷新不会重开；来源地址变化时阻止用旧来源目标进入新来源 K 线。

宽版警报摘要可同行或按字体和实测宽度下移，点击区至少 48dp，并按摘要自身的顶段、价格行与内距测量增高。顶段覆盖 14sp 绑定周期、12sp 时间标签加 2dp 内距及可能两行的 11sp 状态，再计入 12sp 价格行与 8dp 余量，避免大字体价格行被裁切。有效时间为无填充圆角边框，天蓝、小时紫、分钟金；已触发、范围结束和待更新保留真实文字。组件不提供新后台声音，完整警报管理与延期沿用应用。

## 数据与性能

`PullbackWidgetProjector` 只在快照或规则变化时重建，使用快照原始服务器时钟及校准接收时间判定采样时候选。到达时已经无效、缺时钟或非完整快照不可生成候选；有效采样日后过期保留历史观察并标记“快照旧”，不延长实时 TTL。冻结依据包含原规则、目标与背景证据、均线、采样时间、入场周期和来源，Activity 中不随刷新替换。

`GuailiWidgetWorker` 复用既有约 15 分钟 WorkManager；实际更新时刻受系统调度与网络限制。V2 和回调的同源手动刷新共用一次信号读取，矩阵与决策提醒保留各自来源。价格警报复用前台已有的 10 秒读取并合并相同在途请求。每个服务地址隔离缓存，确认编辑后提高版本，晚到旧全量读取不能覆盖新结果。

相同警报内容不重复写持久缓存；只有持久化成功后才能记录成功读取的新鲜度，保存失败使其失效并反馈失败。仅匹配模式／来源的实例订阅和更新；候选筛选直接切换只重绘该实例。分组在真实过期边界重建，固定周期测量按尺寸与字体缓存；无新增行情轮询、后台候选声音或倒计时请求。

## 实现入口

| 文件 | 职责 |
| --- | --- |
| [PullbackWidget.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidget.kt) | 模式展示、尺寸测量、单行顶栏、目标／小级别／警报独立动作 |
| [PullbackWidgetPresentation.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetPresentation.kt) | 原始采样投影、历史状态、冻结证据与警报分组边界 |
| [PullbackWidgetActivity.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetActivity.kt) | 菜单、共用参数面板、固定依据与 K 线 Intent |
| [PullbackWidgetAlerts.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetAlerts.kt) | 按来源警报缓存、合并读取、确认版本、成功持久化后新鲜度 |
| [WidgetConfigStore.kt](../app/src/main/java/com/gouge/guaili/widget/WidgetConfigStore.kt) | 新模式、10 品种上限、实例候选筛选及并发编辑合并 |
| [GuailiWidgetWorker.kt](../app/src/main/java/com/gouge/guaili/widget/GuailiWidgetWorker.kt) | 复用周期／手动刷新、同源请求与实例反馈 |

## 原生验证与截图

最终修正后的组合检查已通过：app 的 Debug 与 AndroidTest APK 构建、358 项单元测试（62 个套件，零失败、错误和跳过）及 lint，组合构建耗时 2 分 24 秒；四个设备集成测试通过，耗时 33.884 秒。警报摘要按自身内容测高，原生断言确认点击区至少 48dp，价格布局完整位于 TextView 及每级祖先容器内；警报断言覆盖 250／320dp、字体 1.0／1.5 与浅／深主题。

最终复核确认此前列出的两项修正均已解决：警报持久化成功后才建立新鲜度，以及独立警报入口按自身多行内容测高。15 张要求的原生截图均已重新打开核对，结论为 `ship`；该结论仅覆盖这两项已评分修正，不能解释为整张表面的全面验收，详见 [最终复核记录](../.impeccable/review/pullback-widget/final-verdict.md)。

新增单元测试覆盖实例筛选隔离、编辑器不覆盖直接切换、同源模式及 10 品种上限、原校准接收判定、历史保留、到达时过期／不完整／时钟异常、真实零与截断零，以及冻结证据随新快照仍保持不变。

`PullbackWidgetIntegrationTest` 的四个原生测试覆盖：12 组资源上下文布局；固定依据和原周期／警报 ID 导航；真实 AppWidgetHost 中的尺寸、筛选、刷新和依据；确认后拒绝旧全量读取及按服务隔离。布局检查逐一确认完整周期不换行、不省略，顶栏仅一组候选／刷新且没有常驻采样底栏，宽版警报保持独立至少 48dp 触摸区，价格行的布局高度不超出 TextView 且未被任何祖先容器裁切。

| 证据 | 范围 |
| --- | --- |
| [构建与检查日志](../build/pullback-widget-checks.log) | app 单元测试、lint、Debug 及 AndroidTest 构建；358 项测试来自 app 的 62 个测试套件，以日志最终结果为准 |
| [原生测试日志](../build/pullback-widget-instrumentation.txt) | 四个设备集成测试，包含真实宿主选项 |
| [最终复核记录](../.impeccable/review/pullback-widget/final-verdict.md) | 两项已列修正均解决，15 张原生截图重新核对；`ship` 仅覆盖已评分修正 |
| [浅色原生布局](../.impeccable/review/pullback-widget/review-contact-light.png) | 180／250／320dp、字体 1.0／1.5 的资源上下文 |
| [深色原生布局](../.impeccable/review/pullback-widget/review-contact-dark.png) | 相同六组尺寸与字体组合 |
| [真实窄宿主](../.impeccable/review/pullback-widget/pullback-widget-host-narrow.png) | AppWidgetHost 的真实 RemoteViews 与点击路径 |
| [真实宽宿主](../.impeccable/review/pullback-widget/pullback-widget-host-wide.png) | 调整尺寸后的真实宿主 |
| [固定依据](../.impeccable/review/pullback-widget/pullback-widget-evidence-native.png) | 原始目标及原周期入口 |

![180dp、150% 字体、浅色原生布局](../.impeccable/review/pullback-widget/pullback-widget-180-1.5-light.png)

![320dp、普通字体、深色原生布局](../.impeccable/review/pullback-widget/pullback-widget-320-1.0-dark.png)

12 组图片来自 Android 原生 Glance 资源上下文渲染；宿主与依据另有真实原生截图。此范围不代表已在整机层面切换系统日夜模式，也不代表不同厂商桌面、真机后台时效或真实服务器部署均已验证。业务测试使用本地模拟服务，不向真实服务提交警报修改。

## 构建与复验

调试安装包：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)。此路径为 Debug 输出，不作为新版本 Release 发布或生产签名证明。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain --max-workers=2
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell appwidget grantbind --package com.gouge.guaili --user current
try {
    adb -s emulator-5554 shell am instrument -w -e class com.gouge.guaili.widget.PullbackWidgetIntegrationTest -e pullback_widget_host true com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
} finally {
    adb -s emulator-5554 shell appwidget revokebind --package com.gouge.guaili --user current
}
```

真实宿主测试通过 `pullback_widget_host=true` 显式启用，并临时授予测试设备中的 Guaili 绑定组件权限，结束后撤销；该授权仅用于测试模拟器。只覆盖安装，不清除现有用户数据。主测试代码恢复临时服务设置与快照，解除并删除测试宿主 ID。后台调度仍遵守 Android 限制，不能把测试中的立即更新当成生产固定时限。
