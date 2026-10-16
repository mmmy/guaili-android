# Android 回调观察小组件实现与验证

## 2026-10-17 警报固定同行修正与验证

用户反馈真机仍然换行，并明确要求“不要换行”。本轮将宽版（Glance 响应尺寸档至少 250dp）警报固定在目标与推荐小级别右侧同一主行，删除 `pullbackWidgetAlertFits` 和上下布局分支，不再根据估算宽度或字体阈值将整条警报移到目标下方。目标与小级别继续按当前可见行及候选筛选共享实测列宽；警报在同一 `Row` 内通过 `defaultWeight()` 使用余下宽度。警报自身仍有两行：第一行意图、绑定周期及时间／状态，第二行精确价格，所有字段各自 `maxLines = 1`。候选 32sp、等待 28sp、小级别 16sp、独立至少 48dp 点击高度，以及冻结依据、原品种／周期／警报 ID 导航和刷新数据流保持。小于 250dp 的窄版继续不常驻警报；应用 `PullbackContent` 的布局约定不受此修正影响。

真机桌面的实际可用宽度可能大于 Glance 选中的响应尺寸档。例如 320dp 宿主扣除 16dp 桌面内距后在 304dp 实际宽度显示，仍可能选择 250dp 档；此前按该档估算空间不足即可触发下移。本轮原生用例将尺寸档与实际渲染宽度分开：250／320dp 两档均在 304dp 实际宽度，配合字体 1.0／1.3／1.5 与浅／深资源上下文，生成 12 张与用户截图同文案的截图。`2D / 2H`、`1H / 3m` 两组目标旁的 `30m / 5m` 待更新警报始终位于右侧，价格 `81056.5 / 82998.2` 与目标垂直范围重叠；所测价格及“待更新”没有省略，且均在各级祖先容器内。

另有实际 180／250／320dp × 字体 1.0／1.5 × 浅／深的 12 张原生布局，以及窄／宽 AppWidgetHost 两张截图，本轮共 26 张。目标和推荐周期保持完整；实际 250dp、150% 字体时辅助有效时间标签出现省略，精确测试价格仍可见。固定同行优先于辅助标签完整，不能把本轮结果表述为所有宽度、所有辅助状态均不省略，也不能重新引入下移。完整警报继续通过原入口查看。

组合检查 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 已通过，耗时 3 分 13 秒；362 项单元测试（63 个套件）零失败、错误和跳过，lint 为 0 错误、49 条警告。8 项原生集成测试通过，耗时 27.923 秒。[本轮独立复核](../.impeccable/review/pullback-fixed-row/finish-review.md) 逐张检查全部 26 张原始截图并对照本轮代码基线，结论为 `ship`，范围仅为固定同行修正及所列证据，不沿用历史验收结论。此次证据来自原生资源上下文和模拟器真实宿主，未连接用户实体手机；资源主题覆盖不等于已切换整机日夜模式，未部署生产服务器，也未证明任意厂商桌面或未见长文本均适配。

| 证据 | 范围 |
| --- | --- |
| [构建与检查日志](../build/pullback-fixed-row-checks.log) | Debug、AndroidTest、362 项单元测试及 lint |
| [原生测试日志](../.impeccable/review/pullback-fixed-row/instrumentation.txt) | 8 项原生测试，含响应尺寸档与实际宽度分离的同行检查 |
| [手机同文案浅色](../.impeccable/review/pullback-fixed-row/contact-phone-light.png)、[深色](../.impeccable/review/pullback-fixed-row/contact-phone-dark.png) | 250／320dp 档，实际 304dp，三档字体，共 12 张 |
| [通用布局浅色](../.impeccable/review/pullback-fixed-row/contact-matrix-light.png)、[深色](../.impeccable/review/pullback-fixed-row/contact-matrix-dark.png) | 实际 180／250／320dp、两档字体，共 12 张 |
| [窄宿主](../.impeccable/review/pullback-fixed-row/pullback-widget-host-narrow.png)、[宽宿主](../.impeccable/review/pullback-fixed-row/pullback-widget-host-wide.png) | 模拟器真实 AppWidgetHost |
| [Debug APK](../app/build/outputs/apk/debug/app-debug.apk) | 本轮调试构建，非生产发布证明 |

下文保留先前实现与验证历史。其中小组件“空间不足时下移”“状态可换行”及相应验收条件已由本节固定同行规则取代；应用回调页自身规则保持。

## 2026-10-17 采样与警报更新状态修正

