# Guaili Android 界面规范

## Overview

继承现有 Android 视觉和 Material 3 交互。2026-10-11 用户确认：同步桌面端的页面任务和视觉主次，手机按自身空间与触摸需求适配。关键对象直接表达语义，辅助文字、背景和操作适度弱化，不能仅靠降低透明度造成难读。

主表格看 guaili 数值，不放大全部级别；V2 看真实结构参与周期，不让结构名称或级数抢占重点。小组件按同一任务规则展示，不能因空间不足退回“标题强、周期弱”。

回调观察是已批准原型的原生扩展：每品种多头在上、空头在下，用户先辨认目标级别，再看强弱与阶段，按需打开依据和 K 线。该页面的层次与方向颜色保持局部适用。

## Colors

保留 GuailiColors.kt 的乖离值底色、过滤色与趋势文本含义，以及现有矩阵深色。普通周期趋势、V2 动态趋势、结构颜色各有来源，不混用。

应用 V2 继续通过 MaterialTheme 映射现有 SignalCardPalette；小组件继续使用 SignalCardStyle.Light / Dark。上方共振为现有正向色，下方共振为负向色，近均线为 accent，分歧为 warning；这些颜色直接用于参与周期。结构名称与说明使用 secondary。不把回调窗口的多空颜色替换到 V2。

回调观察的多头级别映射 `MaterialTheme.colorScheme.secondary`（当前绿色），空头级别映射 `error`（当前红色）；候选直接使用方向色，等待级别由 `onSurfaceVariant` 向方向色混合 55%。候选使用中性 `surfaceVariant` 底层，等待使用透明底层，品种、乖离和说明用 `onSurfaceVariant`。选中边框使用 `primary`，不能用选中框替代候选状态。

选中边框、触摸反馈和展开状态独立表达；缺失、过期、失败等警告不能随辅助信息一起弱化。应用在系统日夜模式下均沿用既有独立深色主题；小组件保留自身 Light / Dark 行为，分别验证。

## Typography

沿用系统字体，字号用 sp 并尊重字体缩放。主表格数值、周期字号由现有 TableDensity / GroupLayoutSize 决定，保留不变；品种为较弱分组定位信息。

V2 应用和小组件共用 SignalCardStyle：参与周期 RangeFontSp = 16，加粗；品种 TitleFontSp = 12，次级色加粗；结构说明 DescriptionFontSp = 11，常规字重；级数、方向、EMA、阶段 BodyFontSp = 10，常规字重。范围可换行，分歧两段各自拥有范围和说明。

回调观察候选目标使用 Material 3 `headlineLarge`（32sp / Bold），等待目标使用 `headlineMedium`（28sp / Medium）；品种用 `labelMedium`（12sp），乖离与阶段说明用 `bodySmall`（12sp），方向与底部采样时间用 `labelSmall`（11sp）。辅助内容保留完整可读文本，不能升到目标级别同一层次。

## Layout

保留手机底部导航、宽屏侧栏，主表格、分组表格与仅 V2 格模式及密度选项。主表格不因强调周期增加格宽、格高或减少可见数据。V2 卡片允许为完整周期增加必要高度，应用与组件仍使用惰性列表。

手机窄屏与大字体下各范围占完整可用宽度，阶段和趋势需要时分行。不要用固定高度截断文字。小组件按真实可用尺寸适配，保留 RemoteViews 子节点限制与滚动能力。

回调观察使用按品种稳定键复用的 LazyColumn，内容居中且最大宽度为 760dp。目标行最小高度 72dp，横向内距 12dp、纵向内距 10dp，文字放大时允许增高。筛选／声音行保留至少 48dp 触摸区域；可用宽度除以 fontScale 小于 330dp 时两项开关上下排列。宽屏沿用主导航侧栏；依据与参数底部面板可滚动，参数面板避让输入法。

## Elevation & Depth

回调观察以中性底层和字号／字重区分候选与等待，不增加装饰性阴影。原生 Material 底部面板与遮罩表达暂时查看依据或编辑参数的层次，关闭后取消对应选择。

## Shapes

回调目标行使用柔和圆角（12dp），选中时以同形的 1dp `primary` 边框标记。开关、输入框、按钮及底部面板沿用现有 Material 3 组件形状；该行圆角不替换矩阵格子或 V2 的既有形状。

## Components

次级工具使用现有原生／Material 按钮与图标，降低视觉对比和字重，保留点击区域、状态、语义标签和反馈。应用常用按钮保留至少 48dp 触摸区域。紧凑数据格不因本次层次调整改变尺寸；已有格子详情与导航仍可操作。

保留筛选、排序、状态详情、级数展开、K 线、矩阵定位、选择及撤销、滚动恢复。小组件保留编辑、刷新、状态详情、卡片与格子的点击目标。数据刷新不改写手动滚动或选择。

回调筛选与声音为独立开关。依据固定打开时的目标、背景、规则、均线参数和采样时间；当前数据失效时提示不可确认，保留原依据。关闭按钮、遮罩和系统返回取消选择，刷新不自动打开；K 线传入原 symbol / interval 并保留返回后的观察位置。参数取消不保存，校验错误留在面板中供修正。

局部规范：主表格见 [.impeccable/surfaces/guaili-table.md](.impeccable/surfaces/guaili-table.md)，V2 见 [.impeccable/surfaces/market-signals.md](.impeccable/surfaces/market-signals.md)，回调观察见 [.impeccable/surfaces/pullback-observation.md](.impeccable/surfaces/pullback-observation.md)，小组件见 [.impeccable/surfaces/guaili-widget.md](.impeccable/surfaces/guaili-widget.md)。

## Do's and Don'ts

- 检查手机、宽屏、字体缩放、系统日夜模式及小组件浅／深主题、长品种和周期范围；截图来自 Android 模拟器或真机。
- 验证主表格颜色与密度不变、分歧两段完整、详情和导航可达、刷新不破坏观察位置。
- 回调观察检查级别多绿空红、候选强于等待、多上空下、48dp 触摸区域、窄屏／150% 字体和宽屏；验证筛选及声音独立保存、依据取消和刷新不复选、K 线原周期及返回位置。
- 不复制 Windows DIP 尺寸或工具栏布局，不把统一突出级别当成全产品规则，不改变业务判定与网络调度。
