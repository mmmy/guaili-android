后端服务程序在: F:\test\crypto-candlestick\config.toml
部署在服务器: ssh -p 22 root@139.180.203.107
服务器访问在: http://139.180.203.107:3005
后端服务程序本地一般启动在: http://127.0.0.1:3005

桌面端程序代码在: F:\workspace\xbot-desktop

# 项目实现原则

- **代码运行时执行效率优先，允许较高的开发难度和实现复杂度。** 不为降低开发成本而牺牲计算效率、内存占用、输入响应或 I/O 效率。

## UI 开发入口与用户偏好

- 修改 UI 前读取 [PRODUCT.md](PRODUCT.md)、[DESIGN.md](DESIGN.md) 和 `.impeccable/surfaces/` 中匹配目标的局部规范。Impeccable context 传入明确目标文件。
- 局部入口：[主表格](.impeccable/surfaces/guaili-table.md)、[V2 信号](.impeccable/surfaces/market-signals.md)、[桌面小组件](.impeccable/surfaces/guaili-widget.md)。共享组件遵守对应页面任务的规范。
- 主表格以 guaili 数值为重点，保留级别字号、格子尺寸、密度和颜色语义；V2 才突出真实结构参与周期，说明与控件退到辅助层。视觉重点由页面任务决定，不统一放大全部级别。
- 应用与小组件同步语义和主次，布局按 Android 适配；尊重字体缩放、深浅主题和触摸区域，不能只靠低透明度弱化信息。
- 行情状态与选中／焦点／展开分别表达；可取消选择能撤销，刷新不能恢复已取消状态。保留现有功能、惰性列表、缓存与生命周期，不为视觉调整增加轮询或无意义重组。
- 完成修改验证窄屏、宽屏、大字体、深浅主题、颜色与密度、选择及撤销和点击导航；长期约定同步到产品、设计与对应局部规范。
