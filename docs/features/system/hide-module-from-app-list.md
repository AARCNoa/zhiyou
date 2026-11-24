# 隐藏模块应用

**系统与隐私** · 可开关 · 实验性 — 防止微信查询模块的安装状态。

Hook 微信进程内的 `ApplicationPackageManager`，让它被查询时「看不到」模块包（`com.AARCNoa.zhiyou`）：

- `queryIntentActivities` 的结果里剔除模块条目；
- `getPackageInfo(String, …)` / `getApplicationInfo(String, …)` 查询模块包名时抛 `NameNotFoundException`。

三个入口一起处理，避免只拦一处、换个 API 又查得到。

## 说明

- 启用即生效，不需要重启微信。要验证可在开关前后用微信内的应用列表类页面或第三方检测工具对比。
- **实验性**：只拦了 `String` 参数的重载，个别 ROM / 微信版本走其它重载可能绕过。
- 不注销模块自身组件，LSPosed 仍能正常加载模块，设置页照常可开。
- 与「禁止微信检测 Xposed」互补：前者隐藏框架，本功能隐藏模块 APK。
