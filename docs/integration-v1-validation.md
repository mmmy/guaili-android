# 第一版整合验证

验证日期：2026-10-05。版本：Guaili 0.2.0（versionCode 2）。设备：现有 Android 16 / API 36 模拟器 `emulator-5554`。

## 结果

| 检查 | 结果 |
| --- | --- |
| Guaili 单元测试 | 207 项通过，无跳过、失败或错误 |
| XBot 模块单元测试 | 84 项通过，无跳过、失败或错误 |
| 两模块 Lint | 通过，0 个错误；保留现有非阻断警告 |
| Debug APK 与宿主测试 APK | 构建成功，已覆盖安装到现有模拟器 |
| 宿主整合 | 10 项业务检查通过 |
| TradingView 警报页面 | 8 项业务检查通过 |
| 原有警报组件 | 6 项业务检查通过 |
| 视觉检查 | 手机竖屏、横屏侧栏、1.3 倍字号各捕获 5 张实际模拟器截图；暗色与键盘状态已检查 |

24 项业务检查包括四入口、未登录行情浏览、登录/退出、两客户端认证隔离、迟到响应取消、账户缓存隔离、K线与警报组件跳转、重复组件定位、有效期、再设确认和处理中禁止重复业务操作。

视觉流程是 opt-in instrumentation，普通测试会跳过，不计入上述 24 项。原有警报测试中的「退出」断言已调整为新的「账户」入口：查看账户允许继续，退出登录在账户页按业务状态禁用；该检查已单独复验通过。

## 复现

```powershell
.\gradlew.bat :app:testDebugUnitTest :feature-xbot:testDebugUnitTest :app:lintDebug :feature-xbot:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class "com.gouge.guaili.integration.TradingAppIntegrationTest,com.gouge.xbot.ui.TvAlertScreenTest,com.gouge.xbot.widget.AlertWidgetIntegrationTest" com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
```

视觉捕获使用 `TradingAppIntegrationTest#visualSmokeScreenshots`，传入 `-e visual true`，可通过 `-e visualSuffix` 区分设备状态；PNG 写入应用缓存 `cache/integration-v1/`。本次捕获与日志已保存到 `build/reports/integration-v1/`。

业务套件日志为 `functional-tests.txt`，其中保留了旧账户按钮断言的首次失败；修正后的 `final-functional-recheck.txt` 记录宿主套件与该单项复验通过。`visual-phone.txt`、`visual-wide.txt`、`visual-font.txt` 记录三个显示场景通过，`visual-profiles.json` 记录原显示设置已恢复。

## 数据与验证边界

- 所有 XBot 登录、退出和警报业务请求均使用测试用 MockWebServer；没有向真实 XBot 后端创建、删除或再设警报。
- XBot 截图中的品种、信号和警报明确标为测试数据，时间按模拟器设备时钟生成。行情页使用当前已配置的行情服务。
- 临时 XBot 偏好使用不解密的完整备份并恢复；测试结束使临时会话失效，防止迟到请求覆盖恢复数据。原独立 XBot 工程及安装均未修改。
- 保留 Guaili applicationId、行情设置和已有乖离组件。安装前的配置备份位于 `build/device-backups/`，未清除或卸载用户应用。
- 字号、夜间模式、屏幕旋转与测试临时启用的键盘选项已恢复。
- 本次使用 Android 16 模拟器；Android 8–11 的兼容警报适配器保留并增加账户校验，未在旧版本设备上实测。
- 实际 XBot 服务的认证和业务兼容性需在「设置 → XBot 账户与连接」登录后使用；本次没有取得或使用真实账户凭据。两套后端继续独立运行。
