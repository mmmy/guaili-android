---
version: 1
slug: "tv-kline"
primary_target: "app/src/main/java/com/gouge/guaili/ui/KlineTvAlerts.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/ui/KlineScreen.kt", "app/src/main/java/com/gouge/guaili/ui/TradingAppScreen.kt", "feature-xbot/src/main/java/com/gouge/xbot/ui/TvAlertScreen.kt", "feature-xbot/src/main/java/com/gouge/xbot/ui/QuickBtcAlertSheet.kt"]
---

# TV 警报与 K 线联动

Mode: Operate. 继承 ../../PRODUCT.md 与 ../../DESIGN.md。

2026-10-12 用户授权第一版：TV 管理与 K 线双向跳转；K 线内当前周期列表、添加、再设、删除。图上触发标记与条件线不在本版范围。

- 管理页的品种／周期为 K 线入口，至少 48dp；不挤入另一排按钮。只按完整代码匹配，区分交易所、现货和永续。Binance 永续可以打开未加入自选的行情品种；显式映射优先，歧义／未知周期说明原因，不猜测转换。D/1D、W/1W 可归一，1440 分钟与日线独立。
- K 线 TV 入口靠前且紧凑；面板默认当前品种／当前周期，切换全部周期不修改图表或警报。惰性列表按账户＋警报 ID 复用。未知有效期如实表达，不等同于有效。
- 从管理页带入的警报只在列表中突出，用 primary 细框和文字区分焦点与业务状态；关闭、返回、遮罩取消焦点，刷新不恢复。换品种／周期清除焦点。返回恢复管理页滚动与展开、K 线周期与缩放平移。
- 添加先选择 TV 管理已选择的配置，预填当前完整代码与周期，可编辑；取消不提交。有效但不在常用周期选项中的当前周期保留可见与可选。再设／删除需确认具体配置、品种和周期，进行中禁止重复写入，失败／待确认不显示成功。
- 复用 MainViewModel、AlertDataCoordinator、既有创建／再设等待与结果确认；进入、回前台、手动刷新及操作完成同步，不增加按周期轮询或强制刷新 TV 缓存。后台停止本地有效期计时，隐藏面板不运行计时。
- 保留现有价格绘图、警报、光标、缩放与刷新。独立深色主题适配窄屏、宽屏、字体缩放；辅助内容可读，错误可重试。
