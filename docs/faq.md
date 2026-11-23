# 常见问题

## 1. 模块不加载，日志也没有报错

按 [快速开始 · 模块不加载](getting-started.md#模块不加载微信热更新导致) 处理（微信热更新留下的 `tinker` 目录）。

## 2. 微信突然很卡、狂吃内存

先关掉「系统与隐私 / 禁止微信检测 Xposed」与「隐藏模块应用」再观察。若装了未压缩的 debug 包，
dex 会被 LSPosed 整包映射进每个进程，换 release（R8）包可显著降低占用。

## 3. 模块数据在哪？

`/sdcard/Android/data/<宿主包名>/WCX/`（`logs/` `crashes/` `dex_cache/` `assets/` `stickers/`），
配置在 `/data/data/<宿主包名>/files/mmkv/wekit_prefs`。详见 [配置](configuration.md#数据目录)。

## 4. 启动一直提示 DEX 适配 / 功能失效

旧版本的「DEX 适配」弹窗已移除，现在改为**后台静默重扫 + 一条 toast**。若某功能仍失效：

1. 「设置 → 兼容 → 重置适配信息」→ 重启微信，让它重新扫锚点；
2. 确认微信是 8.0.79（唯一适配版本）；
3. 仍不行则看 [自检](#8-模块出现问题找不到入口功能失效怎么办) 报告里的具体条目。

## 5. 「聊天 / 发送卡片消息」在哪里？

已合并进「聊天输入栏增强」，在 [全部功能](features/README.md) 的「聊天」一节里。

## 6. LSPosed 提示「此模块是为较新的 Xposed 版本设计的」

忽略即可，模块支持 Xposed API 51 ~ 102。

## 7. 模块出现问题（找不到入口、功能失效）怎么办？

先跑主页「模块已激活」卡右侧的「自检」，未通过项会给出原因；再按 [问题与建议](feedback.md) 提交 Issue。

## 8. 如何解密微信数据库？

1. 开启「设置 → 调试 → 详细日志」并重启微信；
2. 日志中找 `WeDatabaseApi: openDatabase() called with: name=…, password=…`；
3. 用 `sqlcipher` 逐行执行（不要一次贴多行）：

```bash
sqlcipher ./EnMicroMsg.db
PRAGMA key = 'xxxxxxx';
PRAGMA cipher_compatibility = 1;
ATTACH DATABASE 'decrypted_wechat.db' AS decrypted KEY '';
SELECT sqlcipher_export('decrypted');
DETACH DATABASE decrypted;
.exit
```

4. 用 DB Browser for SQLite 打开 `decrypted_wechat.db`。

## 9. 「指纹支付」在分身微信里崩溃

ROM 的应用分身与指纹不兼容，不修复。

## 10. 频繁提示「无法连接到服务器 (1,-15)」

这是**微信自己**的网络提示：模块没有这句文案，也没有任何功能 hook 网络层。按顺序排查系统省电 /
智能省流量 / 后台联网限制、VPN 与 DNS 过滤（含「私人 DNS」）、WiFi 与移动数据并存、微信服务端波动。
要排除模块因素，先关闭「系统与隐私 / 省电模式」（它拦唤醒锁，熄屏时可能保不住长连接），
再临时关「环境伪装」与「阻止 Native 检测」，或在 LSPosed 取消勾选微信对比。

## 11. 某功能开了没反应

微信会在版本更新里直接删掉模块，这类失效无法修。模块的处理：开关提示「当前微信版本不支持」，
自检报告标为不可用 / 不适用并说明影响区间。已知删除（实测 8.0.71 ~ 8.0.79）：

| 微信版本 | 微信侧变化 | 受影响功能 |
|---|---|---|
| 8.0.77 起 | 删除多人通话模块（MultiTalk / ILink） | 群通话拆分、通话小窗多人通话、密友里的通话隐藏 |
| 8.0.78 起 | 删除表情面板 `setSelection: %s` | 美化 · 表情面板 Tab 图标 |