正常桌面采样不再持续显示“快照旧”，正常价格警报也不再因行情采样进入历史状态或普通进程重启而统一显示“待更新”。此前后端证据约 15 秒的 TTL 与后台约 15 分钟调度共用了展示失效状态；警报又依赖行情历史状态和仅存于进程内的 30 秒成功读取标记，手动信号刷新还可能命中已完成结果的 1 秒复用窗口。

本轮将实时证据与传输状态分别表达：`PullbackWidgetProjector` 仍在原始校准接收时间验证完整快照、真实零、质量有效期和到达时是否陈旧，原候选分类与冻结依据不变；17 分钟（既有 15 分钟后台间隔加 2 分钟宽限）只用于判断是否应已收到下一次传输。正常顶栏显示“采样 HH:mm”，小于 320dp 或字体缩放超过 1.2 时仅显示“HH:mm”，完整采样时间、规则级数、计数及“采样观察、非实时”说明保留在菜单与无障碍描述。满 17 分钟未收到新快照显示“快照旧”，实际网络失败、未知／异常时钟、非完整或到达时已陈旧仍按原规则提示；不延长实时证据 TTL，也不把历史候选表述为当前有效入场信号。

价格警报使用按来源持久保存的成功完整读取凭据（墙钟、单调时钟、启动次数）及错误，独立于行情采样新鲜度。只有数据持久化成功且来源版本匹配，才能建立成功读取凭据；普通进程重启后恢复，设备重启、未知启动次数、单调时钟回退、凭据缺失、读取／保存失败或满 17 分钟未成功读取则显示“待更新”。已触发、范围结束、行情断开等 DTO 业务状态及其原优先级保持，后端实际返回的“待更新”仍可合法显示。失败仅在同来源成功读取后清除，旧版本的晚到完整读取不能覆盖确认后的编辑或删除。

相同警报内容复用已有 JSON 和解码列表，仅成功读取时间变化的持久化按 60 秒合并；成功读取的内容（含已持久化错误状态）变化则立即保存凭据。手动刷新绕过已完成信号结果的 1 秒复用窗口，实际读取行情和警报，相同在途读取继续共享。WorkManager 周期仍为约 15 分钟，前台警报仍沿用已有 10 秒读取。新增无网络的本地超期重绘：保留同次设备启动中最早的待执行边界，不因后续凭据换源而推迟，旧来源晚到不能将已有任务替换成更晚检查。任务执行时重新读取当前服务设置及其最新凭据，只在本地重评并安排下一真实边界，不发起 HTTP 请求；V2 的短 TTL 任务仅作用于 V2。没有新增行情轮询、后台声音或秒级计时。

最终回归的 362 项单元测试（零失败、错误及跳过）、8 项原生集成测试及 Debug／AndroidTest 构建、lint 已通过。新增原生宿主测试实际点击刷新，断言行情与警报两类 API 请求计数均增加并成功保存读取凭据；持久化重载覆盖内容不变时推进时间而不重编码列表、设备重启、读取失败及恢复、来源版本隔离。跨来源调度测试使用真实 WorkManager，断言不同来源后到的较晚凭据不改变已经排定的任务 ID。纯单元测试覆盖 17 分钟边界、未知／回退时钟及 60 秒写入合并。原有 180／250／320dp × 字体 1.0／1.5 × 浅／深共 12 组资源上下文布局与窄／宽真实宿主检查同步验证新顶栏。

在已有配置的 emulator-5556 上，使用 `10.0.2.2` 本地服务只读刷新后，顶栏显示采样时间，正常警报保留 `18H`；杀进程并确认新 PID 后重新读取，`18H` 仍保留，候选开关连续两次切换后恢复原配置。未连接用户实体手机，此证据不代表实体手机后台时效、不同厂商桌面或生产服务器部署已验证。资源上下文的浅／深覆盖也不等于实际切换整机日夜模式。[本轮独立复核](../.impeccable/review/pullback-refresh/finish-review.md) 逐张检查 16 张原生截图，结论为 `ship`，范围仅为此次刷新状态修正及所列模拟器证据，不沿用历史验收结论。

| 证据 | 范围 |
| --- | --- |
| [构建与检查日志](../build/pullback-refresh-checks.log) | Debug、AndroidTest、单元测试及 lint |
| [原生测试日志](../build/pullback-refresh-instrumentation.txt) | 八项原生集成测试，含真实宿主刷新、持久化重载及跨来源最早任务保留 |
| [刷新后桌面](../.impeccable/review/pullback-refresh/device-after-refresh.png) | emulator-5556 原配置，只读本地服务 |
| [进程重启后桌面](../.impeccable/review/pullback-refresh/device-after-process-restart.png) | 新进程恢复采样与警报读取凭据 |

