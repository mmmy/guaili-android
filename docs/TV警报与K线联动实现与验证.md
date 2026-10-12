# TV 警报与 K 线联动实现与验证

日期：2026-10-12。范围为 Android 第一版：双向导航、当前周期 TV 列表、添加、再设与删除；不绘制 TV 触发点或条件线。

## 使用

- TV 管理页点击警报的品种／周期打开对应 K 线。当前 Binance 永续可打开自选外品种；其他交易所或现货必须匹配已有显式行情映射。歧义、无法识别的周期给出原因，不猜测替代品种。
- K 线工具行首部的 `TV N` 为当前品种／当前周期的警报入口。底部面板默认当前周期，可切换同品种全部周期；仅使用 TV 管理已选择的配置。
- 从管理页带入的具体警报在面板中以细边框和“当前警报”突出。按账户 ID＋警报 ID 识别，跨账户同 ID 不串选；关闭、返回或遮罩取消突出，刷新不恢复。切换品种／周期清除突出，不修改原警报绑定。
- 添加先选择配置，预填完整 TV 品种代码和当前周期。仍可编辑品种和周期；不在常用选项中的有效周期也保留在选中项中。取消不提交。
- 行内更多菜单提供再设和删除。再设确认覆盖重建；删除确认展示配置、名称、完整品种和周期。进行中禁止重复写入；拒绝、失败和结果待确认不作为成功。同步失败保留上次结果，刷新成功后再操作。
- 行内“前往 TV 管理”定位具体配置和警报；通用管理入口打开管理页。系统返回恢复 K 线；从管理进入 K 线后返回恢复管理页滚动及展开。K 线周期、横向视口和手动价格轴保存，加载或活动重建不丢失。

## 数据与运行效率

复用同一 `MainViewModel`、`AlertDataCoordinator` 和既有后端创建／再设确认流程。删除确认后从共享缓存移除对应账户的警报，管理页和表格消费同一结果。导航映射随完整快照缓存，业务有效期在可见面板的前台本地更新。

进入 K 线、应用回前台、手动打开／刷新 TV 列表及写操作完成读取现有后端缓存。K 线定时刷新不请求 TV 列表，也不自动强制刷新 TradingView 缓存。后台和隐藏面板停止本地计时。列表使用稳定键及 LazyColumn；图表视口保存少量数值，不复制 K 线数据。

完整代码区分交易所、现货与永续。D/1D、W/1W 匹配同一周期，1440 分钟仍与日线独立。无法计算业务有效期不计为有效。列表接口缺少实际触发事件和每条警报的完整几何条件，因此本版只做配置与管理联动。

## 验证

- `:app:testDebugUnitTest`：277 项通过；其中 TV 测试新增 2 项，覆盖自选外 Binance 永续、完整品种身份、周期别名、分钟／日线独立、显式映射和映射歧义。
- `:feature-xbot:testDebugUnitTest`：84 项通过。Debug 应用、测试 APK 构建和两个模块 Lint 通过，保留已有警告。
- 新增 4 项 `MarketTvAlertsIntegrationTest`，使用本地 MockWebServer 和隔离测试账户：双向返回与活动重建、当前／全部周期和焦点撤销、精确品种与周期创建、成功／拒绝结果、再设／删除确认与跨账户区分、从行情进入 K 线及定时刷新不轮询 TV。

- Android 模拟器最终回归：标准手机 29 项通过（TV 联动 8、原 TV 管理 8、价格绘图 4、价格轴导航 3、TV 小组件 6）；320dp 窄屏、150% 字号、系统夜间模式下 TV 联调 8 项通过；900dp 宽屏、130% 字号、系统日间模式下 8 项通过。
- TV 小组件在系统日间／夜间模式分别验证原生集合的分组、折叠与展开，浅／深主题截图已检查；夜间独立复验 1 项通过。总计 46 项次设备验证通过。既有表格颜色、密度及 TV 同步流程通过原 4 项联调回归；小组件实现未改动。
- 已修正窄屏大字体下刷新按钮被筛选控件挤压的问题：刷新保留 48dp，筛选短标签仍有完整无障碍描述。截图来自原生设备；测试完成后恢复 1080×2400、420dpi、100% 字号及系统日间模式，并恢复原账户偏好和缓存。

本轮限定 Android 模拟器，未做真机或生产 TV 服务验证。测试不向真实 TV 账户写入，也不部署服务器。

| 截图 | 内容 |
| --- | --- |
| [手机当前周期](assets/tv-kline/current-phone.png) | 当前周期列表与账户级焦点 |
| [窄屏 150% 字号](assets/tv-kline/current-narrow.png) | 完整刷新触摸区域、短标签和换行信息 |
| [宽屏 130% 字号](assets/tv-kline/current-wide.png) | 原生底部面板与完整详情 |
| [添加预填](assets/tv-kline/setup-phone.png) | 完整 TV 品种和当前周期 |
| [TV 小组件浅色](assets/tv-kline/widget-day.png) | 原生集合分组与有效期 |
| [TV 小组件深色](assets/tv-kline/widget-night.png) | 深色主题的状态与文字对比 |

安装包：[`GuailiAndroid-tv-kline-debug.apk`](../dist/tv-kline/GuailiAndroid-tv-kline-debug.apk)。沿用 0.4.1 包名和版本号，Debug 构建。SHA-256：`83E23DB4529686D230AA969E4999F54E25016E92C61ECACD587D2F3552A61EB4`。

## 源码

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/gouge/guaili/ui/KlineTvAlerts.kt` | TV 入口、筛选列表、配置选择和操作确认 |
| `app/src/main/java/com/gouge/guaili/ui/TradingAppScreen.kt` | 双向导航、管理定位及页面状态恢复 |
| `app/src/main/java/com/gouge/guaili/ui/MarketTvAlerts.kt` | 完整品种和周期身份、反向映射 |
| `app/src/main/java/com/gouge/guaili/ui/KlineScreen.kt` | 工具入口、图表视口保存 |
| `feature-xbot/src/main/java/com/gouge/xbot/ui/TvAlertScreen.kt` | 管理页的品种／周期导航 |
| `feature-xbot/src/main/java/com/gouge/xbot/ui/QuickBtcAlertSheet.kt` | 原有设置面板的当前周期预填与输入保存 |

构建复验：

```powershell
.\gradlew.bat :app:testDebugUnitTest :feature-xbot:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :feature-xbot:lintDebug
```

交互联调：

```powershell
adb shell am instrument -w -e class com.gouge.guaili.integration.MarketTvAlertsIntegrationTest com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
```
