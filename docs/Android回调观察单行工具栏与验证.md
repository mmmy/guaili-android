# Android 回调观察单行工具栏

2026-10-12，按用户确认的一行工具栏原型实现，版本 0.4.1 / versionCode 5。入口仍为「行情 → 回调观察」。

工具栏固定为一行，依次为「级数、候选、声音、刷新」，高度 56dp。级数打开原有参数面板，保存后立即反映新级数；完整 N/T/K 及高级参数保留，取消不保存。移除重复的观察标题和常驻规则说明，把空间留给级别列表。

候选与声音采用可直接切换的原生 IconToggleButton。开启时以 primary 的 16% 淡色底层、primary 文字及勾选／发声图标表达，关闭时恢复次级色。两项仍分别保存，保留完整无障碍名称和开关状态；声音去重与静默基线沿用既有逻辑。

各控件至少 48dp 触摸区域、相邻间距 8dp。窄屏和大字体保持一行，宽度／fontScale 小于 220dp 时只省略参数入口的 Tune 图标，保留级数及下拉标记。行情状态与计数属于列表信息，不增加第二行操作栏。多空级别、候选层次、依据取消、真实行情和 K 线行为保持原有语义。

## 验证

app 单元测试 272 项全部通过，0 失败、0 错误、0 跳过；Lint 0 错误、45 项既有警告。Release 构建、APK 对齐和 v2／v3 签名校验通过，已覆盖安装到 API 36.1 模拟器。

最终原生集成测试共 6 项执行全部通过：手机 3 项（36.701 秒），360dp／150% 字体 1 项（31.191 秒），320dp／150% 字体 1 项（32.602 秒），宽屏／130% 字体 1 项（31.005 秒）。直接开启／关闭两项开关、筛选恢复隐藏品种、级数修改及 Activity 重建通过；四个操作同一行、至少 48dp 触摸区域、边界无重叠通过。取消选中、刷新不复选、原品种周期 K 线、返回位置、参数校验及空／过期／网络错误流程继续覆盖，真实部署服务器完整周期证据检查通过。

保留 33 张最终原生截图及尺寸／SHA-256 清单于 [.impeccable/review/pullback-toolbar/qa.json](../.impeccable/review/pullback-toolbar/qa.json)，其中 8 张作为文档截图。日志位于 `build/pullback-toolbar-{phone,narrow,compact,wide}.log`，构建日志为 `build/pullback-toolbar-build.log`。测试后恢复偏好、行情快照及模拟器显示／字体／日夜配置。

宽屏通过同一模拟器的 Android 显示配置模拟，不代表物理平板实测。系统日夜模式均沿用应用原有独立深色主题。320dp／150% 字体下，原有上方行情分页标签会换行；本次四项工具栏保持一行，分页布局未改动。

## 截图

以下为真实原生 Release 页面，除真实行情截图外，均使用确定性测试行情验证布局与交互。

- [手机默认工具栏](assets/pullback-toolbar/phone-all.png)
- [候选开关开启](assets/pullback-toolbar/phone-candidates.png)
- [候选、声音均开启，修改级数已保存](assets/pullback-toolbar/phone-toolbar-on.png)
- [点击级数打开参数](assets/pullback-toolbar/phone-parameters.png)
- [360dp / 150% 字体](assets/pullback-toolbar/narrow-all.png)
- [320dp / 150% 字体](assets/pullback-toolbar/compact-all.png)
- [宽屏 / 130% 字体](assets/pullback-toolbar/wide-all.png)
- [只读部署服务器真实行情](assets/pullback-toolbar/phone-real-market.png)

## 安装包

[GuailiAndroid-v0.4.1.apk](../dist/pullback-one-row/GuailiAndroid-v0.4.1.apk) 为沿用本机开发证书签名的 Release 构建，可覆盖同证书 `com.gouge.guaili` 并保留设置；尚非生产证书签名。声音是前台回调页提醒，本次模拟器无音频输出，不声称已验证真机听感。

长期约定已同步至 PRODUCT.md、DESIGN.md、AGENTS.md 和 [.impeccable/surfaces/pullback-observation.md](../.impeccable/surfaces/pullback-observation.md)。原业务判定与完整验证说明见 [回调观察实现与验证](Android回调观察实现与验证.md)。