下文保留 2026-10-16–17 的实现与验证历史。其中首版“跨实时 TTL 即快照旧”的展示规则已由本节的传输超期语义取代，实时候选判定、固定依据和原业务状态规则继续适用。

## 2026-10-17 警报同行空间修复与验证（历史，布局策略已取代）

用户截图中目标右侧仍有空间，警报却移到目标下方。布局此前按未显示的完整周期目录预留目标／小级别列宽，并在字体缩放超过 1.2 时统一下移警报。现按当前可见行及候选筛选共享实测列宽；每条警报使用 Android Paint 按当前字体测量 11sp 意图、14sp 绑定周期、11sp 状态或含 8dp 水平内距的 12sp 时间标签，以及 12sp 精确价格行，再加 4dp 安全余量，仅在剩余宽度确实不足时下移。列宽和同行判断随对应内容、尺寸及字体缓存，不增加轮询。全量 28 周期目录与推荐入场算法保持，目标 32／28sp、小级别 16sp、至少 48dp 独立点击区域及原导航保留。

四项 app 检查 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 通过，耗时 2 分 21 秒；359 项单元测试零失败。六项原生集成测试通过，耗时 30.829 秒。新增截图同文案用例以 `2D / 2H`、`1H / 3m` 目标与 `30m / 5m` 待更新警报、价格 `81056.5 / 82998.2`，覆盖 320dp × 字体 1.0／1.3／1.5 × 浅／深主题，断言两条警报位于目标右侧、垂直范围重叠且价格未裁切。原有 180／250／320dp × 字体 1.0／1.5 × 浅／深的 12 组资源上下文及真实 AppWidgetHost 窄／宽场景继续通过；250dp、150% 字体的剩余宽度不足场景保留下移。

| 证据 | 范围 |
| --- | --- |
| [构建与检查日志](../build/pullback-alert-inline-checks.log) | 四项 app 检查，359 项单元测试 |
| [原生测试日志](../build/pullback-alert-inline-instrumentation.txt) | 六项原生集成测试 |
| [截图同文案布局](../.impeccable/review/pullback-alert-inline/contact-phone.png) | 320dp、三档字体及浅／深主题的同行位置与价格完整性 |
| [浅色布局](../.impeccable/review/pullback-alert-inline/contact-light.png)、[深色布局](../.impeccable/review/pullback-alert-inline/contact-dark.png) | 原有 12 组生产布局资源上下文 |
| [窄／宽宿主](../.impeccable/review/pullback-alert-inline/contact-host.png) | 模拟器真实 AppWidgetHost |

截图同文案用例的 BTC 标题属于组件测试包装，在深色图中未套生产标题样式；生产布局的品种标题仍使用可读的辅助色。此轮证据来自原生资源上下文与模拟器宿主，未连接用户实体手机，也不代表已在整机层面切换系统日夜模式或完成全局视觉验收。下文保留 2026-10-16 的实现与历史验证记录。

## 2026-10-16 桌面无法显示修复

用户模拟器桌面出现 `Can't show content`。日志确认 `LazyColumn` 的品种哈希 ID 小于 Glance 的保留边界 `-4611686018427387904`，导致整个组件组合失败；真实配置中的 CLUSDT、SOXLUSDT 可触发。稳定哈希现限制为非负 63 位，仍按品种保持身份，不依赖排序或候选筛选，不修改配置和刷新策略。

回归覆盖 10,010 个品种标识的合法范围、样本唯一性和顺序稳定性，并新增 10 品种的生产 `PullbackObservationWidgetContent`／`LazyColumn` 窄宽组合测试。原来仅三个品种的宿主测试未覆盖保留区哈希，普通 Column 预览也不能验证惰性列表 ID。此次构建、lint、359 项单元测试与 5 项原生测试全部通过；在用户的 emulator-5556 保留原组件 ID 36 和设置，覆盖安装后确认桌面恢复，当前进程无 Glance／RemoteViews 错误。桌面证据：[恢复截图](../build/pullback-widget-id-fix/widget-restored.png)；检查日志：[构建与单元测试](../build/pullback-widget-id-fix-checks.log)、[5 项原生测试](../build/pullback-widget-id-fix-instrumentation.txt)。最新验证结果见本节链接日志，后文保留首轮实现过程记录。

