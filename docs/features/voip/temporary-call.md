# 临时通话

> 在真群资料页发起一次性假群语音通话，不创建会话

## 类别

音视频通话

## 类型

可点击

## 描述

在**真实群**的「聊天信息」页点「发起临时通话」，会打开成员选择页，
用假群 ID（真实群 ID 加一个 `@`，如 `123@chatroom` → `123@@chatroom`）发起语音通话。

特点：

- **不创建会话**：不打开聊天页，会话列表里不会多出假群；
- **不污染真群**：通话用假群 ID 发起，真实群里不会出现通话记录；
- **只能选成员**：假群本身不存在，通话对象是你在选成员页里勾选的真实联系人；
- **通话结束自动清理**：拨通时微信会自己往会话表写假群，挂断后模块会把它删干净。

## 使用方法

1. 进入任意真实群的「聊天信息」页；
2. 点「发起临时通话」；
3. 在选成员页勾选要邀请的人（默认已全选）；
4. 点「确定」发起通话，正常通话、手动挂断即可。

设置页里的「临时通话」入口也可以先选群再进选成员页。

## 实现要点

微信的选成员页是 `voipmp.v2.multitalk.ui.MultiTalkSelectContactUI`，它按
`chatroom.memberlist` 取成员。假群没有这条记录就会显示空列表，所以**打开真群资料页时**
就会把真实群的 `chatroom` 行（`memberlist` / `roomdata` / `memberCount`）临时复制给假群。
这一行只在数据库里，不进会话列表，也不影响真实群。

- 进页时把全部成员通过 `default_select_contact` 传进去，默认全选（可手动取消）；
- 微信默认最多选 15 人（`bz5.f.b()` 读 ABTest 配置），模块只在这个页面把它临时放开，
  实际能进多少人仍取决于服务端；
- `key_need_gallery = true` 必须带上，否则「确定」只会回传结果、不会真正发起（见文末）。

## 通话结束后清理假群

拨通时微信会用 `chatroomName`（假群 ID）写会话与联系人行，所以挂断后必须清理。

- 挂断点：`d06.p.u4(Continuation)`（实现类 `ry5.i3`，内部日志
  `[QuitGroupHangup] callEnd release flutter, clear currentTalkingWxGroupId`）。
  该方法是**抽象接口方法**，hook 必须挂到实现类上才会触发；
- 清理：`hideConversation`（缓存感知，列表立刻刷新）+ 删 11 张表——
  `message` / `ImgInfo2` / `MsgQuote` / `GroupBindApp` / `GroupSolitatire` /
  `GroupTodo` / `GroupTools` / `chatroom` / `rconversation` / `img_flag` / `rcontact`，
  表清单沿用原项目 `DeleteFakeGroups`；
- 启动兜底：每次微信启动 5 秒后扫一遍 `rconversation` 里所有 `%@@chatroom`，
  清掉旧版本遗留的假群。

## 已知瑕疵

选成员页标题显示 `null`（用真实群 ID 直接启动同样如此），不影响成员与通话。

## 为什么之前「点确定没有任何反应」

微信的选成员页用 intent 里的 `key_need_gallery` 决定点「确定」之后做什么：

```
H = getIntent().getBooleanExtra("key_need_gallery", false)
if (H)  d06.p.Oh(自己wxid, 成员列表, chatroomName); finish()   // 真正发起
else    setResult("Select_Contact", 已选人员); finish()        // 只回传给调用方
```

会话里点「+ → 语音通话」时，微信自己会带这个 extra；直接 `startActivity`
打开该页则默认 false，于是「点确定 → 页面关掉 → 什么都没发生」。
模块现在显式传 `key_need_gallery = true`。
