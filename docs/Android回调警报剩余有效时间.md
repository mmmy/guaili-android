# Android 回调警报剩余有效时间

更新：2026-10-15。按用户审核的 Android 原型实现，改动仅作用于回调观察的价格警报摘要及同源 +N 底部面板。

## 时间与显示

显示的是警报剩余有效时间，不是预计触发、下次检测、K 线收盘或起点倒计时。有限线段终点与警报 `expiresAt` 取较早者；向右/双向延伸的线段不受几何第二端点限制。

最大单位取整数部分：2 天 5 小时显示 `2D`、5 小时 20 分钟显示 `5H`、23 分 54 秒显示 `23m`，不足一分钟显示 `<1m`。天 #94B8DF、小时 #C2ACDF、分钟 #DFBD7B，时间文字 12sp，圆角边框与文字同色；主回调级别仍按原 32/28sp、多绿空红和候选中性底层表达重点。第一行意图、14sp 绑定周期与状态/时间，第二行保留精确价格；窄屏大字体沿用原列对齐和警报下移策略，48dp 点击区保持。

无截止时间的正常警报仍为“监测”。未来起点且有期限时同样显示距结束时间，无期限时为“未开始”。已触发持续保留；范围结束与延期入口、待更新、等精度、行情断开和线价无效不被数字覆盖。保存、失败、核对状态同样优先于时间，原延期预览、确认和撤销流程保持。无障碍描述使用“剩余有效时间约 N 天/小时/分钟”。

## 更新范围

### 圆角倒计时（2026-10-15）

用户审核移动端效果图后确认实现。主行与 +N 面板共用同一 `PullbackAlertSummary`：只有数字时间加无填充、1dp 同色细边框，4dp 圆角，水平内距 6dp、垂直内距 1dp，最小高度 20dp，12sp 文字 / 16sp 行高。标签不带前置间隔点，整体参加 FlowRow 换行；大字体按内容增高。已触发、监测、范围结束、异常与保存状态保留 11sp 纯文字。标签本身不新增点击行为，原摘要至少 48dp 点击区、精确价格第二行、窄屏下移及共享列继续保留。

此增量只修改渲染外观；时间计算、显示边界任务、生命周期和网络数据流不变。产品、设计和回调局部规范同步记录此长期约定。

纯时间判定在 `pullback/PullbackAlertValidity.kt`；`ui/PullbackPriceAlertContent.kt` 中每个已组合摘要独立读取自己的显示边界，父列表、候选投影、主警报选择和声音基线不随时间重建。相同整数、单位和下次边界复用同一悬挂任务；不足一分钟仅等到实际结束。跟随 `STARTED` 生命周期，进入后台取消，恢复即校正，摘要离开组合时释放。不新增请求、秒级轮询或固定频率整页重组。

## 圆角增量验证与交付

- `:app:testDebugUnitTest`：329 项通过，0 失败、0 错误、0 跳过；`:app:lintDebug` 通过，0 错误、46 条既有警告。Debug、AndroidTest 与启用 R8/资源收缩的 Release 均构建成功，日志 `build/rounded-build.log`。
- 模拟器恢复稳定后，5 项警报集成测试与 1 项计时生命周期测试全部通过，涵盖主警报、K 线导航、已触发保留、延期/撤销、单位显示和后台暂停。日志 `build/rounded-native-phone-final.log`。首次在模拟器冷启动期间运行出现进程退出；随后单独重试被 System UI 无响应弹窗遮挡，返回等待超时。这两次失败分别保留在 `rounded-native-phone.log` 和 `rounded-native-retry.log`，未计作通过；恢复系统界面后完整 6 项通过。
- 320dp / 150% 字体 / 系统日间、360dp / 150% 字体 / 系统夜间、960dp / 130% 字体各 1 项剩余时间场景通过；检查了原生主行、+N 面板、换行与纯文字已触发状态，沿用独立深色主题。截图在 `docs/assets/pullback-alert-rounded/`，日志为 `build/rounded-native-{narrow320,narrow360,wide960}.log`。均来自临时只读模拟器 `emulator-5582` 和设备内 MockWebServer，不代表真机或性能对比。
- 签名 Release 覆盖安装、冷启动与存活检查通过，未发现对应 AndroidRuntime 崩溃。沿用原有证书，v2/v3 签名及 16KiB 对齐验证通过。安装包 `dist/pullback-alert-rounded/GuailiAndroid-rounded-countdown.apk`，附 SHA-256、签名报告、截图清单与说明；入口仍为「行情 → 回调观察」。

## 原始剩余时间版本验证与交付

- 最终 `:app:testDebugUnitTest`：329 项通过，0 失败、0 错误、0 跳过。新增纯时间用例覆盖单位/整数切换、较早截止时间、无限延伸、未来起点、数据异常、触发保留和极端时间边界。
- `:app:lintDebug` 通过，0 错误、46 条既有警告；本次时间实现文件没有新增 lint 条目。Debug、AndroidTest 和启用 R8/资源收缩的 Release 构建均成功。
- 原生手机批测 12 项中 11 项通过；既有 `allControlsEvidenceCancellationKlinePersistenceAndDataStates` 在参数错误提示等待处超时，单独重跑通过。保留原批跑结果，不将其记为整批全绿。新计时生命周期用例以及 5 项警报集成用例全部通过，覆盖后台不读时间、恢复校正、同值不重启任务、不足一分钟不秒刷、主警报、K 线、已触发、延期核对/撤销及剩余时间。
- 单独的同一剩余时间原生场景在 320dp/150% 字体、360dp/150% 字体、960dp/130% 字体均通过；覆盖系统日间与夜间设置，应用继续使用独立深色主题。原生截图来自临时只读 Android 模拟器 `emulator-5580`，使用设备内 MockWebServer，截图为受控数据，不是线上行情或 Release 性能对比证据。
- 最终签名 Release 在该临时模拟器覆盖安装并启动成功，检查进程持续运行、无对应 AndroidRuntime 崩溃日志。证书沿用原有签名，v2/v3 验证与 16KiB 对齐检查通过。

日志位于 `build/alert-validity/`：`build-checks.log`、`native-phone-tests.log`、`native-control-retry.log`、`native-{narrow320,narrow360,wide960}.log`、`release-startup.log`。截图位于 `docs/assets/pullback-alert-validity/`，包含普通手机、窄屏大字体与宽屏的主行和 +N 面板。产品、设计和局部规范已同步。

安装包为 `dist/pullback-alert-validity/GuailiAndroid-alert-validity.apk`，附构建 manifest、签名报告和使用说明。保留已有包与设置；业务写入验证只使用设备内隔离接口。
