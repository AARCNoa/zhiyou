# 隐藏模块应用

> 在不影响模块功能的情况下防止微信查询模块安装状态 (实验性)

## 类别

系统与隐私

## 类型

可开关

## 描述

Hook 微信进程内的 `ApplicationPackageManager`，让它在被查询时「看不到」模块包
（`com.AARCNoa.zhiyou`）：

- `queryIntentActivities` 的结果里剔除模块条目；
- `getPackageInfo(String, …)` 查询模块包名时抛 `NameNotFoundException`；
- `getApplicationInfo(String, …)` 同上。

三个入口一起处理，避免只拦一处、换个 API 又查得到。

## 使用方法

启用即生效，不需要重启微信。想验证是否生效，可在开启前后用微信内的「应用列表」类页面
或第三方检测工具对比。

## 说明

- **实验性**：只拦了 `String` 参数的重载，个别 ROM / 微信版本若走其它重载可能绕过；
- 不会注销模块自身组件，LSPosed 仍能正常加载模块，模块设置也照常可开；
- 与「禁止微信检测 Xposed」互补：前者隐藏 Xposed 框架，本功能隐藏模块 APK。
