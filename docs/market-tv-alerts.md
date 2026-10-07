# 乖离表格中的 TV 警报

实现日期：2026-10-07。复用 Android 的 XBot 账户、TV 警报接口、共享缓存与重设流程。

## 使用

- 登录 XBot，并在「TV 警报」管理页选择要显示的配置。表格上方显示选择范围、同步状态和更新时间；点击可进入管理页。
- 品种标题的「TV N」打开该品种所有周期的警报。矩阵、分组、仅 V2 信号格及展开的全级别均在对应数值格右下角显示 TV 数量；左上角仍显示原有价格穿越警报圆点。琥珀色表示包含业务已过期警报，灰色表示全部停用，其余为浅蓝色；具体状态见详情。
- 有指标数据时，点击数值格打开原有详情，增加该周期的 TV 警报列表。有 TV 警报但没有指标数据时，点击整格直接打开该周期 TV 列表。单周期默认展开前三条，可展开其余警报。
- 详情先显示品种、信号值及警报摘要，随后提供「View K-line」和「查看该周期的实时信号」，再展示 TV 警报和状态；低频的 Indicators、Candle time 放在最后。
- 详情分别列出配置名称、真实警报名称、周期、TV 启用／停用、业务有效期和到期时间。启用但有效期未配置或无法计算时不计为「启用且业务有效」。业务有效期在页面前台本地更新。
- 过期警报直接提供「重设」；其余警报的「更多操作」菜单提供重设。确认框显示配置、完整品种代码和周期，并说明按当前配置覆盖重建、重新计算业务有效期。可以取消。
- 重设复用现有 `MainViewModel` 和 `TvAlertResetter`。提交后等待后端任务完成，且确认出现新 ID 的启用警报后才报告成功。失败或结果未确认保留相应提示；操作期间禁止重复提交和刷新竞争。结果同步到表格、TV 管理页和共享缓存。
- 「前往 TV 管理」定位到所选配置及警报；返回行情即可看到同一份数据。

## 匹配和刷新

- 默认完整代码为 `BINANCE:<行情代码>.P`，与当前 Binance 合约行情来源对应。详情的「品种映射」可保存其他完整代码或恢复默认，保存按行情服务器地址隔离。不会自动合并现货、永续合约或不同交易所。
- `D/1D`、`W/1W` 统一身份匹配；分钟、秒、日、周、月保留各自身份，不将 `1440` 分钟当作日线。未知周期仍可在品种列表查看。
- 只统计 TV 管理页选中的配置。按「账户 ID + 警报 ID」去重，同一账户的重叠名称前缀选择最长匹配，避免重复计数或对同一警报重复操作。
- 进入或返回表格、应用回到前台、手动刷新时读取已有后端缓存列表。不会每格发请求，也不会自动调用强制刷新 TradingView 缓存接口。完整 TV 账户刷新继续由管理页现有入口提供。
- 同步失败保留上次角标和列表，显示失败状态；未登录、未同步、未选配置分别提示。失败的缓存读取期间禁用重设，需刷新成功后再操作。退出或切换账户使用现有会话隔离机制。
- 当前列表接口未提供 TV 触发历史；本功能展示设置和有效期，不增加 TV 触发次数。

## 验证

- `:app:testDebugUnitTest`：263 项通过；其中新增 6 项覆盖完整品种身份、显式映射、多账户去重、隐藏配置、重叠前缀、周期别名、有效期边界和缓存失败／删除。
- `:feature-xbot:testDebugUnitTest`：84 项通过，包括现有重设流程的 9 项测试。
- 新增 `MarketTvAlertsIntegrationTest`：使用设备内 MockWebServer 和隔离测试账户，验证对应周期数量、无指标数据的入口、取消确认、单次覆盖提交、确认后的管理页同步、映射保存与重建、读失败及后端拒绝。测试不写入生产 TV 账户。
- 手机及 1.3 倍系统字号的平板各 4 项联调通过；Debug 与测试 APK 构建及两个模块的 Lint 通过。测试保存并恢复账户偏好、行情设置和缓存；模拟器尺寸、密度与字体设置在验证后恢复。
- 最终手机回归合计 14 项通过：新增 TV 表格联调 4 项、原有价格穿越表格联调 2 项、原有 TV 管理页交互 8 项。

截图来自原生 Android 模拟器，均使用「测试」警报：

- [手机分组表格](assets/market-tv-alerts/groups-phone.png)
- [手机周期详情](assets/market-tv-alerts/detail-phone.png)
- [重设确认](assets/market-tv-alerts/confirmation-phone.png)
- [手机矩阵](assets/market-tv-alerts/matrix-phone.png)
- [平板放大字号](assets/market-tv-alerts/detail-tablet-font-1.3.png)

构建：`./gradlew.bat :app:testDebugUnitTest :feature-xbot:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :feature-xbot:lintDebug`。

联调：`adb shell am instrument -w -e class com.gouge.guaili.integration.MarketTvAlertsIntegrationTest com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner`。
