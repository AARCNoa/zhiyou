package com.AARCNoa.zhiyou.features.items.contacts.hidecontacts

import android.app.Service
import android.content.Intent
import com.tencent.mm.plugin.voip.widget.VoipForegroundService
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.features.items.contacts.HideContacts
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.BString

private const val TAG = "HideContacts.Voip"

/**
 * Hides — and optionally auto-rejects — calls from hidden contacts.
 *
 * ## Which stack actually runs on 8.0.7x
 *
 * A 1:1 call is delivered entirely through the VoIPMP / ILink native core:
 *
 * ```
 * push (hp5.m.L0 / hp5.t.yi)
 *   -> qq5.j.b9(...)                 mp5/q2.java
 *   -> voipmp.platform.v0.z(...)     -> native
 *   -> ZIDL_ibmKH7hbMB.ZIDL_FBV(...) "launchInComingCardAsync"
 *   -> qq5.j.qa(ctx, ..., username, members, isSubCall)   mp5/q2.java
 *   -> coroutine, which forks:
 *        background -> full-screen/heads-up notification (id 41)
 *        foreground -> float card + qq5.j.Ri -> ringtone
 * ```
 *
 * The legacy v2protocal RUDP stack (`MicroMsg.Voip.VoipServiceEx` / `VoipIncomingCallManager`) is
 * only reached when the *peer* falls back to the old protocol. Auto-reject used to be implemented
 * solely against that stack, which is why it never did anything for a normal 8.0.7x call: the
 * hooks simply never fired, so no rejection packet was ever sent. Those hooks are kept below purely
 * as a legacy fallback.
 *
 * ## Ordering constraint (this is the subtle part)
 *
 * `v0.f()` — the native hangup that a rejection ultimately reaches — can only reject a room the
 * native core already knows about. Inside `ZIDL_FBV` the call order is:
 *
 * ```
 * jVar.qa(...)            <- banner/notification/ring dispatch happens FIRST
 * r0.f371853w = 2         <- ...then status
 * r0.f371854x/f371855y    <- ...roomId / roomKey
 * r0.f371852v = true
 * f3.f371733a.y2(...)     <- ...and finally the ZIDL completion ack owed back to native
 * ```
 *
 * So a rejection issued from inside the `qa` hook would run before any of that state exists. Worse,
 * the previous implementation cancelled `ZIDL_FBV` outright with `hookBefore { result = null }`,
 * which skipped both the state writes *and* the ack — leaving CoreV2 half-initialised with
 * `r0.p()` false, so every later rejection attempt was a no-op and the caller just rang until the
 * 60 s timeout.
 *
 * Hence: suppress the UI in [installVoipMpHooks] (via `qa`, the ringtone and the FGS), let
 * `ZIDL_FBV` run to completion, and only then — from its `hookAfter` — invoke `q2.Qa()`, the same
 * "rejectByShortCut" entry WeChat's own Bluetooth quick-reject uses.
 */
internal fun HideContacts.installVoipHooks() {
    if (!surfaceOn(SURFACE_VOIP)) return
    installVoipMpHooks()
    installVoipRecordHooks()
    installLegacyVoipHooks()
}

/**
 * The `mp5.q2` (VoIPMP launcher) instance, captured from a hooked instance method so we can invoke
 * `Qa()` on it later. WeChat itself obtains it via `pa5.n0.c(qq5.j.class)`; capturing `thisObject`
 * from a call we already intercept avoids having to resolve that plugin-lookup machinery.
 */
@Volatile
private var voipMpLauncher: Any? = null

