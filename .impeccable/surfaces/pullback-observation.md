---
version: 1
slug: "pullback-observation"
primary_target: "app/src/main/java/com/gouge/guaili/ui/PullbackContent.kt"
related_targets: ["app/src/main/java/com/gouge/guaili/ui/GuailiScreen.kt", "app/src/main/java/com/gouge/guaili/ui/MarketSignalsContent.kt", "app/src/main/java/com/gouge/guaili/ui/ServerSignalsViewModel.kt", "app/src/main/java/com/gouge/guaili/pullback/PullbackProjection.kt", "app/src/main/java/com/gouge/guaili/settings/PullbackPreferences.kt", "app/src/main/java/com/gouge/guaili/settings/MarketSignalPreferencesStore.kt"]
---

# 回调观察局部规范

Mode: Operate. 继承 [PRODUCT.md](../../PRODUCT.md) 与 [DESIGN.md](../../DESIGN.md)。2026-10-11 用户已确认交互原型，并授权全部需求实现；以现有原生实现为依据，不要求复制浏览器审核外框。

## 观察重点与布局

入口为「行情 → 回调观察」，与表格、V2 并列。每品种多头在上、空头在下，目标级别直接表达方向与候选重要性。品种用于定位；乖离、强度、阶段、参数摘要和控件保持辅助层。此层次与多绿空红只适用于回调页，不改矩阵、V2、K 线和小组件已有语义。

候选目标为 Material 3 headlineLarge（32sp / Bold），等待目标为 headlineMedium（28sp / Medium）。多头映射 secondary（当前 #A7F3D0），空头映射 error（当前 #FFB4AB）；候选直接使用方向色，等待从 onSurfaceVariant 向方向色混合 55%。候选背景 surfaceVariant 为中性 #202832，等待背景透明；选中以 primary 的 1dp 边框单独表达。品种 labelMedium 与乖离／说明 bodySmall 为 12sp，方向及采样时间 labelSmall 为 11sp；辅助信息用 onSurfaceVariant，错误与不可用原因保持 error 可读。

应用在系统日夜模式下均沿用独立深色 GuailiTheme。目标行圆角 12dp、最小高度 72dp、横向内距 12dp、纵向内距 10dp；文字放大可增高。工具和开关保留至少 48dp 触摸区域，宽度／fontScale 小于 330dp 时两项开关上下排列。宽屏沿用导航侧栏，观察内容居中且最大 760dp。按品种稳定键复用 LazyColumn；底部面板可滚动，参数避让输入法。

## 参数、判定与质量

默认 N=5、T=10、K=2、最小目标级别 1 分钟、R=3。N 含目标；T 为整数 value 阈值；K 只计前 N−1 个背景。保存前校验 N 为 2–64、T 为 1–1000、K 为 1–N−1、R 为 0–1000；最小级别支持正分钟数字或 S / D / W，并按实际时长比较。最小级别与 R 位于「高级参数」，取消不保存，错误留在面板。

完整 `/api/signals` 证据按实际时长从大到小形成轴，缺失、过滤、重复别名、过期及无效证据占位并阻断连续性，不删掉后拼接。每个最大同向段从大级别起点观察固定的第 N 级，前 N−1 级按原始 guaili 正负同向，不把窗口滑向更小级别；每方向取最大的合格段。

背景全部 |value| < T 为弱势，至少 K 个背景 |value| ≥ T 为强势，其余普通；目标不计入强度。目标原始 guaili == 0 为触及均线，弱势／强势高亮、普通不高亮。原始非零但整数 value 为 0 仅是接近均线，不高亮。强势小幅反向要求整数 value 非零且 |原始 guaili| × 10 ≤ R；同向等待及反向超限不高亮。

只消费完整快照及 ready、未闭合、有限值且与原始 guaili 一致的证据；保留后端 qualityConfig、服务器时间和设备单调时钟的有效期判断。子集、过期、异常时基、缺元信息或非 live 状态不推断候选。有效零与缺失 `—` 分开，未知不能伪装成无候选。

## 筛选、依据与提醒

「只显示回调候选」和「声音提醒」分别保存且默认关闭，回调参数与原表格／V2 筛选互不覆盖，旧偏好取得默认值。候选筛选只显示高亮方向，无候选品种隐藏；筛选不改变声音判定。

依据固定打开时的目标、背景、规则、均线参数和采样时间，刷新不替换这份证据；当前质量失效时提示不可确认。关闭按钮、系统返回和遮罩取消选择；刷新不得恢复已取消状态。打开参数或切换候选筛选也清除选择。K 线传入原 symbol 与 interval，返回保留视图及列表位置。

提醒身份为品种＋多空方向＋实际周期时长，一次更新有多个新候选只发一个短通知音，持续候选不重复提醒。有效证据确认消失后再出现可重响；未知、缺失、断网与过期不能证明消失。启动、重新进入、开启声音、改规则或换服务器静默建立基线。只在前台回调观察页发送 SharedFlow 事件，ToneGenerator 使用通知音量、按生命周期复用并释放，不新增后台服务。

复用 ServerSignalsViewModel、完整快照缓存、现有刷新协调器和可取消请求，不新增行情轮询。快照、规则或质量边界变化时重建投影，复用有效期间的排序；离开行情、打开 K 线或退后台遵守现有前台暂停边界。刷新保持观察位置。

## 验证入口

检查手机、360dp／150% 字体、宽屏／130% 字体及系统日夜模式，验证级别主次、方向颜色、候选与等待、文字换行、触摸区域、空／过期／错误状态。行为覆盖独立保存、参数取消与校验、依据冻结及撤销、刷新不复选、原周期 K 线、返回位置、声音新出现／持续／重现／未知及静默基线。截图、测试证据与限制见 [Android 回调观察实现与验证](../../docs/Android回调观察实现与验证.md)。
