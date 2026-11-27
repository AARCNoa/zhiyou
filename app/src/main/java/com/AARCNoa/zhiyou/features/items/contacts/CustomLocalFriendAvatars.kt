package com.AARCNoa.zhiyou.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.SpinnerAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.fields
import dev.ujhhgtg.reflekt.firstField
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import com.AARCNoa.zhiyou.activity.TransparentActivity
import com.AARCNoa.zhiyou.constants.PackageNames
import com.AARCNoa.zhiyou.dexkit.DexMethodDescriptor
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.DexMethodDelegate
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.api.core.WeDatabaseApi
import com.AARCNoa.zhiyou.features.api.core.models.IWeContact
import com.AARCNoa.zhiyou.features.api.ui.WeContactPrefsScreenApi
import com.AARCNoa.zhiyou.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import com.AARCNoa.zhiyou.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.preferences.WePrefs.Companion.prefOption
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.BaseContactSelector
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.currentWxId
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.reflection.BString
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import com.AARCNoa.zhiyou.utils.reflection.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.min

@Feature(
    name = "自定义好友本地头像", categories = ["联系人与群组"],
    description = "为指定联系人或群组使用本地图片替换显示的头像"
)
object CustomLocalFriendAvatars : ClickableFeature(), IContactInfoProvider, IResolveDex {

    private const val PREF_KEY = "custom_avatar"
    private const val SEP = ";"
    private const val VIEW_TAG_CUSTOM_AVATAR = 0x57434156

    private const val TAG = "CustomLocalFriendAvatars"
    private val avatarMapFile = KnownPaths.moduleData / "custom_avatars_map.json"