private fun HideContacts.installVoipMpHooks() {
    // Banner + notification + ringtone dispatch. Cancelling this one call suppresses the whole
    // incoming-call presentation, because both forks of the coroutine it starts live behind it.
    methodVoipMpLaunchBanner.hookBefore {
        voipMpLauncher = thisObject
        val wxId = args.getOrNull(5) as? String ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        WeLogger.i(TAG, "suppressing incoming-call banner from $wxId")
        result = null
    }

    // Runs after CoreV2's state writes and the ZIDL ack, which is the earliest point a rejection
    // can actually reach the caller. NB: hookAfter, never hookBefore — see the KDoc above.
    methodVoipMpLaunchIncomingCard.hookAfter {
        val rawWxId = args.getOrNull(5) as? ByteArray ?: return@hookAfter
        val wxId = String(rawWxId)
        if (!isHiddenNow(wxId)) return@hookAfter
        if (!autoRejectVoipEnabled) {
            // Hide-only: the presentation is already suppressed, the call is simply left to time
            // out on the caller's side exactly as an unanswered call would.
            return@hookAfter
        }
        rejectVoipMpCall(wxId)
    }

    // Ringtone. The old implementation hooked "MicroMsg.RingPlayer" / "playSound, type: ..." which
    // is the call-ENDED tone (its data source is k0.b("playend")), not the incoming ring — so a
    // hidden contact's call still rang. This is the real chokepoint, and it carries the wxid.
    methodVoipMpStartRing.hookBefore {
        val wxId = args.getOrNull(0) as? String ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        WeLogger.i(TAG, "suppressing ringtone for $wxId")
        result = null
    }

    // Foreground service. Suppressing it here rather than letting VoipForegroundService start and
    // then calling stopSelf() avoids a ForegroundServiceDidNotStartInTimeException on Android 12+.
    methodVoipMpStartFgs.hookBefore {
        val wxId = args.getOrNull(0) as? String ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        result = null
    }
}

/** Invokes `mp5.q2.Qa()` — "rejectByShortCut". */
private fun rejectVoipMpCall(wxId: String) {
    val launcher = voipMpLauncher
    if (launcher == null) {
        WeLogger.w(TAG, "no VoIPMP launcher captured; cannot auto-reject call from $wxId")
        return
    }
    if (HideContacts.methodVoipMpReject.isPlaceholder) {
        WeLogger.w(TAG, "rejectByShortCut wasn't resolved; cannot auto-reject call from $wxId")
        return
    }
    WeLogger.i(TAG, "auto-rejecting VoIPMP call from $wxId")
    // Qa() dispatches the actual hangup onto a background coroutine, so this does not block the
    // ZIDL callback thread we are on.
    runCatching { HideContacts.methodVoipMpReject.method.invoke(launcher) }
        .onFailure { WeLogger.w(TAG, "rejectByShortCut failed for $wxId", it) }
}

/**
 * Call-record messages ("未接听" / "已取消" / duration bubbles).
 *
 * 8.0.79 里 VoIPMP 与 legacy ILink 两条插入路径**汇到同一个方法**：
 * `ry5.i3.Ii(String toUser, boolean, int roomType, long, long, long, int)`（即
 * `VoIPMPBubbleHelper`），方法内部按 roomType 分支，分别打 `insertMsg() called with: roomType = `
 * 与 `insertILinkVoIPMsg() called with: voipInfo = `。所以挂这一个点就覆盖两条路径，
 * [HideContacts.methodVoipMpInsertMsg] 已指向它；旧版另挂的 `b2.d` 8 参 matcher 在 8.0.79
 * 已不存在（`b2` 只剩构造 callagain 链接的 `b(String,String,Z,J,I)J`），故删除。
 */
private fun HideContacts.installVoipRecordHooks() {
    // 两条插入路径共用的入口（见上方 KDoc），args[0] 就是 toUser。
    methodVoipMpInsertMsg.hookBefore {
        voipMpLauncher = thisObject
        val wxId = args.getOrNull(0) as? String ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        result = null
    }

    // Server-pushed <voipmsg> bubble (msg type 50). Returns an object, so cancelling with null is
    // the same as "nothing was inserted", which is exactly how WeChat treats a parse failure.
    methodVoipBubbleHandle.hookBefore {
        val addMsg = args.getOrNull(1) ?: return@hookBefore
        val talker = runCatching {
            addMsg.reflekt().fields { type = BString }.firstNotNullOfOrNull { field ->
                (field.get() as? String)?.takeIf { isHiddenNow(it) }
            }
        }.getOrNull() ?: return@hookBefore
        WeLogger.i(TAG, "suppressing pushed voip bubble from $talker")
        result = null
    }
}

