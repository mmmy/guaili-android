---
version: 1
slug: "market-signals"
primary_target: "app/src/main/java/com/gouge/guaili/ui/MarketSignalsContent.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/signals/SignalCardStyle.kt", "app/src/main/java/com/gouge/guaili/signals/SignalCardPresentation.kt"]
---

# V2 局部规范

Mode: Operate. 继承 ../../PRODUCT.md 与 ../../DESIGN.md。

参与周期独立主行，16sp 加粗，直接使用原结构颜色；品种 12sp 次级色，结构说明 11sp 常规，方向、级数、EMA 与阶段 10sp 常规。分歧短段和长段分别显示范围及各自方向、级数；原区间变化含义保持。

范围使用真实后端参与周期，不把锚点或范围称为确认入场级别。保留级数展开、长按详情、K 线、矩阵定位、筛选、状态与联动。尊重字体缩放，范围可换行，辅助行按宽度分行；独立页和旁侧信号栏采用相同主次。
