---
version: 1
slug: "guaili-widget"
primary_target: "app/src/main/java/com/gouge/guaili/widget/GuailiWidget.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/signals/SignalCardStyle.kt", "app/src/main/java/com/gouge/guaili/widget/GuailiSignalPresentation.kt"]
---

# 桌面小组件局部规范

Mode: Operate. 继承 ../../PRODUCT.md 与 ../../DESIGN.md。

矩阵与分组组件以数值为重点，保留周期、数值字号和密度算法；品种分组标题与编辑、刷新弱化。V2 组件共用应用信号样式和周期范围／说明构造，彩色范围为 16sp 加粗，结构说明较小次级色；分歧两段完整显示。

保留所有点击导航、编辑、刷新、状态详情及无数据／过期提醒；保留日夜调色板和 Glance 响应式尺寸、列表节点限制。检查 180、250、320dp 组件宽度、字体缩放与深浅主题。禁止为层次调整增加后台轮询或持续动画。
