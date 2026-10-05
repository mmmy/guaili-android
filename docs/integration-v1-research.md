# Guaili × XBot 第一版整合调研

调研日期：2026-10-04。目标是在一个 Guaili App 内接入 XBot 的账户、信号设置、TradingView 警报管理和桌面组件，让现有两套功能完整可用。

## 竞品证据

以下事实来自官方公开页面，取舍是针对本项目的设计判断。

| 产品与来源 | 已验证的能力 | 本项目借鉴 |
| --- | --- | --- |
| TradingView：[Manage alerts](https://www.tradingview.com/support/solutions/43000595311-manage-alerts/) | 列表显示活跃、已触发、手动停止、已过期及停止原因；支持排序、编辑、停止、恢复和触发日志。 | 状态使用文字与颜色共同表达；失败和过期提供具体原因。 |
| TradingView：[Learn how to configure alerts](https://www.tradingview.com/support/solutions/43000763312-learn-how-to-configure-alerts/) | 品种、条件、触发频率、周期和到期时间分别配置。 | 保留各业务字段含义，不将不同有效期合为一个状态。 |
| TradingView：[2023 年官方移动端更新](https://www.tradingview.com/blog/en/tradingview-product-update-40147/) | 官方介绍从品种页面、自选列表滑动或长按创建警报的移动端入口。 | 后续可从行情品种进入对应配置；第一版先建立完整管理入口。 |
| TrendSpider：[Manage Existing Price Alerts](https://help.trendspider.com/kb/alerts/manage-existing-price-alerts) | 警报面板管理活跃、过期及曾触发的警报；支持详情、复制、编辑、重新激活和删除确认。 | 保留原有再设配置和删除确认，操作完成后刷新列表。 |
| 3Commas：[Signal Bot](https://3commas.io/signal-bot) | 以日志呈现信号处理过程，提供统一管理界面。 | 保存、删除、再设给出明确的进行中、成功与失败反馈。 |
| 3Commas：[信号拒绝状态](https://3commas.io/blog/signal-bot-max-number-of-active-smarttrades-per-bot-pair) | 达到限制的信号会被拒绝，并在日志中标为 Rejected。 | 将失败结果明确展示，避免把请求提交等同于业务成功。 |
| 3Commas：[Positions Tab Overview](https://help.3commas.io/en/articles/16281060-positions-tab-overview-signal-bot) | 列表中的 View chart 操作可打开相关品种图表。 | 后续信号与行情联动应保留品种上下文。 |
| TradingView：[2021 年官方移动端更新](https://www.tradingview.com/blog/en/you-ve-improved-our-mobile-apps-25189/) | 历史更新说明 Android 提供 widget shortcuts。 | 桌面组件点击应准确打开对应业务入口。 |

TradingView 最接近行情与警报客户端；TrendSpider 适合参考警报生命周期；3Commas 是信号处理邻近竞品，只借鉴管理和反馈方式。

## 第一版实际取舍

1. 一个 Guaili App，提供「行情」「信号」「TV 警报」「设置」四个 Material 入口。这是本项目的信息架构，不代表竞品当前导航。
2. XBot 接入独立 Android feature library，宿主负责导航和应用初始化，优先保留已有业务行为。
3. 两套后端、网络客户端与配置保持独立。XBot 凭据只用于 XBot 请求；未登录不阻断行情浏览。
4. 行情数据过期、XBot 设置有效期、TV 警报业务有效期分别展示，沿用各自业务语义。
5. 保留现有桌面组件、再设流程及警报操作确认。组件跳转到对应业务，账户退出或服务变更后清理相关缓存与组件数据。
6. 请求进行中限制重复提交；失败保留可重试上下文，成功后同步界面。

第一版不新增自动交易，不合并信号处理后端，不引入竞品的多账户镜像、仓位管理或订单执行。行情到信号配置的快捷联动留在基础整合稳定后实施。

## 公开文档边界

- 本次未登录竞品进行实际操作，不能据此保证当前移动端标签顺序、全部手势或组件布局。
- 2021／2023 年 TradingView 更新仅证明历史上介绍过相关能力，不能视为当前版本完整功能清单。
- TrendSpider 的 Alerts widget 是应用内侧栏面板，不能当作 Android 桌面组件证据。
- 公开页面不能验证竞品内部凭据、缓存和任务隔离实现。本项目的双后端与账户隔离是工程设计决定。
- 各来源均为短篇转述；订阅价格、功能额度和交易策略不用于第一版设计依据。

验收重点：四个入口可达、未登录可看行情、XBot 功能与原有组件完整接入、账户与服务状态互不干扰，并通过编译及适合本次整合的验证。