    // ji1.s.og, most of com.tencent.mm.feature.avatar.w calls this,
    // e.g. Cg, ig, cg, og, rg
    private val methodMvvmLoadAvatar1 by dexMethod(allowFailure = true) {
        // 8.0.80 起 "MicroMsg.AvatarGetContactServiceHelper" 已成孤儿串（无 const-string 引用）。
        // 只保留仍存活的 "put stack into pool: "，配合原有的 4 参签名
        // (ImageView, String, String, float) → void：
        // 8.0.76 = avatar.w.kj / 8.0.79 = avatar.w.Aj / 8.0.80 = avatar.w.Gj，三版均唯一命中。
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "java.lang.String",
                "float"
            )
            returnType(Void.TYPE)
            usingEqStrings("put stack into pool: ")
        }
    }

    // ji1.s.pg: another exception
    private val methodMvvmLoadAvatar2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AvatarGetContactServiceHelper", "put stack into pool: ")
            }

            usingEqStrings("imageView")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
            )
            returnType(Void.TYPE)
            usingNumbers(30000)
        }
    }

    private val classAvatarDrawable by dexClass {
        searchPackages("com.tencent.mm.feature.avatar")
        matcher {
            usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null", "?access_token=")
        }
    }

    // com.tencent.mm.feature.avatar.w.pg; an exception: this doesn't call methodMvvmLoadAvatar
    // 跨版本说明：8.0.76 唯一命中 AvatarDrawable.lj(ImageView,String)V（内部调 View.invalidate）；
    // 8.0.79 已移除该 invalidate 调用，同形状方法变成两个（Bj / yj），旧 addInvoke 条件导致 0 命中。
    // 因此去掉 addInvoke，改用 allowMultiple 把该类所有 (ImageView,String)V 候选都 hook
    // （头像替换是幂等覆盖，多点 hook 无副作用，且能覆盖聊天/通讯录/朋友圈不同入口）。
    private val methodFeatureAvatarSimple1 by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            declaredClass(classAvatarDrawable.clazz)
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String"
            )
            returnType(Void.TYPE)
        }
    }

    private val methodPluginsdkLoadAvatar by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.pluginsdk.ui")
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String"
            )
            returnType(Void.TYPE)
            usingEqStrings("MicroMsg.AvatarDrawable")
        }
    }

    private val methodHdGallerySetUsername by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView"
            name = "setUsername"
            paramTypes("java.lang.String")
            returnType(Void.TYPE)
        }
    }

    private val methodRoundBitmap by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            paramTypes("android.graphics.Bitmap", "boolean", "float")
            returnType = "android.graphics.Bitmap"

            addInvoke {
                usingEqStrings("MicroMsg.BitmapUtil", "getRoundedCornerBitmap in bitmap is null")
            }
        }
    }

    // com.tencent.mm.pluginsdk.ui.u.b
    private val methodConversationAvatar by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui")
        matcher {
            usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "float",
                "boolean"
            )
            returnType = "void"

            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
            }
        }
    }

    @Volatile
    private var avatarMapCache: Map<String, String>? = null

    /** 头像渲染 hook 是否已安装（两个功能共用，只装一次）。 */
    @Volatile
    private var avatarHooksInstalled = false

    /** 对话归拢是否启用了文件夹头像（由 ConversationAggregation 设置）。 */
    @Volatile
    private var folderAvatarActive = false

    /** 判断某个 username 是否是对话归拢的文件夹 id。 */
    @Volatile
    private var folderIdPredicate: ((String) -> Boolean)? = null

    /**
     * 对话归拢启用/停用时调用。
     *
     * 传入 folder 判断后，头像 hook 就能区分「文件夹头像」和「好友头像」：
     * 文件夹 id 只受 [folderAvatarActive] 控制，普通 wxId 只受本功能开关控制。
     */
    fun setFolderAvatarSupport(active: Boolean, isFolderId: ((String) -> Boolean)?) {
        folderAvatarActive = active
        folderIdPredicate = isFolderId
    }

    /** 当前 wxId 是否应该走本模块的头像渲染（按归属分别判断，两个功能互不干扰）。 */
    private fun shouldApplyAvatar(wxId: String): Boolean {
        return if (folderIdPredicate?.invoke(wxId) == true) {
            folderAvatarActive
        } else {
            isEnabled
        }
    }

    @Volatile
    var fallbackUsernameProvider: ((String) -> String?)? = null

    private val roundedBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val originalBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val boundAvatarViews = Collections.synchronizedMap(WeakHashMap<ImageView, BoundAvatar>())

    private lateinit var hdGalleryUsernameField: Field
    private lateinit var hdGalleryThumbBitmapField: Field
    private lateinit var hdGalleryHdBitmapField: Field
    private lateinit var hdGalleryLoadedField: Field
    private lateinit var hdGalleryAdapterField: Field
    private lateinit var hdGallerySetAdapterMethod: Method

    var avatarMap: Map<String, String>
        get() = avatarMapCache ?: loadAvatarMap().also { avatarMapCache = it }
        set(value) {
            val normalized = value
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() }
                .filterKeys { it.isNotEmpty() }
                .filterValues { it.isNotEmpty() }
            avatarMapCache = normalized
            saveAvatarMap(normalized)
        }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)

        // 清除重启可能残留的缓存状态，避免旧缓存导致头像加载异常
        clearBitmapCaches()
        boundAvatarViews.clear()

        ensureAvatarHooks()
        WeLogger.i(TAG, "CustomLocalFriendAvatars enabled, avatarMap size=${avatarMap.size}")
    }

    /**
     * 安装头像渲染 hook（幂等）。
     *
     * 这些 hook 不只服务「自定义好友本地头像」：对话归拢的文件夹头像复用同一套渲染链路。
     * 因此安装与功能开关解耦，由两个功能任一启用时调用；具体某个 wxId 是否应用头像，
     * 在 [shouldApplyAvatar] 里按归属分别判断，互不干扰。
     */
    @Synchronized
    fun ensureAvatarHooks() {
        if (avatarHooksInstalled) return
        avatarHooksInstalled = true

        val hookTargets = listOf(
            "methodConversationAvatar" to methodConversationAvatar,
            "methodMvvmLoadAvatar1" to methodMvvmLoadAvatar1,
            "methodMvvmLoadAvatar2" to methodMvvmLoadAvatar2,
            "methodFeatureAvatarSimple1" to methodFeatureAvatarSimple1,
            "methodPluginsdkLoadAvatar" to methodPluginsdkLoadAvatar
        )

        for ((name, delegate) in hookTargets) {
            if (delegate.isPlaceholder) {
                WeLogger.w(TAG, "skipping hook $name: method not found (placeholder) in current WeChat version")
                continue
            }

            // allowMultiple 的委托（如 methodFeatureAvatarSimple1）会把同签名的方法全部返回，
            // 统一遍历候选逐个 hook，避免只挂上第一个导致部分入口漏改头像。
            // 缓存命中时委托只能按描述符回退一条，这里再按该类枚举同签名方法补回其余入口。
            val targets = if (name == "methodFeatureAvatarSimple1") {
                expandSameSignature(delegate)
            } else {
                delegate.candidateMethods()
            }
            if (targets.isEmpty()) {
                WeLogger.w(TAG, "skipping hook $name: no candidate method resolved")
                continue
            }
            for (target in targets) {
                try {
                    target.hookBefore {
                        val imageView = args.getOrNull(0) as? ImageView ?: return@hookBefore
                        val wxId = args.getOrNull(1) as? String ?: return@hookBefore

                        // 文件夹头像归「对话归拢」管，普通好友头像归本功能管：
                        // 只开其中一个时，另一个不会跟着生效。
                        if (!shouldApplyAvatar(wxId)) return@hookBefore

                        val redirectedId = fallbackUsernameProvider?.invoke(wxId)
                        if (redirectedId != null) {
                            args[1] = redirectedId
                            return@hookBefore
                        }

                        if (applyCustomAvatar(imageView, wxId, roundAvatarRadiusFactor)) {
                            try {
                                // 仅当原方法返回 void 时才设置 result = null
                                if (method is java.lang.reflect.Method) {
                                    val returnType = (method as java.lang.reflect.Method).returnType
                                    if (returnType == Void.TYPE) {
                                        result = null
                                    }
                                }
                            } catch (e: Throwable) {
                                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                            }
                        }
                    }
                } catch (e: Exception) {
                    WeLogger.w(TAG, "failed to hook $name on ${target.name}, avatar loading may be affected", e)
                }
            }
            WeLogger.d(TAG, "hook $name set up on ${targets.size} target(s)")
        }

        if (!methodHdGallerySetUsername.isPlaceholder) {
            try {
                methodHdGallerySetUsername.hookBefore {
                    val username = args.getOrNull(0) as? String ?: return@hookBefore
                    if (!shouldApplyAvatar(username)) return@hookBefore
                    val gallery = thisObject
                    if (applyCustomHdAvatar(gallery, username)) {
                        try {
                            // 仅当原方法返回 void 时才设置 result = null
                            if (method is java.lang.reflect.Method) {
                                val returnType = (method as java.lang.reflect.Method).returnType
                                if (returnType == Void.TYPE) {
                                    result = null
                                }
                            }
                        } catch (e: Throwable) {
                            // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                        }
                        (gallery as? View)?.let { view ->
                            view.post { applyCustomHdAvatar(gallery, username) }
                            view.postDelayed({ applyCustomHdAvatar(gallery, username) }, 300L)
                        }
                    }
                }
                WeLogger.d(TAG, "hook methodHdGallerySetUsername set up successfully")
            } catch (e: Exception) {
                WeLogger.w(TAG, "failed to hook methodHdGallerySetUsername", e)
            }
        } else {
            WeLogger.w(TAG, "skipping hook methodHdGallerySetUsername: method not found (placeholder)")
        }

        WeLogger.i(TAG, "avatar hooks installed (shared by local avatars / folder avatars)")
    }

    /**
     * 取出该类里所有同签名的方法。
     *
     * `methodFeatureAvatarSimple1` 的匹配条件就是「AvatarDrawable 类里所有 (ImageView,String)V」，
     * 不同版本会有一到多个（8.0.76 一个、8.0.79 两个）。DexKit 缓存的 key 只存选中的那一条，
     * 二次启动时 [candidateMethods] 只能回退一条，所以这里按描述符的类再枚举一次补齐。
     */
    private fun expandSameSignature(delegate: DexMethodDelegate): List<Method> {
        val base = delegate.candidateMethods()
        val descriptor = delegate.getDescriptorString() ?: return base
        return runCatching {
            val desc = DexMethodDescriptor(descriptor)
            val className = desc.declaringClass
                .removePrefix("L")
                .removeSuffix(";")
                .replace('/', '.')
            val methods = ClassLoaders.HOST.loadClass(className).declaredMethods
                .filter { DexMethodDescriptor.getMethodTypeSig(it) == desc.signature }
            (base + methods).distinctBy { it.name + DexMethodDescriptor.getMethodTypeSig(it) }
        }.getOrDefault(base)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
        avatarMapCache = null
        clearBitmapCaches()
        boundAvatarViews.clear()
        WeLogger.i(TAG, "CustomLocalFriendAvatars disabled, caches cleared")
    }

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId ?: run {
            // 资料页入口消失时，先看是「没取到会话 id」还是「条目没被注入」。
            WeLogger.d(TAG, "contact prefs: no currentWxId on ${activity.javaClass.simpleName}")
            return emptyList()
        }
        val hasCustomAvatar = avatarMap.containsKey(wxId)
        WeLogger.d(TAG, "contact prefs item for $wxId, custom=$hasCustomAvatar")
        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = if (hasCustomAvatar) "更换自定义头像" else "添加自定义头像",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val wxId = activity.currentWxId ?: return true

        WeLogger.i(TAG, "profile entry clicked for $wxId, custom=${avatarMap.containsKey(wxId)}")
        if (avatarMap.containsKey(wxId)) {
            showContactAvatarDialog(activity, wxId)
        } else {
            selectAvatarImage(activity, wxId)
        }

        return true
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            // 好友 + 群可能有几千条，主线程直接查库会让对话框迟迟弹不出来（看起来像没反应）。
            var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }
            LaunchedEffect(Unit) {
                contacts = withContext(Dispatchers.IO) { loadContacts() }
                WeLogger.i(TAG, "avatar manager loaded ${contacts.size} contacts")
            }
            CustomAvatarManagerDialog(
                contacts = contacts,
                entries = avatarMap,
                onDismiss = onDismiss,
                onSelectImage = { wxId ->
                    onDismiss()
                    selectAvatarImage(context, wxId)
                },
                onRemove = { wxId ->
                    removeAvatar(wxId)
                    showToast("已清除自定义头像")
                    onDismiss()
                }
            )
        }
    }

    private var roundAvatarRadiusFactor by prefOption("custom_avatar_round_radius", 0.5f)

    private fun effectiveRadiusFactor(loaderRadiusFactor: Float): Float {
        return if (RoundAvatars.isEnabled) roundAvatarRadiusFactor else loaderRadiusFactor
    }

    private fun applyCustomAvatar(imageView: ImageView, username: String, radiusFactor: Float): Boolean {
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val effectiveRadiusFactor = effectiveRadiusFactor(radiusFactor)
        val tag = "$username$SEP$uri$SEP$effectiveRadiusFactor"
        imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
        boundAvatarViews[imageView] = BoundAvatar(username, uri, radiusFactor)

        // 首次加载：若 bitmap 解码失败（如 content provider 尚未就绪），让原始方法先跑，
        // 避免 ImageView 空白；post 回调会做二次尝试覆盖。
        val firstAttemptOk = loadAvatarInto(imageView, uri, effectiveRadiusFactor)
        imageView.post {
            if (imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag) {
                loadAvatarInto(imageView, uri, effectiveRadiusFactor)
            }
        }

        if (!firstAttemptOk) {
            WeLogger.d(TAG, "first loadAvatarInto attempt failed for $username, allowing original method as fallback")
        }
        return firstAttemptOk
    }

    /**
     * 加载自定义头像到 ImageView。
     * @return true 表示成功设置了自定义头像 bitmap，false 表示需要回退到原始加载逻辑。
     */
    private fun loadAvatarInto(imageView: ImageView, uri: String, radiusFactor: Float): Boolean {
        val targetSize = imageView.width
            .takeIf { it > 0 }
            ?: imageView.layoutParams?.width?.takeIf { it > 0 }
            ?: 156

        val shouldRound = RoundAvatars.isEnabled
        val bitmap = decodeAvatarBitmap(
            uri = uri,
            targetSize = targetSize,
            round = shouldRound,
            radiusFactor = if (shouldRound) radiusFactor else 0f
        )

        if (bitmap == null) {
            WeLogger.d(TAG, "decodeAvatarBitmap failed for $uri, falling back to Coil")
            imageView.load(uri) {
                allowHardware(false)
                crossfade(false)
            }
            return false
        }

        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setImageDrawable(bitmap.toDrawable(imageView.resources))
        imageView.invalidate()
        return true
    }

    private fun applyCustomHdAvatar(gallery: Any?, username: String): Boolean {
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val view = gallery as? View ?: return false
        val width = view.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val bitmap = decodeAvatarBitmap(uri, width, round = false, radiusFactor = 0f) ?: return false

        runCatching {
            ensureReflection()

            hdGalleryUsernameField.set(gallery, username)
            hdGalleryThumbBitmapField.set(gallery, bitmap)
            hdGalleryHdBitmapField.set(gallery, bitmap)
            hdGalleryLoadedField.setBoolean(gallery, true)
            @Suppress("UNCHECKED_CAST")
            val adapter = hdGalleryAdapterField.get(gallery) as? SpinnerAdapter
            if (adapter is BaseAdapter) {
                adapter.notifyDataSetChanged()
            } else if (adapter != null) {
                hdGallerySetAdapterMethod.invoke(gallery, adapter)
            }
            view.invalidate()
        }.onFailure {
            WeLogger.e(TAG, "failed to apply custom HD avatar for $username", it)
            return false
        }
        return true
    }

    private fun decodeAvatarBitmap(uri: String, targetSize: Int, round: Boolean, radiusFactor: Float): Bitmap? {
        val cacheKey = "$uri|$targetSize|$round|$radiusFactor"
        val cache = if (round) roundedBitmapCache else originalBitmapCache
        cache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }

        val bitmap = runCatching {
            HostInfo.application.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null

        val cropped = centerCrop(bitmap, targetSize, targetSize)
        if (cropped !== bitmap && !bitmap.isRecycled) bitmap.recycle()

        val result = if (round) roundBitmap(cropped, radiusFactor) else cropped
        if (round && result !== cropped && !cropped.isRecycled) cropped.recycle()

        cache[cacheKey] = result
        trimBitmapCache(cache)
        return result
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height
        if (srcWidth <= 0 || srcHeight <= 0) return source

        val srcRatio = srcWidth.toFloat() / srcHeight
        val dstRatio = width.toFloat() / height
        val rect = if (srcRatio > dstRatio) {
            val cropWidth = (srcHeight * dstRatio).toInt().coerceAtLeast(1)
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            val cropHeight = (srcWidth / dstRatio).toInt().coerceAtLeast(1)
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }

        return createBitmap(width, height).also { out ->
            Canvas(out).drawBitmap(source, rect, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            })
        }
    }

    private fun roundBitmap(source: Bitmap, radiusFactor: Float): Bitmap {
        val radius = (min(source.width, source.height) * radiusFactor).coerceAtLeast(0f)
        roundBitmapWithWeChat(source, radius)?.let { return it }
        return roundBitmapFallback(source, radius)
    }

    private fun roundBitmapWithWeChat(source: Bitmap, radius: Float): Bitmap? {
        return runCatching {
            methodRoundBitmap.method.invoke(null, source, false, radius) as? Bitmap?
        }.getOrNull()
    }

    private fun roundBitmapFallback(source: Bitmap, radius: Float): Bitmap {
        val out = createBitmap(source.width, source.height)
        val rect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            isFilterBitmap = true
            color = -0x3f3f40
        }

        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        Canvas(out).apply {
            drawARGB(0, 0, 0, 0)
            drawPath(path, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawBitmap(source, 0f, 0f, paint)
            paint.xfermode = null
        }
        return out
    }

    private fun trimBitmapCache(cache: ConcurrentHashMap<String, Bitmap>) {
        if (cache.size <= 24) return
        cache.keys.take(cache.size - 24).forEach { key -> cache.remove(key) }
    }

    private fun showContactAvatarDialog(context: Context, wxId: String) {
        val displayName = WeDatabaseApi.getDisplayName(wxId)
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("自定义头像") },
                text = {
                    DefaultColumn {
                        Text(displayName)
                        Text(text = wxId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            removeAvatar(wxId)
                            showToast("已清除自定义头像, 重新进入页面后生效")
                            onDismiss()
                        }) { Text("清除") }
                        Button(onClick = {
                            onDismiss()
                            selectAvatarImage(context, wxId)
                        }) { Text("更换") }
                    }
                }
            )
        }
    }

    fun selectAvatarImage(context: Context, wxId: String) {
        WeLogger.i(TAG, "opening image picker for $wxId")
        runCatching {
            TransparentActivity.launch(context) {
                WeLogger.i(TAG, "picker activity created for $wxId")
                val launcher = registerForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    finish()
                    if (uri == null) {
                        WeLogger.i(TAG, "image picker cancelled for $wxId")
                        return@registerForActivityResult
                    }
                    persistReadPermission(uri)
                    setAvatar(wxId, uri.toString())
                    showToast("自定义头像已设置, 重新进入页面或重启微信后生效")
                }
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
        }.onFailure {
            // 宿主是微信进程：显式 Activity 启动被拦、或相册没有可用 Activity 时都会走到这里。
            WeLogger.e(TAG, "failed to open image picker for $wxId", it)
            showToast("无法打开相册：${it.javaClass.simpleName}")
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            HostInfo.application.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { WeLogger.w(TAG, "failed to persist avatar uri permission: $uri", it) }
    }

    private fun setAvatar(wxId: String, uri: String) {
        avatarMap = avatarMap + (wxId to uri)
        clearBitmapCaches()
        WeLogger.i(TAG, "saved custom avatar for $wxId, map size=${avatarMap.size}")
    }

    fun removeAvatar(wxId: String) {
        avatarMap = avatarMap - wxId
        clearBitmapCaches()
    }

    private fun clearBitmapCaches() {
        roundedBitmapCache.clear()
        originalBitmapCache.clear()
    }

    fun onRoundAvatarConfigChanged() {
        clearBitmapCaches()
        boundAvatarViews.entries.toList().forEach { (imageView, binding) ->
            if (avatarMap[binding.username] != binding.uri) return@forEach
            val radiusFactor = effectiveRadiusFactor(binding.loaderRadiusFactor)
            val tag = "${binding.username}$SEP${binding.uri}$SEP$radiusFactor"
            imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
            loadAvatarInto(imageView, binding.uri, radiusFactor)
            imageView.post {
                if (imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag) {
                    loadAvatarInto(imageView, binding.uri, radiusFactor)
                }
            }
        }
    }

    private fun ensureReflection() {
        if (::hdGalleryUsernameField.isInitialized) return
        val galleryClass = "${PackageNames.WECHAT}.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView".toClass()
        val mutableBitmapFields = galleryClass.fields {
            type = Bitmap::class.java
            modifiers { !it.contains(Modifiers.FINAL) }
        }
        hdGalleryThumbBitmapField = mutableBitmapFields[0].self
        hdGalleryHdBitmapField = mutableBitmapFields[1].self
        hdGalleryUsernameField = galleryClass.firstField {
            type = BString
            modifiers { !it.contains(Modifiers.FINAL) }
        }.self.makeAccessible()
        hdGalleryLoadedField = galleryClass.firstField { type = bool }.self.makeAccessible()
        hdGalleryAdapterField = galleryClass.firstField { type { it isSubclassOf SpinnerAdapter::class } }.self.makeAccessible()
        hdGallerySetAdapterMethod = galleryClass.firstMethod {
            name = "setAdapter"
            parameters(SpinnerAdapter::class)
        }.self.makeAccessible()
    }

    private fun loadAvatarMap(): Map<String, String> {
        if (!avatarMapFile.exists()) {
            WeLogger.d(TAG, "avatar map file not found, returning empty map")
            return emptyMap()
        }
        return runCatching {
            val raw = avatarMapFile.readText()
            val map = Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw).filter { it.key.isNotBlank() && it.value.isNotBlank() }
            WeLogger.i(TAG, "loaded avatar map: ${map.size} entries from ${avatarMapFile.absolutePathString()}")
            map
        }.getOrElse {
            WeLogger.e(TAG, "failed to parse custom avatar map from ${avatarMapFile.absolutePathString()}", it)
            emptyMap()
        }
    }

    private fun saveAvatarMap(value: Map<String, String>) {
        runCatching {
            val raw = Json.encodeToString(value)
            avatarMapFile.writeText(raw)
        }.onFailure { WeLogger.e(TAG, "failed to save custom avatar map", it) }
    }

    private fun loadContacts(): List<IWeContact> {
        return runCatching {
            (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()).sortedBy { it.displayName.ifBlank { it.wxId } }
        }.getOrElse {
            WeLogger.e(TAG, "failed to load contacts for custom avatar manager", it)
            emptyList()
        }
    }

    @Composable
    private fun CustomAvatarManagerDialog(
        contacts: List<IWeContact>,
        entries: Map<String, String>,
        onDismiss: () -> Unit,
        onSelectImage: (String) -> Unit,
        onRemove: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }

        val fullContactsList = remember(contacts, entries) {
            val entryContacts = entries.keys.map { wxId ->
                contacts.firstOrNull { it.wxId == wxId } ?: SimpleContact(wxId, WeDatabaseApi.getDisplayName(wxId))
            }
            (entryContacts + contacts).distinctBy { it.wxId }
        }

        val filteredContacts = remember(searchQuery, fullContactsList, chinaCollator) {
            fullContactsList.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.wxId.contains(searchQuery, ignoreCase = true)
            }.sortedWith(
                compareBy<IWeContact> { it.displayName.isBlank() }
                    .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
            )
        }

        BaseContactSelector(
            title = "自定义好友本地头像",
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filteredContacts = filteredContacts,
            confirmButtonText = "",
            confirmButtonEnabled = false,
            showConfirmButton = false,
            dismissButtonText = "关闭",
            onDismiss = onDismiss,
            onConfirm = {},
            selectionKey = entries,
            isSelected = { it.wxId in entries.keys },
            avatarModelProvider = { contact -> entries[contact.wxId] ?: contact.avatarUrl },
            subtitleProvider = { contact ->
                if (contact.wxId in entries.keys) "已设置 - ${contact.wxId}" else contact.wxId
            },
            trailingControl = { contact ->
                if (contact.wxId in entries.keys) {
                    TextButton(onClick = { onRemove(contact.wxId) }) { Text("清除") }
                } else {
                    TextButton(onClick = { onSelectImage(contact.wxId) }) { Text("选择") }
                }
            },
            onItemClick = { contact -> onSelectImage(contact.wxId) }
        )
    }

    private data class SimpleContact(
        override val wxId: String,
        override val nickname: String
    ) : IWeContact {
        override val displayName: String get() = nickname
        override val avatarUrl: String get() = ""
    }

    private data class BoundAvatar(
        val username: String,
        val uri: String,
        val loaderRadiusFactor: Float
    )
}
