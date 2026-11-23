# 知友 文档

> 与当前版本对齐：**239 个功能开关 / 15 个分类 / 设置页四 Tab**（主页 · AI · 日志 · 设置），微信 8.0.79。

## 使用

| 文档 | 内容 |
|---|---|
| [快速开始](getting-started.md) | 环境要求、安装（Root / 免 Root）、模块不加载的修复 |
| [配置](configuration.md) | 四个入口、数据目录、配置存储位置 |
| [模块设置](module-settings.md) | 主页三张卡、分类半屏、AI 页三段、设置页六组、日志页 |
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

只有 5 个功能需要展开，单独成页：

| 功能 | 为什么单独写 |
|---|---|
| [临时通话](features/voip/temporary-call.md) | 假群 ID、成员选择页、挂断后清理的数据表 |
| [防撤回](features/chat/anti-message-recall.md) | 徽标 / 旧版两种展示样式与 6 个设置项 |
| [隐藏模块应用](features/system/hide-module-from-app-list.md) | 三个 PackageManager 入口与实验性限制 |
| [跳过启动页面](features/miniapps/skip-splash.md) | 锚点定位方式与失效条件 |
| [恢复旧版公众号列表](features/official_accounts/use-legacy-official-accounts-view.md) | 重定向的两条路径与不可用版本 |

## 代码在哪

```
app/src/main/java/com/AARCNoa/zhiyou/
├── activity/      模块 App 主页（MainActivity）+ 设置页（settings/、settings/ai/）
├── ai/            AI 好友档案 + MessageRendering（媒体占位）；ai/group/ 是群总结
├── features/      api（微信 API）/ core（框架）/ items（功能实现）
├── dexkit/        DexKit 委托、缓存
├── loader/        入口与启动链
├── ui/            style（配色令牌）+ content（公共组件）
└── utils/         日志、崩溃、反射、序列化
```

APK 里 `微信 → 我 → 设置 → 知友` 打开的就是这里的 `SettingsActivity`；模块 App（桌面图标）
是另一个入口，只做激活状态与模块管理。