日期：2026-10-16。基于现有 Kotlin、Glance／RemoteViews 与 Material 3 实现，是既有乖离组件的新增模式。产品与视觉约定见 [PRODUCT.md](../PRODUCT.md)、[DESIGN.md](../DESIGN.md) 和 [局部规范](../.impeccable/surfaces/pullback-widget.md)。

## 使用与行为

在「乖离速览」组件配置中选择「回调观察」，关注最多 10 个品种。每实例候选筛选独立保存、默认关闭，规则与手机回调页共用。每品种多头在上、空头在下，候选 32sp 加粗、等待 28sp 中等字重，小级别 16sp；中性背景和方向色共同区分候选与等待。顶栏始终一行 48dp，没有底栏；窄版保留菜单、候选和刷新，至少 250dp 增加编辑，320dp 普通字体额外显示计数。

菜单包含采样、计数、状态、参数、编辑、刷新及应用入口。点击目标打开固定采样依据；点击推荐小级别打开原品种的小周期 K 线；宽版点击警报打开原绑定周期并定位原 ID。三类入口各自独立。关闭、遮罩或返回取消依据，刷新不会重开；来源地址变化时阻止用旧来源目标进入新来源 K 线。

宽版警报摘要固定在目标与推荐小级别右侧，使用同一主行剩余宽度，不因宽度或字体缩放移到下方。摘要第一行意图、绑定周期及时间／状态，第二行价格，各字段单行；紧窄大字体下辅助标签／状态可省略，目标与推荐周期优先。点击区至少 48dp，并按摘要自身的顶段、价格行与时间标签内距测量增高；当前高度度量保守保留状态两行的空间，实际状态显示仍限一行。有效时间为无填充圆角边框，天蓝、小时紫、分钟金；已触发、范围结束和待更新保留真实状态语义。组件不提供新后台声音，完整警报管理与延期沿用应用。

## 数据与性能

`PullbackWidgetProjector` 只在快照或规则变化时重建，使用快照原始服务器时钟及校准接收时间判定采样时候选。到达时已经无效、缺时钟或非完整快照不可生成候选；有效采样日后过期仍保留历史观察，不延长实时 TTL。首版在此时显示“快照旧”；该展示规则已由上文 2026-10-17 的 17 分钟传输超期语义取代。冻结依据包含原规则、目标与背景证据、均线、采样时间、入场周期和来源，Activity 中不随刷新替换。

`GuailiWidgetWorker` 复用既有约 15 分钟 WorkManager；实际更新时刻受系统调度与网络限制。V2 和回调的同源手动刷新共用一次信号读取，矩阵与决策提醒保留各自来源。价格警报复用前台已有的 10 秒读取并合并相同在途请求。每个服务地址隔离缓存，确认编辑后提高版本，晚到旧全量读取不能覆盖新结果。

相同警报内容不重复编码持久缓存；2026-10-17 起独立持久化成功完整读取凭据，只有持久化成功且来源版本匹配才能建立新鲜度，保存失败使其失效并反馈失败，时间写入的合并规则见上文。仅匹配模式／来源的实例订阅和更新；候选筛选直接切换只重绘该实例。分组在真实过期边界重建，当前可见行的共享列宽按尺寸、字体与筛选缓存；无新增行情轮询、后台候选声音或倒计时请求。

## 实现入口

| 文件 | 职责 |
| --- | --- |
| [PullbackWidget.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidget.kt) | 模式展示、尺寸测量、单行顶栏、目标／小级别／警报独立动作 |
| [PullbackWidgetPresentation.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetPresentation.kt) | 原始采样投影、历史状态、冻结证据与警报分组边界 |
| [PullbackWidgetActivity.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetActivity.kt) | 菜单、共用参数面板、固定依据与 K 线 Intent |
| [PullbackWidgetAlerts.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetAlerts.kt) | 按来源警报缓存、合并读取、确认版本、成功持久化后新鲜度 |
| [PullbackWidgetFreshness.kt](../app/src/main/java/com/gouge/guaili/widget/PullbackWidgetFreshness.kt) | 17 分钟传输边界、同次启动的单调时钟校验、60 秒凭据写入合并 |
| [ServerSignalWidgetRefresh.kt](../app/src/main/java/com/gouge/guaili/widget/ServerSignalWidgetRefresh.kt) | 按模式隔离过期重绘、回调最早待执行本地边界 |
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
