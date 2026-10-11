# Guaili Android 界面规范

## Overview

继承现有 Android 视觉和 Material 3 交互。2026-10-11 用户确认：同步桌面端的页面任务和视觉主次，手机按自身空间与触摸需求适配。关键对象直接表达语义，辅助文字、背景和操作适度弱化，不能仅靠降低透明度造成难读。

主表格看 guaili 数值，不放大全部级别；V2 看真实结构参与周期，不让结构名称或级数抢占重点。小组件按同一任务规则展示，不能因空间不足退回“标题强、周期弱”。

## Colors

保留 GuailiColors.kt 的乖离值底色、过滤色与趋势文本含义，以及现有矩阵深色。普通周期趋势、V2 动态趋势、结构颜色各有来源，不混用。

应用 V2 继续通过 MaterialTheme 映射现有 SignalCardPalette；小组件继续使用 SignalCardStyle.Light / Dark。上方共振为现有正向色，下方共振为负向色，近均线为 accent，分歧为 warning；这些颜色直接用于参与周期。结构名称与说明使用 secondary。不把回调窗口的多空颜色替换到 V2。

选中边框、触摸反馈和展开状态独立表达；缺失、过期、失败等警告不能随辅助信息一起弱化。应用与小组件的主题来源保持现状，分别验证深浅主题。

## Typography

沿用系统字体，字号用 sp 并尊重字体缩放。主表格数值、周期字号由现有 TableDensity / GroupLayoutSize 决定，保留不变；品种为较弱分组定位信息。

V2 应用和小组件共用 SignalCardStyle：参与周期 RangeFontSp = 16，加粗；品种 TitleFontSp = 12，次级色加粗；结构说明 DescriptionFontSp = 11，常规字重；级数、方向、EMA、阶段 BodyFontSp = 10，常规字重。范围可换行，分歧两段各自拥有范围和说明。

## Layout

保留手机底部导航、宽屏侧栏，主表格、分组表格与仅 V2 格模式及密度选项。主表格不因强调周期增加格宽、格高或减少可见数据。V2 卡片允许为完整周期增加必要高度，应用与组件仍使用惰性列表。

手机窄屏与大字体下各范围占完整可用宽度，阶段和趋势需要时分行。不要用固定高度截断文字。小组件按真实可用尺寸适配，保留 RemoteViews 子节点限制与滚动能力。

## Components

次级工具使用现有原生／Material 按钮与图标，降低视觉对比和字重，保留点击区域、状态、语义标签和反馈。应用常用按钮保留至少 48dp 触摸区域。紧凑数据格不因本次层次调整改变尺寸；已有格子详情与导航仍可操作。

保留筛选、排序、状态详情、级数展开、K 线、矩阵定位、选择及撤销、滚动恢复。小组件保留编辑、刷新、状态详情、卡片与格子的点击目标。数据刷新不改写手动滚动或选择。

局部规范：主表格见 [.impeccable/surfaces/guaili-table.md](.impeccable/surfaces/guaili-table.md)，V2 见 [.impeccable/surfaces/market-signals.md](.impeccable/surfaces/market-signals.md)，小组件见 [.impeccable/surfaces/guaili-widget.md](.impeccable/surfaces/guaili-widget.md)。

## Do's and Don'ts

- 检查手机、宽屏、字体缩放、深浅主题、长品种和周期范围；截图来自 Android 模拟器或真机。
- 验证主表格颜色与密度不变、分歧两段完整、详情和导航可达、刷新不破坏观察位置。
- 不复制 Windows DIP 尺寸或工具栏布局，不把统一突出级别当成全产品规则，不改变业务判定与网络调度。
