# 临时通话

**音视频通话** · 可点击 — 在真实群的「聊天信息」页发起一次性语音通话，不建会话、不在真群留记录。

## 用法

1. 进入任一真实群的「聊天信息」页 →「发起临时通话」；
2. 在选成员页勾选要邀请的人（默认全选，可手动取消）；
3. 点「确定」发起，正常通话、手动挂断即可。

设置页里的「临时通话」入口也可以先选群，再进选成员页。

## 行为

- **不创建会话**：不打开聊天页，会话列表里不会多出假群。
- **不污染真群**：通话用假群 ID 发起（真实群 ID 加一个 `@`，`123@chatroom` → `123@@chatroom`），真群不会出现通话记录。
- **成员来自真群**：假群本身不存在，可邀请的是你在选成员页勾选的真实联系人。
- **挂断后清理**：拨通时微信自己会往会话表写假群，挂断后模块把它删干净。

## 实现要点

- 选成员页（`voipmp.v2.multitalk.ui.MultiTalkSelectContactUI`）按 `chatroom.memberlist` 取成员，假群没有这行就是空列表。所以**打开真群资料页时**会把真实群的 `chatroom` 行（`memberlist` / `roomdata` / `memberCount`）临时复制给假群——这一行只在数据库里，不进会话列表，也不影响真群。
- 进页时通过 `default_select_contact` 把全部成员带进去并默认全选；微信默认最多 15 人（`bz5.f.b()` 读 ABTest），模块只在该页临时放开，实际人数仍取决于服务端。
- 必须带 `key_need_gallery = true`：该页按这个 extra 决定点「确定」是真正发起（`d06.p.Oh`）还是只回传结果（`setResult`）。直接 `startActivity` 打开默认是 false，于是「点确定 → 页面关掉 → 什么都没发生」。
- 挂断点：`d06.p.u4(Continuation)`（实现类 `ry5.i3`）。它是抽象接口方法，hook 必须挂到实现类上才会触发。
- 清理：`hideConversation`（缓存感知，列表立刻刷新）+ 删 11 张表——`message` / `ImgInfo2` / `MsgQuote` / `GroupBindApp` / `GroupSolitatire` / `GroupTodo` / `GroupTools` / `chatroom` / `rconversation` / `img_flag` / `rcontact`。
- 启动兜底：每次微信启动 5 秒后扫一遍 `rconversation` 里所有 `%@@chatroom`，清掉旧版本遗留的假群。

## 已知瑕疵

选成员页标题显示 `null`（用真实群 ID 直接启动同样如此），不影响成员选择与通话。
