# 快速开始

基于 Xposed 的微信增强模块，为微信 **8.0.79** 提供 239 个功能开关（15 个分类），另有
43 个 API 内部服务不建卡。

## 需求

| 项目 | 要求 |
|---|---|
| 宿主包名 | `com.tencent.mm` 或以它开头（双开 / 分身） |
| 微信版本 | 国内版 **8.0.79**（arm64-v8a）；Dex 锚点、混淆名、原生库都按该版核对 |
| Android | >= 10（SDK 29） |
| Xposed API | 51 ~ 102（LSPosed 或免 Root 修补均可） |

其它微信版本即使能启动也会有功能失效，问题反馈请附版本号。

## 下载

本仓库不提供预编译包，按 [开发与构建](development.md) 自行编译：

```bash
./gradlew :app:assembleLegacyDebug
```

产物 `app/build/outputs/apk/legacy/debug/app-legacy-arm64-v8a-debug.apk`（arm64-v8a）。

## 安装

**Root（LSPosed）**

1. 安装模块 APK；
2. LSPosed 中启用模块并勾选微信 —— 提示「此模块是为较新的 Xposed 版本设计的」可忽略；
3. 重启微信。

**免 Root（NPatch）**

1. 安装模块与 NPatch 管理器；
2. 修补微信：包名保持 `com.tencent.mm` 开头，建议启用「注入文件提供器」；
   「集成模式」需在嵌入模块里勾选 WCX，「本地模式」需在作用域中启用 WCX；
3. 安装修补包 —— NPatch 可能请求卸载已安装的微信，注意不要丢数据。

## 模块不加载（微信热更新导致）

**Root**：打开桌面「知友」→ 主页「模块管理」卡 →「修复模块加载」→ 重启微信。
（同一个 App 里还有「清除模块数据」，用于开关错乱或闪退。）

**免 Root**（需注入文件提供器，以 MT 管理器为例）

1. 添加微信目录：`Android/data/<宿主包名>` → 使用此文件夹 → 允许；
2. 打开 `./data/`，清空所有 `tinker*` 文件夹内容；
3. 对每个文件夹：长按 → 属性 → 权限 → 取消全部勾选，勾选两个「同时应用到所有…」→ 确定；
4. 重启微信。目录显示为空时先启动一次微信再重试。

## 打开模块设置

四条入口见 [配置](configuration.md#入口)。

## 下一步

[配置](configuration.md) · [模块设置说明](module-settings.md) · [常见问题](faq.md) · [问题与建议](feedback.md)
