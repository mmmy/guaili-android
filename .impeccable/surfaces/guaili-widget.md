---
version: 1
slug: "guaili-widget"
primary_target: "app/src/main/java/com/gouge/guaili/widget/GuailiWidget.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/signals/SignalCardStyle.kt", "app/src/main/java/com/gouge/guaili/widget/GuailiSignalPresentation.kt"]
---

# 桌面小组件局部规范

Mode: Operate. 继承 ../../PRODUCT.md 与 ../../DESIGN.md。

矩阵与分组组件以数值为重点，保留周期、数值字号和密度算法；品种分组标题与编辑、刷新弱化。V2 组件共用应用信号语义：彩色周期加粗，品种、结构和状态次级。

2026-10-12 已确认“一行优先”原型：默认单条摘要尽量一行、最多两行；纵向内距与信号间隙各 2dp，横向内距 8dp。按 Android 实际字体测量组合品种、周期和简短结构／阶段／级数，空间不足转两行。结构、EMA、阶段、级数不各占一行；分歧两段周期完整，极窄或大字体时次级品种可省略，完整信息保留在无障碍描述和应用详情入口。不能裁切周期或缩小全部文字，也不能删掉滚动功能。周期点击仍打开原品种／锚点 K 线；标题进入应用、编辑／刷新／状态导航保持。

保留所有点击导航、编辑、刷新、状态详情及无数据／过期提醒；保留日夜调色板和 Glance 响应式尺寸、列表节点限制。检查 180、250、320dp 组件宽度、字体缩放与深浅主题。禁止为层次调整增加后台轮询或持续动画。
