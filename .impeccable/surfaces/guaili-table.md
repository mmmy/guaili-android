---
version: 1
slug: "guaili-table"
primary_target: "app/src/main/java/com/gouge/guaili/ui/GuailiTable.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/ui/GuailiScreen.kt", "app/src/main/java/com/gouge/guaili/ui/SignalFocusTable.kt"]
---

# 主表格局部规范

Mode: Operate. 继承 ../../PRODUCT.md 与 ../../DESIGN.md。

用户观察重点是全部级别的 guaili 数值。保留 TableDensity、GroupLayoutSize、周期字号、格子尺寸与颜色映射。品种标题用于定位，弱于数据；工具栏、筛选与辅助说明降低字重和对比但可读。仅 V2 格仍属于数据表格，不能机械套用信号卡片的 16sp 周期。

保留全部格子详情、状态、筛选、表格／分组切换、联动、警报、TV、选择与撤销。密集格子保持既有可操作尺寸；常用工具按钮保持 Material 触摸区域。新行情保持观察位置，字体放大按既有尺寸算法适配。

2026-10-16：横向矩阵只组合可见周期、可能的部分尾列和两侧各一列缓冲，表头与数据行共享窗口及 ScrollState。窗口首尾绑定同一个首个可见周期，固定视口下每跨一列最多改变一次窗口，不因非整列视口宽度在同一列内再次重组；不减少可见内容和焦点缓冲。屏外占位按单格实际取整后的像素宽累加，保持总宽、列对齐和程序定位；首次测量得到视口后收窄窗口。刷新、字体缩放和周期过滤不得恢复已取消的选择或重置列表位置。