/**
 * Legacy v2protocal stack. Dead for a normal 8.0.7x-to-8.0.7x call, but still reached when the peer
 * negotiates down to the old protocol, so these stay as a fallback.
 */
private fun HideContacts.installLegacyVoipHooks() {
    // Incoming float card. Shared by BOTH stacks (the legacy path bridges into the same nr4.y.x),
    // so this one is genuinely live on 8.0.76 too.
    methodVoipShowFloatingCard.hookBefore {
        val wxId = args.getOrNull(5) as? String ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        result = null
    }

    // The "已拒绝通话" banner that WeChat raises AFTER a rejection. It lives on the same manager as
    // the incoming card but is a separate method, so suppressing the incoming card leaves it
    // visible — which defeats the point of hiding when auto-reject is on.
    if (methodVoipShowFinishCard.isPlaceholder) {
        WeLogger.w(TAG, "showFinishCard wasn't resolved; the post-reject banner will stay visible")
    } else {
        methodVoipShowFinishCard.hookBefore {
            val wxId = args.getOrNull(1) as? String ?: return@hookBefore
            if (!isHiddenNow(wxId)) return@hookBefore
            WeLogger.i(TAG, "suppressing post-reject banner for $wxId")
            result = null
        }
    }

    // n.a(b57) returns boolean and c0.A(b57) returns void, so they cannot share a hook body: the
    // hook bridge performs no primitive coercion and `result = null` would unbox null.
    methodVoipAcceptIncomingCall.hookBefore {
        val callerWxId = legacyCallerWxId() ?: return@hookBefore
        if (!isHiddenNow(callerWxId)) return@hookBefore
        result = false
    }

    methodVoipStartAcceptVoip.hookBefore {
        val callerWxId = legacyCallerWxId() ?: return@hookBefore
        if (!isHiddenNow(callerWxId)) return@hookBefore
        result = null
    }

    // VoipServiceEx.reject() needs status==3 and roomId!=0, both written by setInviteContent. So
    // when auto-rejecting we must let setInviteContent run and only reject afterwards; when merely
    // hiding we cancel it up front so no state is established and no packet goes out.
    methodVoipServiceExSetInviteContent.hookBefore {
        val wxId = legacyCallerWxId() ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        if (!autoRejectVoipEnabled) result = false
    }

    methodVoipServiceExSetInviteContent.hookAfter {
        if (!autoRejectVoipEnabled) return@hookAfter
        val wxId = legacyCallerWxId() ?: return@hookAfter
        if (!isHiddenNow(wxId)) return@hookAfter
        WeLogger.i(TAG, "auto-rejecting legacy call from $wxId")
        runCatching { methodVoipServiceExReject.method.invoke(thisObject) }
            .onFailure { WeLogger.w(TAG, "legacy reject failed for $wxId", it) }
    }

    // Fallback for the foreground service, in case it is started through a path that bypasses
    // xp5.b.d. VoipNewForegroundService extends this and does not override onStartCommand.
    VoipForegroundService::class.reflekt().firstMethod { name = "onStartCommand" }.hookBefore {
        val self = thisObject as VoipForegroundService
        val intent = args.getOrNull(0) as? Intent ?: return@hookBefore
        val wxId = intent.getStringExtra("Voip_User") ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore
        self.stopSelf()
        result = Service.START_NOT_STICKY
    }
}

/**
 * Reads the caller wxid out of an `a65.b57` RoomInfo, which declares exactly one String field
 * (`f3634i` = callerUserName).
 */
private fun de.robv.android.xposed.XC_MethodHook.MethodHookParam.legacyCallerWxId(): String? =
    runCatching { args[0]!!.reflekt().firstField { type = BString }.get() as? String }.getOrNull()
