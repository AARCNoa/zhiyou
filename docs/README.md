# 知友 文档

> 与当前版本对齐：**239 个功能开关 / 15 个分类 / 设置页四 Tab**（主页 · AI · 日志 · 设置），微信 8.0.80。

## 使用

| 文档 | 内容 |
|---|---|
| [快速开始](getting-started.md) | 环境要求、安装（Root / 免 Root）、模块不加载的修复 |
| [配置](configuration.md) | 四个入口、数据目录、配置存储位置 |
| [模块设置](module-settings.md) | 主页三张卡、分类半屏、AI 页三段、设置页七组、日志页 |
| [常见问题](faq.md) | 卡顿、DEX 适配、数据库解密、功能失效 |
| [问题与建议](feedback.md) | 提 Issue 需要哪些材料 |
| [免责声明](disclaimer.md) | 使用条款 |

## 开发

| 文档 | 内容 |
|---|---|
| [开发与构建](development.md) | 环境、变体、版本与签名、装机脚本 |

## 功能参考

[全部功能](features/README.md) 是一份 **239 条**的清单，按设置页的 15 个分类排列：
一行一个功能，名称后的「（可点击）」表示有二级配置页。绝大多数功能这一行就是全部说明。

少数功能需要展开，单独成页：

- [临时通话](features/voip/temporary-call.md) · [防撤回](features/chat/anti-message-recall.md)
- [朋友圈帮我评论](features/ai/moments-comment.md) · [总览](features/ai/overview.md)
- [应用内更新](features/system/app-update.md) · [隐藏模块应用](features/system/hide-module-from-app-list.md)
- [跳过启动页面](features/miniapps/skip-splash.md) · [恢复旧版公众号列表](features/official_accounts/use-legacy-official-accounts-view.md)

## 代码在哪

```
app/src/main/java/com/AARCNoa/zhiyou/
├── activity/      模块 App 主页（MainActivity）+ 设置页（settings/、settings/ai/）
├── ai/            AI 好友档案、今日总结与总览；ai/group/ 是群摘要与工作汇报
├── features/      api（微信 API）/ core（框架）/ items（功能实现）/ items/ai（总览入口）
├── update/        版本清单、多源检查、下载线路探测
├── dexkit/        DexKit 委托、缓存
├── loader/        入口与启动链
├── ui/            style（配色令牌）+ content（公共组件）
└── utils/         日志、崩溃、反射、序列化、图片压缩
```

`微信 → 我 → 设置 → 知友` 打开的是这里的 `SettingsActivity`；桌面「知友」是另一个入口，
只做激活状态与模块管理。
