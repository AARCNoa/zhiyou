package com.AARCNoa.zhiyou.features.api.core

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tencent.mm.api.IEmojiInfo
import com.tencent.mm.opensdk.modelmsg.WXFileObject
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage
import com.tencent.mm.opensdk.modelmsg.WXMiniProgramObject
import com.tencent.mm.opensdk.modelmsg.WXMusicObject
import com.tencent.mm.opensdk.modelmsg.WXMusicVideoObject
import com.tencent.mm.opensdk.modelmsg.WXTextObject
import com.tencent.mm.opensdk.modelmsg.WXVideoObject
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject
import com.tencent.mm.plugin.gif.MMWXGFJNI
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.spec.VagueType
import dev.ujhhgtg.reflekt.spec.typeMatches
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import com.AARCNoa.zhiyou.constants.WeChatVersions
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexConstructor
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.api.core.WeMessageApi.cacheFile
import com.AARCNoa.zhiyou.features.api.core.WeMessageApi.downloadFile
import com.AARCNoa.zhiyou.features.api.core.models.MessageInfo
import com.AARCNoa.zhiyou.features.api.net.WeNetSceneApi
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.AudioUtils
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.collections.emptyHashSet
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.asPath
import com.AARCNoa.zhiyou.utils.reflection.BBool
import com.AARCNoa.zhiyou.utils.reflection.BInt
import com.AARCNoa.zhiyou.utils.reflection.BString
import com.AARCNoa.zhiyou.utils.reflection.bool
import com.AARCNoa.zhiyou.utils.reflection.int
import com.AARCNoa.zhiyou.utils.reflection.long
import com.AARCNoa.zhiyou.utils.reflection.void
import com.AARCNoa.zhiyou.utils.serialization.JsonToXmlConverter
import com.AARCNoa.zhiyou.utils.serialization.XmlUtils.extractXmlAttr
import com.AARCNoa.zhiyou.utils.serialization.XmlUtils.extractXmlTag
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.random.Random


@SuppressLint("DiscouragedApi")
@Feature(name = "消息发送服务", categories = ["API"], description = "提供文本、图片、文件、语音消息发送能力")
object WeMessageApi : ApiFeature(), IResolveDex {

    /**
     * 8.0.67+ 发图改走 `ImgUploadFeatureServiceSendImage`，这个老构造器只在更早版本使用；
     * 解析不到属于预期，不影响发图（`methodImgUploadFeatureServiceSendImage` 已正常解析）。
     */
    override val supersededAnchors: Map<String, String> = mapOf(
        "WeMessageApi:ctorNetSceneUploadMsgImg" to "新版发图走 FeatureService，该构造器仅老版本使用",
    )

    // -------------------------------------------------------------------------------------
    // 基础消息类
    // -------------------------------------------------------------------------------------
    val classNetSceneSendMsg by dexClass {
        matcher {
            methods {
                // 微信 8.0.79+ 把 "MicroMsg.NetSceneSendMsg" 从 J(I)V 挪到了 doScene，
                // 原来「paramCount=1 且两串同方法」的组合在 8.0.79/8.0.80 上不再成立，
                // 导致整个类定位失败 -> sendText/复读/抢红包自动回复统一返回 false。
                // 首选锚点改为 doScene + 两串同时存在（8.0.80 实测唯一命中 Lq61/s0）；
                // 旧版形态保留作兜底。
                add {
                    name = "doScene"
                    usingEqStrings("MicroMsg.NetSceneSendMsg", "Req MsgSource %s")
                }
                add {
                    paramCount = 1
                    usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                }
            }
        }
    }
    private val classNetSceneQueue by dexClass {
        searchPackages("com.tencent.mm.modelbase")
        matcher {
            methods {
                add {
                    paramCount = 2
                    usingStrings("worker thread has not been set", "MicroMsg.NetSceneQueue")
                }
            }
        }
    }
    val classNetSceneBase by dexClass {
        matcher {
            usingEqStrings("scene security verification not passed, type=")
        }
    }
    private val classNetSceneObserverOwner by dexClass {
        matcher {
            methods {
                add {
                    paramCount = 4
                    usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                }
            }
        }
    }

    // hi0.j1::d() is identical to xv0.a9::e()
    val methodGetSendMsgObject by dexMethod(allowMultiple = true) {
        matcher {
            paramCount = 0
            returnType = classNetSceneObserverOwner.getDescriptorString() ?: ""
            modifiers(AccessFlagsMatcher(Modifier.STATIC))
        }
    }
    private val methodPostToQueue by dexMethod {
        searchPackages("com.tencent.mm.modelbase")
        matcher {
            declaredClass = classNetSceneQueue.getDescriptorString() ?: ""
            paramTypes(classNetSceneBase.getDescriptorString() ?: "")
            returnType = "boolean"
            usingNumbers(0)
        }
    }

    private val classPatMsgExtension by dexClass {
        matcher {
            usingEqStrings("MicroMsg.PatMsgExtension", "insert pat msg %d %s %s")
        }
    }
    private val ctorNetSceneSendPat by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneSendPat")
            paramCount(4)
        }
    }
    private val ctorNetSceneRevokeMsg by dexConstructor {
        searchPackages("com.tencent.mm.modelsimple")
        matcher {
            usingEqStrings("MicroMsg.NetSceneRevokeMsg")
            paramCount(3)
        }
    }
    private val ctorNetSceneSendMsgLocation by dexConstructor {
        matcher {
            usingEqStrings("MicroMsg.NetSceneSendMsg", "[mergeMsgSource] rawSource:%s args is null:%s flag:%s")
        }
    }
    /**
     * ImportMultiVideo（多视频发送）。
     *
     * 8.0.79 实测：旧特征串 `MicroMsg.GetVideoMetadata` **会同时命中两个类** ——
     * - `com.tencent.mm.pluginsdk.model.f3`：视频转码参数类（15 个字段含 VideoTransPara / ABAPrams）
     * - `mp.b`：Uri / 缩略图工具类（0 字段，只有 2 个静态方法）
     * 原实现要求唯一命中，DexKit 直接抛「Multiple classes found」。
     *
     * 现在三处改动：
     * ① matcher 换成 ImportMultiVideo 自己的日志 TAG + 它写进媒体元数据的两个键名
     *    （RemuxInfo / OriginMediaInfo）—— 三串同属一个类，唯一性远高于旧串；
     * ② `allowMultiple` / `allowFailure` 双开，万一未来版本又命中多个也不再抛异常；
     * ③ 真正使用前还会用 [resolveImportMultiVideoCtor] 按方法/字段签名再校验一次，
     *    校验不通过就把该版本标记为「不支持多视频发送」，跳过功能而不影响其它 Dex 解析。
     */
    private val classImportMultiVideo by dexClass(
        allowMultiple = true,
        allowFailure = true,
    ) {
        // 串有效性复核（8.0.76 / 8.0.79 双版本逐串验证）：
        // "RemuxInfo"、"OriginMediaInfo" 在两版 dex 里都只剩 string_ids 条目、**无任何 const-string 引用**
        // （微信裁剪了对应日志调用），三串 AND 因此恒不命中。只保留仍被引用的 TAG：
        // 8.0.76 → pluginsdk.model.b3 / app.u5 …，8.0.79 → pluginsdk.model.g3 / c71.d3 …
        // 多命中交给 resolveImportMultiVideoCtor() 按结构（Thread 子类 + 7 参构造器）择优。
        matcher {
            usingEqStrings("MicroMsg.ImportMultiVideo")
        }
    }

    /** 是否已做过 ImportMultiVideo 定位（无论成功失败，只做一次）。 */
    @Volatile
    private var multiVideoResolved = false

    /** 校验通过的 ImportMultiVideo 构造器；null = 该版本不支持。 */
    @Volatile
    private var multiVideoCtor: java.lang.reflect.Constructor<*>? = null

    /**
     * 多候选择优 + 签名校验，返回可用的构造器。
     *
     * 每个候选必须同时满足：
     * 1. 是 [Thread] 子类（微信以 Thread 形式跑转码任务）
     * 2. 存在 7 参构造器 `(Context, List, ?, String, int, ?, Boolean)`
     *
     * 全部候选都不满足 → 返回 null（调用方跳过功能）。全程 try-catch，
     * 任何异常都降级为「不支持」，绝不向上抛、绝不阻断其它 Dex 解析。
     */
    private fun resolveImportMultiVideoCtor(): java.lang.reflect.Constructor<*>? {
        multiVideoCtor?.let { return it }
        if (multiVideoResolved) return null
        multiVideoResolved = true
        return try {
            // 候选清单：当前已选中的那个排最前，其余来自 DexKit 的全部命中
            val names = buildList {
                runCatching { classImportMultiVideo.getDescriptorString() }
                    .getOrNull()?.let { add(it) }
                runCatching { classImportMultiVideo.candidates }
                    .getOrDefault(emptyList())
                    .forEach { c -> if (c.name !in this) add(c.name) }
            }
            WeLogger.i(TAG, "ImportMultiVideo 候选类(${names.size})：${names.joinToString().ifEmpty { "无" }}")
            for (name in names) {
                val cls = runCatching { name.toClass() }.getOrNull()
                if (cls == null) {
                    WeLogger.w(TAG, "ImportMultiVideo 候选 $name 加载失败 → 排除")
                    continue
                }
                val fieldTypes = runCatching { cls.declaredFields.map { it.type.name } }
                    .getOrDefault(emptyList())
                val isThread = Thread::class.java.isAssignableFrom(cls)
                val ctor = runCatching {
                    cls.declaredConstructors.firstOrNull { c ->
                        c.parameterCount == 7 &&
                            c.parameterTypes[0].name == "android.content.Context" &&
                            java.util.List::class.java.isAssignableFrom(c.parameterTypes[1]) &&
                            c.parameterTypes[3].name == "java.lang.String" &&
                            (c.parameterTypes[4].name == "int" || c.parameterTypes[4].name == "java.lang.Integer") &&
                            (c.parameterTypes[6].name == "boolean" || c.parameterTypes[6].name == "java.lang.Boolean")
                    }
                }.getOrNull()
                val hasVideoPara = fieldTypes.any {
                    it.contains("VideoTransPara") || it.contains("ABAPrams")
                }
                val ok = isThread && ctor != null
                WeLogger.i(
                    TAG,
                    "ImportMultiVideo 校验 $name：Thread=$isThread 七参构造器=${ctor != null} " +
                        "视频参数字段=$hasVideoPara 字段数=${fieldTypes.size} → ${if (ok) "选中" else "排除"}"
                )
                if (ok && ctor != null) {
                    ctor.isAccessible = true
                    multiVideoCtor = ctor
                    return ctor
                }
            }
            WeLogger.w(
                TAG,
                "ImportMultiVideo 全部候选校验不通过 → 该微信版本标记为不支持多视频发送" +
                    "（功能跳过，其它 Dex 解析与功能加载不受影响）"
            )
            null
        } catch (t: Throwable) {
            WeLogger.e(TAG, "ImportMultiVideo 定位异常（已降级为不支持该功能）", t)
            null
        }
    }
    private val classAppMessage by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AppMessage", "parse amessage xml failed")
        }
    }
    val methodSendAppMsg by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            usingEqStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath[%s], content[%s]")
        }
    }
    private val methodShareFile by dexMethod {
        matcher {
            paramTypes(
                "com.tencent.mm.opensdk.modelmsg.WXMediaMessage",
                "java.lang.String",
                "java.lang.String",
                "java.lang.String",
                "int",
                "java.lang.String"
            )
        }
    }
    val classMsgInfo by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfo", "[parseNewXmlSysMsg]")
        }
    }
    val classMsgInfoStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
        }
    }
    val methodMsgInfoHandleApiInsertMessage by dexMethod {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.MsgInfoStorage", "protect:c2c msg should not here")
        }
    }
    val methodMsgInfoStorageInsertMessage by dexMethod {
        matcher {
            declaredClass(classMsgInfoStorage.clazz)
            usingEqStrings("MsgInfo processAddMsg insert db error")
        }
    }
    val classChattingContext by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
        }
    }
    val classChattingDataAdapter by dexClass(allowFailure = true) {
        // 定位修正（8.0.76 / 8.0.79 双版本逐串验证）：
        //  · 原两串 "[handleMsgChange] isLockNotify:" 在两版都已是**孤儿串**（无 const-string 引用），
        //    任何依赖于它的组合都恒失败；
        //  · TAG "MicroMsg.ChattingDataAdapterV3" 仍存活，但 8.0.79 的 adapter 包内有 3 个类引用它
        //    （k / j / ChattingDataAdapter$chatMsgChange$1），单串会多命中。
        // 现在用「TAG + [setShowHistoryMsgTipId] 日志」两串方法级 AND：
        // 8.0.76 唯一命中 adapter.k（V0），8.0.79 唯一命中 adapter.k（i1）。
        // 使用方仍需 isPlaceholder 守卫（RemoveMessageSelectionLimit 内已处理）。
        searchPackages("com.tencent.mm.ui.chatting.adapter")
        matcher {
            usingEqStrings("MicroMsg.ChattingDataAdapterV3", "[setShowHistoryMsgTipId] pos:%s msgId:%s")
        }
    }
    val classTransformChattingComponent by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingEqStrings("MicroMsg.TransformComponent", "[onChattingPause]")
        }
    }
    val methodGetIsTransformed by dexMethod {
        matcher {
            declaredClass(classMsgInfo.clazz)
            usingNumbers(64, 0)
            usingFields {
                add {
                    type = "int"
                }
            }
            returnType = "boolean"
        }
    }

    // -------------------------------------------------------------------------------------
    // 图片发送组件
    // -------------------------------------------------------------------------------------
    private val classMvvmBase by dexClass {
        matcher {
            usingStrings(
                "MicroMsg.Mvvm.MvvmPlugin",
                "onAccountInitialized start"
            )
        }
    }
    private val classImageSender by dexClass()
    private val classImageTask by dexClass(allowFailure = true) {
        matcher { usingStrings("msg_raw_img_send") }
    }
    private val classConfigLogic by dexClass {
        matcher {
            usingEqStrings(
                "MicroMsg.ConfigStorageLogic",
                "get userinfo fail"
            )
        }
    }
    private val classImageServiceImpl by dexClass(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
            superClass(classMvvmBase.getDescriptorString()!!)
        }
    }
    private val methodImageSendEntry by dexMethod()

    // -------------------------------------------------------------------------------------
    // 语音发送组件
    // -------------------------------------------------------------------------------------
    private val classVoiceParams by dexClass {
        matcher {
            usingEqStrings("toUserName", "fileName", "send_voice_msg")
        }
    }
    private val classVoiceNameGen by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
        }
    }
    private val classVfs by dexClass {
        matcher {
            usingStrings("MicroMsg.VFSFileOp", "Cannot resolve path or URI: ")
        }
    }
    private val classPathUtil by dexClass {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            methods {
                add {
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    returnType = "java.lang.String"
                    paramTypes(
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "java.lang.String",
                        "int"
                    )
                }
            }
        }
    }
    private val classMmKernel by dexClass {
        matcher {
            usingStrings("MicroMsg.MMKernel", "Initialize skeleton, create whole world.")
        }
    }
    private val methodMmKernelGetStorage by dexMethod(allowMultiple = true) {
        matcher {
            declaredClass(classMmKernel.clazz)
            modifiers = Modifier.PUBLIC or Modifier.STATIC
            paramCount = 0
            // "mCoreStorage not initialized!" 自 8.0.76 起已是孤儿串（无 const-string 引用），
            // 换用同一方法里仍存活的 "Kernel not initialized by MMApplication!"：
            // 8.0.76 hm0/j1.i、8.0.79 pp0/i1.j、8.0.80 zp0/j1.j，三版均唯一命中（paramCount=0 + public static）。
            usingStrings("Kernel not initialized by MMApplication!")
        }
    }
    private val classVoiceLogic by dexClass {
        matcher {
            usingEqStrings("MicroMsg.VoiceLogic", "startRecord insert voicestg success")
        }
    }
    private val methodGetAmrFullPath by dexMethod {
        matcher {
            usingEqStrings("getAmrFullPath cost: ")
        }
    }
    private val methodStartRecvAndSend by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.SceneVoiceService", "Start Recv[%s] :%s", "Start Send :")
        }
    }
    private val classSceneVoiceService by dexClass {
        matcher {
            usingEqStrings("getVoiceService %s")
        }
    }

    // SceneVoiceService.run() — 在服务自身的 looper 线程上派发，避免在 IO 线程直接调用
    // startRecvAndSend 时构造 Handler 抛出 "looper and serial is null!"
    private val methodRunVoiceService by dexMethod(allowFailure = true) {
        matcher {
            paramCount = 0
            usingEqStrings("MicroMsg.SceneVoiceService", "run() %s")
        }
    }

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------

    // 基础 & 文本
    private val getSelfAliasMethod: Method? by lazy {
        try {
            classConfigLogic.reflekt()
                .firstMethod {
                    modifiers(Modifiers.STATIC)
                    parameterCount = 0
                    returnType = String::class
                }.self
        } catch (e: Exception) {
            WeLogger.w(TAG, "getSelfAliasMethod: failed to resolve via signature matching, selfCustomWxId will be empty", e)
            null
        }
    }

    // 图片
    private val imageServiceApiClass: Class<*> by lazy {
        classImageServiceImpl.clazz.interfaces.first {
            !it.isBuiltin
        }
    }
    private val sendImageMethod: Method by lazy {
        classImageServiceImpl.clazz.declaredMethods.first { m ->
            m.parameterCount == 1 &&
                    m.parameterTypes[0] == classImageTask.clazz &&
                    m.returnType.name.contains("flow", ignoreCase = true)
        }
    }
    private val taskConstructor: Constructor<*> by lazy {
        classImageTask.clazz.reflekt()
            .firstConstructor { parameterCount = 5 }
            .self
    }
    private val crossParamsClass: Class<*> by lazy { taskConstructor.parameterTypes[4] }

    // 语音 & VFS
    private lateinit var vfsCopyMethod: Method         // VFS.L (write)
    private lateinit var vfsReadMethod: Method         // VFS.F (read)
    private lateinit var vfsExistsMethod: Method       // VFS.k/e (exists)
    private val voiceNameGenMethod: Method by lazy {
        classVoiceNameGen.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(String::class, VagueType)
            returnType = String::class
        }.self
    }
    /**
     * VoiceLogic.finishRecord —— 写入 voiceinfo（文件名 / 时长 / 场景），上传前必须调用，
     * 少了这步微信找不到 voiceinfo，语音发不出去。
     *
     * 8.0.7x 起签名从 3 参扩到 5 参：
     * `(String fileName, int duration, int scene, MsgInfo info, String msgSource)`（8.0.80 实测
     * `q71.e1.u`）。原来只按 3/4 参匹配，新版直接匹配不到 → 语音链路中断。
     * 这里按「静态 + 3..5 参 + 前三位 (String,int,int)」匹配，调用时按实际参数个数补 null
     * （微信自己转发语音时也是传 null）。
     */
    private val setVoiceMethod: Method by lazy {
        classVoiceNameGen.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameterCount { it in 3..5 }
            parameters {
                it[0] == BString && it[1].typeMatches(int) && it[2].typeMatches(int)
            }
            returnType = bool
        }.self
    }
    private var storageAccPathMethod: Method? = null  // b0.e (动态解析)

    /**
     * AccPath 在本版本不可用（8.0.79 的 Storage 类继承链上没有任何零参 String 方法）。
     * 记住结果，之后每次发语音直接跳过 Service 路径，不再重扫一遍类结构。
     */
    @Volatile
    private var accPathUnresolved = false
    private val pathGenMethod: Method by lazy {
        classPathUtil.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            parameters(VagueType, VagueType, VagueType, VagueType, Int::class)
            returnType = String::class
        }.self
    }
    private lateinit var voiceDurationField: Field     // 语音时长字段
    private lateinit var voiceOffsetField: Field       // 偏移量字段

    private const val TAG = "WeMessageApi"
    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge) {
        classImageSender.find(dexKit, allowFailure = true) {
            matcher {
                usingStrings(
                    "MicroMsg.ImgUpload.MsgImgSyncSendFSC",
                    "/cgi-bin/micromsg-bin/uploadmsgimg"
                )
            }
        }

        methodImageSendEntry.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classImageSender.clazz)
                modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                paramCount(4, 5)
                usingEqStrings("send_mid_size", "send_hevc_mid_size")
            }
        }

        if (!methodImageSendEntry.isPlaceholder) {
            // DexKit 命中的方法不一定真能被宿主 ClassLoader 反射到：DexKit 读的是 APK 里的 dex，
            // 个别版本/机型上宿主实际加载的同名类会与 dex 里的不一致（8.0.80 实测
            // Lc70/r;->f7(...) 被命中但反射不到，NoSuchMethodException）。
            // 这里绝不能让一次锚点校验失败掀翻整个 WeMessageApi 解析——否则「API/消息发送服务」
            // 整体不可用，AI 自动回复等所有依赖消息发送的功能一起失效。
            // 失败时保留 classImageTask 自己的匹配结果，下面对 8.0.67+ 还会用发送入口入参校准。
            runCatching { methodImageSendEntry.method.parameterTypes[1] }
                .onSuccess { classImageTask.setDescriptor(it.name) }
                .onFailure {
                    WeLogger.w(
                        TAG,
                        "methodImageSendEntry 命中但无法反射" +
                            "(${methodImageSendEntry.getDescriptorString()})，" +
                            "保留 classImageTask 原解析结果: ${it.message}",
                    )
                }
        } else {
            WeLogger.w(TAG, "methodImageSendEntry 定位失败，图片发送链路降级")
        }

        if (HostInfo.versionCode >= WeChatVersions.MM_8_0_67 && !HostInfo.isHostGooglePlay ||
            HostInfo.versionCode >= WeChatVersions.MM_8_0_66_PLAY && HostInfo.isHostGooglePlay
        ) {
            // 8.0.80+ 同一类里存在多个「1 参 + 用到 params 串」的方法（实测 xa0.w::Uj 与 xa0.w::a），
            // 只靠 'params' 区分不出来。命中多个时旧代码直接抛 IllegalStateException，
            // 整个 WeMessageApi 解析失败 → 语音/图片/复读等全部发送能力一起不可用。
            // 这里允许多候选：真正的 sendImage 是协程入口，返回 kotlinx.coroutines.flow.*，
            // 按这个特征择优；挑不到就退回第一个候选，绝不抛异常。
            methodImgUploadFeatureServiceSendImage.find(
                dexKit,
                allowMultiple = true,
                allowFailure = true,
            ) {
                matcher {
                    declaredClass {
                        usingEqStrings("MicroMsg.ImgUpload.MsgImgFeatureService", "taskListener", "params")
                    }

                    paramCount(1)
                    usingEqStrings("params")
                }
            }
            runCatching {
                val picked = methodImgUploadFeatureServiceSendImage.candidates.firstOrNull {
                    it.paramTypeNames.size == 1 &&
                        it.returnTypeName.startsWith("kotlinx.coroutines.flow.")
                } ?: methodImgUploadFeatureServiceSendImage.candidates.firstOrNull()
                if (picked != null) {
                    methodImgUploadFeatureServiceSendImage.setDescriptor(
                        picked.className,
                        picked.methodName,
                        picked.methodSign,
                    )
                    WeLogger.i(
                        TAG,
                        "sendImage 定位到 ${picked.className}::${picked.methodName}" +
                            " (候选 ${methodImgUploadFeatureServiceSendImage.candidates.size} 个)",
                    )
                    // 8.0.67+ 的发送入口唯一入参就是图片任务类，用它校准 classImageTask，
                    // 这样即使老版本的 methodImageSendEntry 锚点命中错误，发图链路仍可用。
                    runCatching { methodImgUploadFeatureServiceSendImage.method.parameterTypes[0] }
                        .onSuccess { classImageTask.setDescriptor(it.name) }
                        .onFailure { WeLogger.w(TAG, "classImageTask 校准失败: ${it.message}") }
                }
            }.onFailure { WeLogger.w(TAG, "sendImage 择优失败: ${it.message}") }

            methodAppInfoSetAppId.find(dexKit, allowFailure = true) {
                matcher {
                    declaredClass {
                        usingEqStrings("appinfo", "appid", "version", "appname", "isforceupdate", "messageaction", "messageext", "mediatagname")
                    }

                    paramTypes(BString)
                    usingNumbers(0)
                }
            }

            ctorNetSceneUploadMsgImg.setPlaceholderDescriptor()
        } else {
            methodImgUploadFeatureServiceSendImage.setPlaceholderDescriptor()

            methodAppInfoSetAppId.setPlaceholderDescriptor()

            ctorNetSceneUploadMsgImg.find(dexKit, allowFailure = true) {
                searchPackages("com.tencent.mm.modelimage")
                matcher {
                    name = "<init>"
                    declaredClass {
                        // 8.0.79 起 "MicroMsg.NetSceneUploadMsgImg" 这个 TAG 串已不存在
                        // （只剩 MicroMsg.OnNetSceneUploadMsgImgEnd），继续要求它会永远匹配不到，
                        // 导致发图片拿不到 NetSceneUploadMsgImg 构造器。cgi 串全版本唯一，单独使用即可。
                        usingEqStrings("/cgi-bin/micromsg-bin/uploadmsgimg")
                    }
                    paramTypes(int, BString, BString, BString, int, null, int, BString, BString, bool, int)
                }
            }
        }
    }

    fun convertMsgInfoInstanceFromContentValues(contentValues: ContentValues): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.reflekt().firstMethod {
            name = "convertFrom"
            parameters(ContentValues::class, Boolean::class)
            superclass()
        }.invoke(contentValues, true)
        return msgInfo
    }

    fun createSimpleMsgInfoAndInsert(type: Int, talker: String, content: String, currentTime: Long) {
        val values = ContentValues().apply {
            put("msgid", 0)
            put("msgSvrId", currentTime + Random.nextInt())
            put("type", type)
            put("status", 3)
            put("createTime", currentTime)
            put("talker", talker)
            put("content", content)
        }
        val msgInfo = convertMsgInfoInstanceFromContentValues(values)
        methodMsgInfoStorageInsertMessage.method.invoke(
            WeServiceApi.msgInfoStorage,
            msgInfo
        )
    }

    fun revokeMsg(msgInfo: MessageInfo): Boolean {
        return try {
            WeLogger.i(TAG, "revoking message: msgSvrId=${msgInfo.serverId}")
            val netScene = ctorNetSceneRevokeMsg.newInstance(msgInfo.instance, "你撤回了一条消息", "")
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "revokeMsg failed", e); false
        }
    }

    fun revokeMsgByMsgId(msgId: Long): Boolean {
        return revokeMsg(MessageInfo(getMsgInfoInstanceByMsgSvrId(getMsgSvrIdByMsgId(msgId) ?: return false, null)))
    }

    fun revokeMsgByMsgSvrId(msgSvrId: Long): Boolean {
        return revokeMsg(MessageInfo(getMsgInfoInstanceByMsgSvrId(msgSvrId, null)))
    }

    private val methodGetMsgInfoByTalkerAndSvrId by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.MsgInfoStorage", "build new index last %d")
            }
            paramTypes(BString, long)
            returnType(classMsgInfo.clazz)
            usingEqStrings("msgSvrId=?")
        }
    }

    /** 通过本地 msgId 解析 msgSvrId；找不到或 msgSvrId 为 0 时返回 null。 */
    fun getMsgSvrIdByMsgId(msgId: Long): Long? {
        return try {
            WeDatabaseApi.rawQuery("SELECT msgSvrId FROM message WHERE msgId=?", arrayOf(msgId))
                .use { if (it.moveToFirst()) it.getLong(0).takeIf { v -> v != 0L } else null }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getMsgSvrIdByMsgId failed", e)
            null
        }
    }

    /**
     * 通过 msgSvrId 反查 talker。仅查 message 表 (C2C/群聊); 企业微信、小程序消息等不在此表中会返回 null。
     */
    fun getTalkerByMsgSvrId(msgSvrId: Long): String? {
        return try {
            WeDatabaseApi.rawQuery("SELECT talker FROM message WHERE msgSvrId=?", arrayOf(msgSvrId))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            WeLogger.e(TAG, "getTalkerByMsgSvrId failed", e)
            null
        }
    }

    /**
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     */
    fun getMsgInfoInstanceByMsgSvrId(msgSvrId: Long, talker: String? = null): Any {
        val resolvedTalker = talker ?: getTalkerByMsgSvrId(msgSvrId)
        ?: error("failed to resolve talker for msgSvrId=$msgSvrId")
        return methodGetMsgInfoByTalkerAndSvrId.method.invoke(
            WeServiceApi.msgInfoStorage, resolvedTalker, msgSvrId
        )!!
    }

    fun convertMsgInfoInstanceFromCursor(cursor: Cursor): Any {
        val msgInfo = classMsgInfo.clazz.createInstance()
        msgInfo.reflekt().firstMethod {
            name = "convertFrom"
            parameters(Cursor::class)
            returnType = void
        }.invoke(cursor)
        return msgInfo
    }

    fun sendQuoteMsgByMsgId(talker: String, msgId: Long, content: String): Boolean {
        return sendQuoteMsgByMsgSvrId(talker, getMsgSvrIdByMsgId(msgId) ?: return false, content, null)
    }

    fun sendQuoteMsgByMsgSvrId(talker: String, msgSvrId: Long, content: String): Boolean {
        return sendQuoteMsgByMsgSvrId(talker, msgSvrId, content, null)
    }

    fun sendQuoteMsgByMsgSvrId(talker: String, msgSvrId: Long, content: String, referContent: String?): Boolean {
        return try {
            WeLogger.i(TAG, "sending quote message to $talker")
            val f8 = getMsgInfoInstanceByMsgSvrId(msgSvrId, talker)
            val mi = MessageInfo(f8)
            val appmsg = JSONObject()
            appmsg.put("type", 57)
            appmsg.put("title", content)
            val refermsg = JSONObject()
            refermsg.put("type", mi.typeCode)
            refermsg.put("svrid", msgSvrId)
            refermsg.put("fromusr", mi.talker)
            refermsg.put("chatusr", mi.talker)
            refermsg.put("displayname", WeDatabaseApi.getDisplayName(mi.talker))
            refermsg.put("msgsource", "")
            refermsg.put("content", referContent ?: mi.actualContent)
            refermsg.put("strid", "")
            refermsg.put("createtime", mi.createTime)
            appmsg.put("refermsg", refermsg)
            val outer = JSONObject()
            outer.put("msg", JSONObject().put("appmsg", appmsg))
            val appMsg = classAppMessage.clazz.createInstance(outer.toString())
            methodSendAppMsg.method.invoke(null, appMsg, "", "", talker, "", null)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendQuoteMsg failed", e); false
        }
    }

    /**
     * Sends a local sticker using the same format routing as FunBox's DLC/CVm/GFh path.
     * WeChat's direct EmojiInfo upload accepts GIF and bounded PNG, but raw WebP can leave
     * the resulting type-47 message permanently pending.
     */
    fun sendSticker(toUser: String, path: String): Boolean {
        return runCatching {
            val source = path.asPath
            require(source.isRegularFile()) { "sticker source does not exist" }

            WeStickerCodec.cleanupStale()
            val info = WeStickerCodec.inspect(source)
            WeLogger.i(
                TAG,
                "sticker inspected: format=${info.format}, bytes=${info.byteCount}, " +
                        "dimensions=${info.width}x${info.height}"
            )

            when (info.format) {
                StickerFileFormat.GIF -> {
                    WeLogger.i(TAG, "sticker send route: direct emoji (GIF)")
                    sendEmoji(toUser, path)
                }

                StickerFileFormat.PNG -> {
                    if (info.fitsDirectEmoji()) {
                        WeLogger.i(TAG, "sticker send route: direct emoji (PNG)")
                        sendEmoji(toUser, path)
                    } else {
                        sendStickerAsImage(toUser, source, "PNG dimensions exceed direct emoji limit")
                    }
                }

                StickerFileFormat.WEBP -> {
                    if (!info.fitsDirectEmoji()) {
                        sendStickerAsImage(toUser, source, "WebP dimensions exceed direct emoji limit")
                    } else {
                        val converted = WeStickerCodec.createTemp("png")
                        try {
                            WeStickerCodec.convertToPng(source, converted)
                            WeLogger.i(
                                TAG,
                                "sticker converted: WEBP to PNG, bytes=${converted.fileSize()}"
                            )
                            WeLogger.i(TAG, "sticker send route: direct emoji (converted PNG)")
                            sendEmoji(toUser, converted.absolutePathString())
                        } finally {
                            converted.deleteIfExists()
                        }
                    }
                }

                StickerFileFormat.WXGF -> {
                    val converted = WeStickerCodec.createTemp("gif")
                    try {
                        WeStickerCodec.convertWxgfToGif(source, converted)
                        WeLogger.i(
                            TAG,
                            "sticker converted: WXGF to GIF, bytes=${converted.fileSize()}"
                        )
                        WeLogger.i(TAG, "sticker send route: direct emoji (converted GIF)")
                        sendEmoji(toUser, converted.absolutePathString())
                    } finally {
                        converted.deleteIfExists()
                    }
                }

                StickerFileFormat.JPEG,
                StickerFileFormat.OTHER -> sendStickerAsImage(
                    toUser,
                    source,
                    "format is not supported by direct emoji upload"
                )
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to send sticker", it)
            false
        }
    }

    /**
     * 调用微信的表情发送入口。
     *
     * 该方法的签名在 8.0.6x~8.0.8x 之间从 3 参扩到 5 参（末尾多出 msgInfo / int），
     * 这里按实际参数个数补齐实参。两条发送路径（本地文件 / 已注册 md5）共用。
     *
     * **必须带接收者**：8.0.79 上这条是 `emojiMgrImpl` 的实例方法（`n82.h#…`），
     * 漏掉接收者会抛 `IllegalArgumentException: Expected receiver of type n82.h,
     * but got java.lang.String`（把 toUser 当成了 receiver）。
     */
    private fun invokeEmojiSend(toUser: String, emoji: Any) {
        val sendMethod = WeServiceApi.emojiMgrImpl.reflekt().firstMethod {
            parameters {
                it[0] == BString &&
                        it[1] == WeServiceApi.methodSaveEmojiThumb.method.declaringClass &&
                        it[2] == classMsgInfo.clazz
            }
            returnType = void
        }.self
        val receiver = if (Modifier.isStatic(sendMethod.modifiers)) null else WeServiceApi.emojiMgrImpl
        when (sendMethod.parameterCount) {
            4 -> sendMethod.invoke(receiver, toUser, emoji, null, null)
            5 -> sendMethod.invoke(receiver, toUser, emoji, null, null, 0)
            else -> sendMethod.invoke(receiver, toUser, emoji, null)
        }
    }

    fun sendEmoji(toUser: String, path: String): Boolean {
        return runCatching {
            val md5 = WeServiceApi.processEmojiPath(path)
            val emojiThumb = WeServiceApi.saveEmojiThumb(md5)

            WeLogger.i(
                TAG,
                "emoji imported: type=${emojiThumb.intField("field_type")}, " +
                        "size=${emojiThumb.intField("field_size")}, " +
                        "start=${emojiThumb.intField("field_start")}, " +
                        "reserved4=${emojiThumb.intField("field_reserved4")}"
            )

            invokeEmojiSend(toUser, emojiThumb)
            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to send emoji by path", it)
            false
        }
    }

    private fun sendStickerAsImage(toUser: String, source: Path, reason: String): Boolean {
        val extension = source.name.substringAfterLast('.', "img")
            .lowercase()
            .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        val retained = KnownPaths.moduleCache /
                "sticker-send-image-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension"
        Files.copy(source, retained, StandardCopyOption.REPLACE_EXISTING)
        WeLogger.i(TAG, "sticker send route: ordinary image ($reason), source retained in cache")
        return sendImage(toUser, retained.absolutePathString()).also { success ->
            if (!success) retained.deleteIfExists()
        }
    }

    private fun Any.intField(name: String): Int? = runCatching {
        reflekt().firstField { this.name = name; superclass() }.get() as? Int
    }.getOrNull()

    fun sendEmojiByMd5(toUser: String, md5: String): Boolean {
        return runCatching {
            WeLogger.i(TAG, "sending registered emoji")
            val emojiInfo = WeServiceApi.getEmojiInfoByMd5(md5)
            invokeEmojiSend(toUser, emojiInfo)
            true
        }.getOrElse {
            WeLogger.e(TAG, "failed to send registered emoji", it)
            false
        }
    }

    fun sendPat(toUser: String, patTargetWxId: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending pat to $patTargetWxId in $toUser")
            // Get PatMsgExtension service instance (C1387 ≈ C1104.m2574 → classPatMsgExtension)
            val patService = WeServiceApi.getServiceByClass(classPatMsgExtension.clazz)
            // First reflection: find method returning String with 2 String params → m1650(patTarget, talker)
            val strMethod = patService.reflekt()
                .firstMethod { parameters(String::class, String::class); returnType = String::class }
                .self
            val str11 = strMethod.invoke(patService, patTargetWxId, toUser) as String
            // timestamp = (int)(System.currentTimeMillis() / 1000)
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            // Second reflection: find method returning Pair with 6 params → m1650(talker, selfWxId, patTarget, str11, timestamp, 0L)
            val pairMethod = patService.reflekt()
                .firstMethod {
                    parameters(String::class, String::class, String::class, String::class, Int::class.java, Long::class.java)
                    returnType = android.util.Pair::class.java
                }
                .self
            val pair = pairMethod.invoke(patService, toUser, WeApi.selfWxId, patTargetWxId, str11, timestamp, 0L)
            // Dispatch via background thread
            thread {
                try {
                    val netScene = ctorNetSceneSendPat.newInstance(pair, toUser, patTargetWxId, 0)
                    WeNetSceneApi.sendNetScene(netScene)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "sendPat background task failed", e)
                }
            }
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendPat failed", e); false
        }
    }

    fun sendLocation(toUser: String, poiName: String, label: String, x: String, y: String, scale: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending location: $x,$y to $toUser")
            val locJson = """{"msg":{"location":{"poiname":"$poiName","label":"$label","x":"$x","y":"$y","scale":"$scale"}}}"""
            val netScene = newSendMsgSceneInstance(ctorNetSceneSendMsgLocation.constructor, toUser, locJson)
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendLocation failed", e); false
        }
    }

    fun sendShareCard(toUser: String, cardWxId: String): Boolean {
        return try {
            WeLogger.i(TAG, "sending share card $cardWxId to $toUser")
            val json1 = JSONObject()
            val json2 = JSONObject()
            json2.put("username", cardWxId)
            val nickname = WeDatabaseApi.getDisplayName(cardWxId)
            json2.put("nickname", nickname)
            json2.put("certflag", if (cardWxId.startsWith("gh_")) 4928270286903575946L else 4928270274018674058L)
            json1.put("msg", json2)
            val netScene = newSendMsgSceneInstance(ctorNetSceneSendMsgLocation.constructor, toUser, json1.toString())
            WeNetSceneApi.sendNetScene(netScene)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendShareCard failed", e); false
        }
    }

    fun sendVideo(toUser: String, videoPath: String): Boolean {
        if (runCatching { !java.io.File(videoPath).let { it.isFile && it.length() > 0 } }.getOrDefault(true)) {
            WeLogger.w(TAG, "sendVideo skipped: mp4 missing/empty ($videoPath)")
            return false
        }
        return try {
            val ctor = resolveImportMultiVideoCtor()
            if (ctor == null) {
                WeLogger.w(TAG, "sendVideo 跳过：当前微信版本不支持多视频发送（ImportMultiVideo 未定位/校验失败）")
                return false
            }
            WeLogger.i(TAG, "sending video: $videoPath to $toUser（ImportMultiVideo=${ctor.declaringClass.name}）")
            val thread = ctor.newInstance(
                HostInfo.application,
                java.util.Collections.singletonList(videoPath),
                null, toUser, 2, null, java.lang.Boolean.TRUE
            ) as Thread
            thread.start()
            true
        } catch (t: Throwable) {
            WeLogger.e(TAG, "sendVideo failed", t); false
        }
    }

    override fun onEnable() {
        classVfs.reflekt().apply {
            vfsReadMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = InputStream::class
            }.self

            vfsCopyMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class, Boolean::class)
                returnType = OutputStream::class
            }.self

            vfsExistsMethod = firstMethod {
                modifiers(Modifiers.STATIC)
                parameters(String::class)
                returnType = Boolean::class
            }.self
        }

        classVoiceParams.reflekt().apply {
            val intFields = fields { type = Int::class }
            voiceDurationField = intFields[0].self
            voiceOffsetField = intFields[1].self
        }
    }

    /**
     * 动态解析 AccPath 获取方法。
     *
     * 返回 null 表示本版本没有可用的 AccPath（8.0.79 就是这种情况：Storage 的继承链上
     * 根本没有 `零参 + 返回 String` 的方法，扫描结果为 0）。这只是「主路径不可用」，
     * 不是错误 —— 调用方会改走 VoiceLogic 兜底路径，因此这里不抛异常、不记 E。
     */
    private fun getAccPath(): String? {
        if (accPathUnresolved) return null

        val storageObj = runCatching { methodMmKernelGetStorage.method.invoke(null) }.getOrNull()
        if (storageObj == null) {
            accPathUnresolved = true
            WeLogger.d(TAG, "AccPath 不可用: Kernel.getStorage() 返回 null，改用 VoiceLogic 兜底")
            return null
        }

        if (storageAccPathMethod != null) {
            return storageAccPathMethod!!.invoke(storageObj) as? String
        }

        WeLogger.d(TAG, "resolving AccPath method... StorageClass=${storageObj.javaClass.name}")

        var currentClass: Class<*>? = storageObj.javaClass
        var scanCount = 0

        // 递归扫描类继承链
        while (currentClass != null && currentClass != Any::class.java) {
            val methods = currentClass.declaredMethods.filter {
                it.parameterCount == 0 && it.returnType == String::class.java
            }
            scanCount += methods.size

            for (m in methods) {
                try {
                    // 排除干扰项
                    if (m.name == "toString") continue

                    m.makeAccessible()
                    val result = m.invoke(storageObj) as? String

                    // 特征校验：包含 "MicroMsg" 且以 "/" 结尾
                    if (result != null && result.contains("MicroMsg") && result.endsWith("/")) {
                        storageAccPathMethod = m
                        WeLogger.i(TAG, "resolved AccPath method: ${m.name}, path: $result")
                        return result
                    }
                } catch (_: Throwable) {
                    // ignore
                }
            }
            // 向上查找父类
            currentClass = currentClass.superclass
        }

        accPathUnresolved = true
        WeLogger.d(
            TAG,
            "AccPath 不可用 (scanned $scanCount candidates, StorageClass=${storageObj.javaClass.name})，" +
                    "改用 VoiceLogic 兜底",
        )
        return null
    }

    /** 发送图片消息 */
    fun sendImage(toUser: String, imgPath: String): Boolean {
        return try {
            val serviceObj = WeServiceApi.getServiceByClass(imageServiceApiClass)

            val paramsObj = crossParamsClass.createInstance()
            paramsObj.reflekt()
                .firstField { type = int }
                .set(4)

            val taskObj = classImageTask.clazz.createInstance(
                imgPath,
                0,
                selfCustomWxId,
                toUser,
                paramsObj
            )
            taskObj.reflekt()
                .lastField { type = String::class }
                .set("media_generate_send_img")

            sendImageMethod.invoke(serviceObj, taskObj)

            WeLogger.i(TAG, "image send task submitted")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send image message", e)
            false
        }
    }

    private val methodImgUploadFeatureServiceSendImage by dexMethod()
    private val methodAppInfoSetAppId by dexMethod()
    private val ctorNetSceneUploadMsgImg by dexConstructor()

    fun sendImageByMd5(toUser: String, md5: String, appMsgAppId: String? = null) {
        if (HostInfo.versionCode >= WeChatVersions.MM_8_0_67 && !HostInfo.isHostGooglePlay ||
            HostInfo.versionCode >= WeChatVersions.MM_8_0_66_PLAY && HostInfo.isHostGooglePlay
        ) {
            val sendImageMethod = methodImgUploadFeatureServiceSendImage.method
            val paramsClass = sendImageMethod.parameterTypes[0]
            val crossParamsClass = paramsClass.reflekt()
                .firstField { type { !it.isBuiltin } }.self.type
            val crossParams = crossParamsClass.createInstance()

            if (appMsgAppId != null) {
                val appInfoClass = methodAppInfoSetAppId.method.declaringClass
                val appInfo = appInfoClass.createInstance()
                methodAppInfoSetAppId.method.invoke(appInfo, appMsgAppId)
                crossParams.reflekt()
                    .firstField {
                        type = appInfoClass
                    }.set(appInfo)
            }

            val params = paramsClass.createInstance(md5, 1, WeApi.selfWxId, toUser, crossParams)
            sendImageMethod.invoke(WeServiceApi.getServiceByClass(sendImageMethod.declaringClass), params)
        } else {
            val xml: String?
            val wxId = WeApi.selfWxId
            if (appMsgAppId != null) {
                val json = JSONObject()
                val json2 = JSONObject()
                val json3 = JSONObject()
                json3.put("appid", appMsgAppId)
                json2.put("appinfo", json3)
                json.put("msg", json2)
                val converter = JsonToXmlConverter(json, emptyHashSet(), emptyHashSet())
                xml = converter.toString()
            } else {
                xml = null
            }
            WeNetSceneApi.sendNetScene(
                ctorNetSceneUploadMsgImg.newInstance(4, wxId, toUser, md5, 1, null, 0, xml, "", true, 0)
            )
        }
    }

    /** 发送文本消息 */
    /**
     * 构造 NetSceneSendMsg 实例。
     *
     * 微信 8.0.79+ 的构造器在末尾多了一个 msgSource(String) 参数（共 6 参），
     * 旧版为 5 参。早期实现写死 5 参，遇到 6 参宿主会抛 IllegalArgumentException
     * 被上层 catch 后表现为「复读/发送失败」。这里按实际构造器参数个数自适应。
     */
    private fun newSendMsgSceneInstance(clazz: Class<*>, toUser: String, content: String): Any? {
        // 微信 8.0.79 的 NetSceneSendMsg 只有 ()V、(J I String)V 以及两个 6 参构造器，
        // 早期写死的 5 参调用会直接抛「找不到构造器」。这里按「首参为 String」过滤后
        // 从参数多到少逐个尝试，任一成功即返回，避免依赖构造器的反射顺序。
        val filtered = clazz.constructors
            .filter { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
            .sortedByDescending { it.parameterCount }
        val candidates = if (filtered.isNotEmpty()) filtered else clazz.constructors.toList()
        for (ctor in candidates) {
            val args = buildSendMsgCtorArgs(ctor, toUser, content) ?: continue
            val obj = runCatching { ctor.newInstance(*args) }.getOrElse {
                WeLogger.w(TAG, "NetSceneSendMsg 构造失败(${ctor.toGenericString()}): ${it.message}")
                null
            }
            if (obj != null) return obj
        }
        WeLogger.e(
            TAG,
            "未找到可用的 NetSceneSendMsg 构造器；候选=" +
                clazz.constructors.joinToString(" | ") { it.toGenericString() },
        )
        return null
    }

    /** 按实际构造器参数个数自适应地构造「位置/名片」用的 NetSceneSendMsg 实例。 */
    private fun newSendMsgSceneInstance(ctor: java.lang.reflect.Constructor<*>, toUser: String, content: String): Any {
        val args = buildSendMsgCtorArgs(ctor, toUser, content)
            ?: error("unsupported NetSceneSendMsg constructor: ${ctor.toGenericString()}")
        return ctor.newInstance(*args)
    }

    /**
     * 依据构造器的真实形参类型构造实参，兼容微信 8.0.79 的 6 参形态
     * (String,String,int,int,long|Object,String) 与旧版 5 参形态。
     */
    private fun buildSendMsgCtorArgs(
        ctor: java.lang.reflect.Constructor<*>,
        toUser: String,
        content: String,
    ): Array<Any?>? {
        val types = ctor.parameterTypes
        if (types.size < 5) return null
        val args = arrayOfNulls<Any?>(types.size)
        for (i in types.indices) {
            val t = types[i]
            args[i] = when {
                t == String::class.java -> when (i) {
                    0 -> toUser
                    1 -> content
                    else -> null
                }
                t == java.lang.Integer.TYPE || t == java.lang.Integer::class.java -> if (i == 2) 1 else 0
                t == java.lang.Long.TYPE || t == java.lang.Long::class.java -> 0L
                t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java -> false
                t == java.lang.Byte.TYPE || t == java.lang.Byte::class.java -> 0.toByte()
                t == java.lang.Character.TYPE || t == java.lang.Character::class.java -> '\u0000'
                else -> null
            }
        }
        return args
    }

    /** 供诊断页展示：发送文本（NetSceneSendMsg）类定位与构造器自检结果。 */
    fun sendTextDiagnostics(): String {
        return runCatching {
            val clazz = classNetSceneSendMsg.clazz
            val ctors = clazz.constructors.joinToString(" / ") { c ->
                "(" + c.parameterTypes.joinToString(",") { it.simpleName } + ")"
            }
            val usable = clazz.constructors.any {
                it.parameterCount >= 5 && it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == String::class.java
            }
            val state = if (usable) "是" else "✗ 否（无可用构造器）"
            "类=${clazz.name}｜构造器=[$ctors]｜可发送=$state"
        }.getOrElse { "✗ 未解析（${it.javaClass.simpleName}: ${it.message}）" }
    }

    /**
     * 发送相关组件是否已定位完成。
     *
     * DexKit 的委托在解析前 descriptor 为空，解析失败会写成 LauncherUI 占位；
     * 两种都算「未就绪」。微信冷启动后模块在后台扫 dex，这期间点复读/转发
     * 一定会失败一次，看起来像功能坏了。
     */
    fun isSendReady(): Boolean = runCatching {
        val cls = classNetSceneSendMsg
        val sendObj = methodGetSendMsgObject
        val post = methodPostToQueue
        cls.getDescriptorString() != null && !cls.isPlaceholder &&
                sendObj.getDescriptorString() != null && !sendObj.isPlaceholder &&
                post.getDescriptorString() != null && !post.isPlaceholder
    }.getOrDefault(false)

    /**
     * 等待发送组件就绪，最多 [timeoutMs] 毫秒。
     *
     * 只应在后台线程调用（复读/转发都在 Dispatchers.IO 里）。
     * 返回 false 表示等超时，调用方应提示「正在准备，请稍后重试」
     * 而不是笼统的失败。
     */
    fun awaitSendReady(timeoutMs: Long = 8_000L): Boolean {
        if (isSendReady()) return true
        WeLogger.i(TAG, "send components not ready yet, waiting up to ${timeoutMs}ms")
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(120)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (isSendReady()) {
                WeLogger.i(TAG, "send components ready after waiting")
                return true
            }
        }
        WeLogger.w(TAG, "send components still not ready after ${timeoutMs}ms")
        return false
    }

    fun sendText(toUser: String, text: String): Boolean {
        return try {
            // 只记长度与目标，不记正文：日志文件在外部存储，任何人都能读。
            WeLogger.i(TAG, "sending text message to $toUser (${text.length} chars)")
            val sendMsgObject = methodGetSendMsgObject.method.invoke(null) ?: return false
            val msgObj = newSendMsgSceneInstance(classNetSceneSendMsg.clazz, toUser, text) ?: return false
            methodPostToQueue.method.invoke(sendMsgObject, msgObj) as? Boolean ?: false
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send text message", e)
            false
        }
    }

    /** 发送文件消息 */
    fun sendFile(talker: String, filePath: String, title: String, appId: String? = null): Boolean {
        return try {
            WeLogger.i(TAG, "sending file message: $filePath")
            val fileObject = WXFileObject()
            fileObject.filePath = filePath
            val mediaMessage = WXMediaMessage()
            mediaMessage.mediaObject = fileObject
            mediaMessage.title = title
            methodShareFile.method.invoke(null, mediaMessage, appId ?: "", "", talker, 2, null)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to send file message", e)
            false
        }
    }

    /**
     * 由微信侧的「加密语音路径」解析出可用的语音文件完整路径。
     *
     * 容错（对齐 WeChatVoiceApi 一类成熟实现的做法，但按本工程框架重写）：
     * [methodGetAmrFullPath] 在不同微信版本间参数形态发生过变化——既有
     * `static (String, boolean)` 的 2 参形态，也有 `(Object, String, boolean)` 的 3 参形态。
     * 早期实现无条件按 3 个实参调用，一旦宿主换成 2 参形态就会抛
     * IllegalArgumentException: wrong number of arguments，被上层 catch 后表现为「转发语音失败」。
     * 这里按实际 parameterCount 分派，并对返回值做非空/可读校验。
     *
     * 解析失败返回空串（不抛异常），调用方需自行判空。
     */
    fun getVoiceFullPath(encPath: String): String {
        if (encPath.isBlank()) {
            WeLogger.w(TAG, "getVoiceFullPath: empty source path")
            return ""
        }
        val m = runCatching { methodGetAmrFullPath.method }.getOrElse {
            WeLogger.e(TAG, "getVoiceFullPath: methodGetAmrFullPath not resolved", it)
            return ""
        }
        val paramCount = m.parameterCount
        val resolved = runCatching {
            when (paramCount) {
                // static (String, boolean)
                2 -> m.invoke(null, encPath, true)
                // (Object service, String, boolean) —— 实例方法需先取宿主服务实例
                3 -> {
                    val service = if (Modifier.isStatic(m.modifiers)) null
                    else WeServiceApi.getServiceByClass(m.declaringClass)
                    if (!Modifier.isStatic(m.modifiers) && service == null) {
                        WeLogger.w(TAG, "getVoiceFullPath: service instance unavailable for ${m.declaringClass.name}")
                        return ""
                    }
                    m.invoke(service, null, encPath, true)
                }
                else -> {
                    WeLogger.w(TAG, "getVoiceFullPath: unsupported parameter count $paramCount for ${m.declaringClass.name}")
                    return ""
                }
            }
        }.onFailure {
            WeLogger.e(TAG, "getVoiceFullPath failed (paramCount=$paramCount): $encPath", it)
        }.getOrNull()

        val result = resolved as? String
        if (result.isNullOrBlank()) {
            WeLogger.w(TAG, "getVoiceFullPath returned empty (paramCount=$paramCount): $encPath")
            return ""
        }
        // 解析出的路径可能还不存在 —— 发语音时它是**待写入的目标**（紧接着
        // Files.copy 过去），存语音时才是源。两种情况都由调用方判定，
        // 这里只做提示，记 D 不记 W，避免每次发语音都刷一条假警告。
        if (!File(result).isFile) {
            WeLogger.d(TAG, "getVoiceFullPath: path not existing yet (destination or stale): $result")
        }
        return result
    }

    // WeChat marks a received voice message as read (clearing the unplayed red dot) by
    // flipping the played flag inside the voice content string and persisting it — see
    // w21.x0.q(MsgInfo) (classVoiceLogic), the only static void(MsgInfo) method in that class,
    // invoked from AutoPlay.startPlay before playback. We call it directly so features that
    // consume a voice message without going through playback (e.g. auto speech-to-text) can
    // clear the red dot the same way WeChat would.
    fun markVoicePlayed(msgInfo: MessageInfo) {
        classVoiceLogic.clazz.reflekt()
            .firstMethod {
                parameters(classMsgInfo.clazz)
                returnType = void
                modifiers(Modifiers.STATIC)
            }.invokeStatic(msgInfo.instance)
    }

    fun sendVoice(toUser: String, path: String, durationMs: Int): Boolean {
        // 源文件不在就没有任何通道能发出去，先判掉，避免误报「发送成功」
        if (runCatching { !java.io.File(path).let { it.isFile && it.length() > 0 } }.getOrDefault(true)) {
            WeLogger.w(TAG, "sendVoice skipped: source file missing/empty ($path)")
            return false
        }
        var succeeded = false

        // AccPath 只在部分版本可用。取不到就跳过 Service 路径直接走 VoiceLogic 兜底,
        // 不要在这里记 E —— 否则每次发语音都会留一条「假报错」(兜底其实是成功的)。
        val accPath = getAccPath()
        if (accPath == null) {
            WeLogger.d(TAG, "sendVoice: 跳过 Service 路径，直接走 VoiceLogic")
        } else {
            succeeded = runCatching {
                // 准备文件
                val fileName = voiceNameGenMethod.invoke(getReceiverForMethod(voiceNameGenMethod), toUser, "amr_") as? String
                    ?: error("failed to generate voice name")
                val voice2Root = if (accPath.endsWith("/")) "${accPath}voice2/" else "$accPath/voice2/"
                val destFullPath =
                    pathGenMethod.invoke(null, voice2Root, "msg_", fileName, ".amr", 2) as? String
                        ?: error("failed to generate path")

                // 注意：这里不能 `return false` —— runCatching 是 inline，非局部返回会直接
                // 结束整个 sendVoice()，把后面的 WAuxv 兜底路径也跳过（历史 bug）。
                // 抛异常交给 runCatching 记日志并落到第二条路径。
                if (!copyFileViaVfs(path, destFullPath)) error("VFS copy failed")

                // 设置语音信息（VoiceLogic.setVoice 在 8.0.7x 起已不存在，拿不到就跳过时长）
                val finalDurationMs = durationMs.coerceIn(1, 60_000)
                val setVoice = runCatching { setVoiceMethod }.getOrNull()
                if (setVoice == null) {
                    WeLogger.w(TAG, "VoiceLogic 没有 setVoice(String,int,int)，跳过时长设置：fileName=$fileName duration=${finalDurationMs}ms")
                } else {
                    val setVoiceReceiver = getReceiverForMethod(setVoice)
                    val setVoiceResult = when (setVoice.parameterCount) {
                        3 -> setVoice.invoke(setVoiceReceiver, fileName, finalDurationMs, 0)
                        4 -> setVoice.invoke(setVoiceReceiver, fileName, finalDurationMs, 0, null)
                        else -> setVoice.invoke(setVoiceReceiver, fileName, finalDurationMs, 0, null, null)
                    } as? Boolean ?: false
                    if (!setVoiceResult) {
                        WeLogger.w(TAG, "VoiceLogic.setVoice returned false, still starting voice service: fileName=$fileName, target=$toUser")
                    }
                }

                startVoiceService()
            }.onFailure {
                // 主路径失败只是「换一条路」，兜底成功就不是真错误 —— 记 W 即可。
                WeLogger.w(TAG, "sendVoice 主路径失败，转 VoiceLogic 兜底", it)
            }.isSuccess
        }

        if (succeeded) return true

        succeeded = runCatching {
            val partialPath = classVoiceLogic.reflekt()
                .firstMethod {
                    parameters(BString, BString)
                    returnType = BString
                }
                .invokeStatic(toUser, "amr_") as String
            val fullPath = getVoiceFullPath(partialPath)

            Files.copy(Path(path), Path(fullPath), StandardCopyOption.REPLACE_EXISTING)

            val actualDuration = if (durationMs > 60000) 60000 else durationMs

            // 注意：8.0.7x 起 VoiceLogic 里没有 setVoice(String,int,int)，
            // 原来这里直接 firstMethod 会抛异常，把整条发送链路打断，
            // 表现就是「语音复读/转发发不出去」。时长只是展示字段，找不到就跳过。
            val target = runCatching {
                classVoiceLogic.clazz.reflekt()
                    .firstMethod {
                        modifiers(Modifiers.STATIC)
                        parameterCount { it in 3..5 }
                        parameters {
                            it[0] == BString && it[1] == BInt && it[2] == BInt
                        }
                        returnType = BBool
                    }.self
            }.getOrNull()
            if (target == null) {
                WeLogger.w(TAG, "VoiceLogic 没有 setVoice(String,int,int)，跳过时长设置：duration=${actualDuration}ms")
            } else {
                val r = when (target.parameterCount) {
                    3 -> target.invoke(null, partialPath, actualDuration, 0)
                    4 -> target.invoke(null, partialPath, actualDuration, 0, null)
                    else -> target.invoke(null, partialPath, actualDuration, 0, null, null)
                }
                if (r == false) {
                    WeLogger.w(TAG, "VoiceLogic.finishRecord 返回 false（duration=${actualDuration}ms），继续尝试发送")
                }
            }

            // 与主路径共用同一个启动逻辑：以前这里手抄了一份「取 SceneVoiceService →
            // 调 run() / startRecvAndSend」的代码，两处很容易改漏。
            startVoiceService()

            WeLogger.i(TAG, "sent voice (WAuxv method): $fullPath")
        }.onFailure { WeLogger.e(TAG, "failed to send voice (WAuxv method)", it) }.isSuccess

        return succeeded
    }

    private fun getReceiverForMethod(method: Method): Any? {
        return if (Modifier.isStatic(method.modifiers)) {
            null
        } else {
            WeServiceApi.getServiceByClass(method.declaringClass)
        }
    }

    private fun startVoiceService() {
        runCatching {
            // 获取 SceneVoiceService 实例
            val serviceType = methodStartRecvAndSend.method.declaringClass

            val getServiceMethod = classSceneVoiceService.reflekt().firstMethod {
                modifiers(Modifiers.STATIC)
                parameters()
                returnType = serviceType
            }.self

            val service = getServiceMethod.invoke(getReceiverForMethod(getServiceMethod))
                ?: error("SceneVoiceService.getVoiceService returned null")

            // 优先调用 run()：它会在服务自身的 looper 线程上派发发送任务。
            // 直接在 IO 线程调用 startRecvAndSend 会因构造 Handler 时无 Looper
            // 抛出 "looper and serial is null!"，导致发送卡死转圈，必须重启微信。
            val runMethod = runCatching {
                if (methodRunVoiceService.isPlaceholder) return@runCatching null
                methodRunVoiceService.method.makeAccessible()
            }.onFailure {
                WeLogger.w(TAG, "failed to load SceneVoiceService.run, fallback to startRecvAndSend", it)
            }.getOrNull()

            if (runMethod != null) {
                runMethod.invoke(service)
            } else {
                methodStartRecvAndSend.method.invoke(getReceiverForMethod(methodStartRecvAndSend.method), service)
            }
            WeLogger.d(TAG, "voice service started successfully")
        }.onFailure {
            WeLogger.e(TAG, "failed to start voice service", it)
        }
    }

    fun sendXmlAppMsg(target: String, xmlContent: String): Boolean {
        // sendXml 走 AppMsgLogic 的 13 参重载（8.0.79 上 6 参那条已不可用）
        val appId = extractXmlAttr(xmlContent, "appid")
        val title = extractXmlTag(xmlContent, "title")

        WeLogger.d(TAG, "appmsg info: appid=$appId, title=$title")
        return WeAppMsgApi.sendXmlAppMsg(target, title, appId, null, null, xmlContent)
    }

    /**
     * 使用微信内部 VFS 引擎进行物理拷贝
     */
    private fun copyFileViaVfs(src: String, dst: String): Boolean {
        WeLogger.d(TAG, "VFS Copy: $src -> $dst")
        return try {
            val input = vfsReadMethod.invoke(null, src) as? InputStream
                ?: error("VFS Open Failed for $src")

            val output = vfsCopyMethod.invoke(null, dst, false) as? OutputStream
                ?: error("VFS Create Failed for $dst")

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            // 校验
            val exists = vfsExistsMethod.invoke(null, dst) as? Boolean ?: false
            if (exists) {
                WeLogger.i(TAG, "VFS copy succeeded")
            } else {
                WeLogger.e(TAG, "VFS copy seems successful but actually failed")
            }
            exists
        } catch (e: Exception) {
            WeLogger.e(TAG, "VFS copy failed", e)
            false
        }
    }

    /**
     * 六个 `share*` 的公共骨架。
     *
     * 原先每个方法各写一遍「构造 WXMediaMessage → shareMedia → true / catch → E → false」，
     * 六份共 ~150 行，只有 mediaObject 不同。这里把骨架收成一处，新增通道只需建对象。
     */
    private inline fun sendMediaMessage(
        tag: String,
        talker: String,
        title: String = "",
        description: String = "",
        thumbData: ByteArray? = null,
        appId: String?,
        mediaObject: WXMediaMessage.IMediaObject,
    ): Boolean {
        return try {
            val message = WXMediaMessage().apply {
                this.title = title
                this.description = description
                this.thumbData = thumbData
                this.mediaObject = mediaObject
            }
            shareMedia(talker, message, appId)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "$tag failed", e)
            false
        }
    }

    /** 把 WXMediaMessage 交给微信分享入口；参数顺序（appId, "", talker, 3, null）是宿主约定。 */
    private fun shareMedia(talker: String, mediaMessage: Any, appId: String?) {
        methodShareFile.method.invoke(null, mediaMessage, appId ?: "", "", talker, 3, null)
    }

    fun shareWebpage(
        talker: String,
        title: String,
        description: String,
        webpageUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareWebpage", talker, title, description, thumbData, appId,
        WXWebpageObject().apply { this.webpageUrl = webpageUrl },
    )

    fun shareVideo(
        talker: String,
        title: String,
        description: String,
        videoUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareVideo", talker, title, description, thumbData, appId,
        WXVideoObject().apply { this.videoUrl = videoUrl },
    )

    fun shareText(
        talker: String,
        text: String,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareText", talker, appId = appId,
        mediaObject = WXTextObject().apply { this.text = text },
    )

    fun shareMusic(
        talker: String,
        title: String,
        description: String,
        musicUrl: String,
        musicDataUrl: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareMusic", talker, title, description, thumbData, appId,
        WXMusicObject().apply {
            this.musicUrl = musicUrl
            this.musicDataUrl = musicDataUrl
        },
    )

    fun shareMusicVideo(
        talker: String,
        title: String,
        description: String,
        musicUrl: String,
        musicDataUrl: String,
        singerName: String,
        duration: Int,
        songLyric: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareMusicVideo", talker, title, description, thumbData, appId,
        WXMusicVideoObject().apply {
            this.musicUrl = musicUrl
            this.musicDataUrl = musicDataUrl
            this.singerName = singerName
            this.duration = duration
            this.songLyric = songLyric
        },
    )

    fun shareMiniProgram(
        talker: String,
        title: String,
        description: String,
        userName: String,
        path: String,
        thumbData: ByteArray?,
        appId: String? = null
    ): Boolean = sendMediaMessage(
        "shareMiniProgram", talker, title, description, thumbData, appId,
        WXMiniProgramObject().apply {
            webpageUrl = "https://github.com"
            this.userName = userName
            this.path = path
        },
    )

    fun sendMediaMsg(talker: String, mediaMessage: Any, appId: String?): Boolean {
        return try {
            shareMedia(talker, mediaMessage, appId)
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "sendMediaMsg failed", e)
            false
        }
    }

    val selfCustomWxId: String
        get() {
            return runCatching {
                getSelfAliasMethod?.invoke(null) as? String ?: ""
            }.getOrElse {
                WeLogger.w(TAG, "selfCustomWxId resolution failed", it)
                ""
            }
        }

    fun getMsgInfoFromTag(tag: Any): Any {
        val mGetMsgInfo = tag.reflekt()
            .firstMethodOrNull {
                returnType = classMsgInfo.clazz
                parameterCount(0)
                superclass()
            }

        return if (mGetMsgInfo != null) {
            mGetMsgInfo.invoke()!!
        } else {
            tag.reflekt()
                .firstField {
                    type = classMsgInfo.clazz
                    superclass()
                }.get()!!
        }
    }

    // ---- 媒体下载/保存 ----

    private val classEmojiFileEncryptMgr by dexClass {
        matcher {
            methods {
                add {
                    usingEqStrings(
                        "MicroMsg.emoji.EmojiFileEncryptMgr",
                        "decode emoji file failed. path is no exist :%s "
                    )
                }
            }
        }
    }

    // com.tencent.mm.pluginsdk.model.app 里的 "自动下载文件" Runnable (8069 为 b0, 8074 为 c0),
    // 构造参数为一个 MsgInfo, run() 会解析 appmsg XML、创建 appattach 行并向 CDN 发起下载任务。
    // 这正是微信里点击文件气泡"下载/缓存"所走的逻辑。
    private val classAppAttachAutoDownload by dexClass {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        matcher {
            methods {
                add {
                    usingEqStrings("MicroMsg.AppMessageExtension", "autoDownloadFile2 %s %s")
                }
            }
        }
    }

    /** 从 message 表读取指定 msgSvrId 消息的 imgPath 字段 (贴纸为 md5, 语音为加密路径)。 */
    private fun queryImgPathByMsgSvrId(msgSvrId: Long): String? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT imgPath FROM message WHERE msgSvrId=?",
            arrayOf(msgSvrId.toString())
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null else rows.getString(0)?.takeIf { it.isNotEmpty() }
        }
    }

    fun cacheImage(msgSvrId: Long): String? = WeMessageImageStore.cacheImage(msgSvrId)

    fun downloadImage(msgSvrId: Long): String? = WeMessageImageStore.downloadImage(msgSvrId)

    data class StickerData(val bytes: ByteArray, val extension: String)

    /**
     * 按文件头判断图片格式。
     *
     * 微信表情库存的贴纸并不都是 WXAM —— 大量贴纸本身就是 GIF/PNG/WEBP,
     * 对这类数据调用 `nativeWxamToGif` 会直接返回 null (不是抛异常),
     * 之前无条件转码就是"保存到面板/存本地"失败的根因。
     */
    private fun detectImageExtension(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes.hasMagic(0, 0x47, 0x49, 0x46) -> "gif"
        bytes.size >= 8 && bytes.hasMagic(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "webp"
        bytes.size >= 3 && bytes.hasMagic(0, 0xFF, 0xD8, 0xFF) -> "jpg"
        else -> null
    }

    /**
     * 微信自己的贴纸解密路径 (`EmojiInfo.getDecryptPath()`)。
     *
     * 与「表情消息以图片打开」用的是同一个锚点: 微信渲染贴纸前必须先把文件解密到
     * `emoji/decrypt/` 下, 拿到的字节一定是可解码的原始图片, 不依赖我们猜格式。
     */
    private val methodEmojiDecryptPath by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.storage.emotion.EmojiInfo"
            paramCount = 0
            returnType = "java.lang.String"
            usingEqStrings(
                "MicroMsg.emoji.EmojiInfo",
                "[cpan] get icon path failed. product id and md5 are null.",
                "decrypt/",
                "getDecryptPath decrypt %s",
            )
        }
    }

    /** 把原始字节归一成可落盘的图片: 常见格式原样返回, 只有 WXGF/WXAM 才转码。 */
    private fun toStickerData(raw: ByteArray, source: String): StickerData? {
        if (raw.isEmpty()) return null
        detectImageExtension(raw)?.let { return StickerData(raw, it) }

        val animated: ByteArray? = runCatching { MMWXGFJNI.nativeWxamToGif(raw) }.getOrNull()
        if (animated != null && animated.size >= 3) return StickerData(animated, "gif")

        val still: ByteArray? = runCatching {
            MMWXGFJNI.wxam2PicBuf(raw, 0, MMWXGFJNI.WXAM_SCENE_MISC)
        }.getOrNull()
        if (still != null && still.isNotEmpty()) {
            return StickerData(still, detectImageExtension(still) ?: "png")
        }

        WeLogger.e(TAG, "unsupported sticker payload from $source, size=${raw.size}")
        return null
    }

    /**
     * 根据 md5 解密贴纸, 返回可直接落盘/入库的字节与真实扩展名。
     *
     * 两条解密路径, 按可靠度排序:
     * 1. `EmojiInfo.getDecryptPath()` —— 微信自己渲染贴纸用的解密文件, 格式一定正确;
     * 2. `EmojiFileEncryptMgr` 解密 —— 拿到的字节按文件头判断格式, 只有 WXAM 才转码。
     *
     * 之前无条件 `nativeWxamToGif` 就是失败根因: 已经是 GIF/PNG 的贴纸会让它返回 null。
     */
    fun decryptStickerByMd5(md5: String): StickerData? {
        val emojiInfo = runCatching { WeServiceApi.getEmojiInfoByMd5(md5) }.getOrElse { error ->
            WeLogger.e(TAG, "getEmojiInfoByMd5 failed: md5=$md5", error)
            null
        }

        if (emojiInfo != null) {
            val hostDecrypted = runCatching {
                (methodEmojiDecryptPath.method.invoke(emojiInfo) as? String)
                    ?.asPath
                    ?.takeIf { it.isRegularFile() && it.fileSize() > 0L }
                    ?.readBytes()
            }.getOrElse { error ->
                WeLogger.w(TAG, "host sticker decrypt failed: md5=$md5", error)
                null
            }
            if (hostDecrypted != null) {
                toStickerData(hostDecrypted, "host-decrypt")?.let { return it }
            }
        }

        val raw = runCatching {
            val emojiFileEncryptMgr = classEmojiFileEncryptMgr.reflekt()
                .firstMethod {
                    modifiers(Modifiers.STATIC)
                    parameterCount = 0
                }
                .invokeStatic()!!
            emojiFileEncryptMgr.reflekt()
                .firstMethod {
                    parameters(IEmojiInfo::class)
                    returnType = ByteArray::class
                }
                .invoke(emojiInfo) as ByteArray
        }.getOrElse { error ->
            WeLogger.e(TAG, "decryptStickerByMd5 failed: md5=$md5", error)
            return null
        }

        return toStickerData(raw, "encrypt-mgr").also {
            if (it == null) WeLogger.e(TAG, "decryptStickerByMd5: unsupported payload, md5=$md5")
        }
    }

    /** 根据 msgSvrId 解密贴纸 (面板导入用, 不落 Downloads)。 */
    fun readStickerByMsgSvrId(msgSvrId: Long): StickerData? {
        val md5 = queryImgPathByMsgSvrId(msgSvrId) ?: run {
            WeLogger.e(TAG, "readStickerByMsgSvrId: no imgPath for msgSvrId=$msgSvrId")
            return null
        }
        return decryptStickerByMd5(md5)
    }

    /**
     * 根据 md5 解密贴纸并保存到 Download/zhiyou/。
     * @return 保存后的文件路径, 失败返回 null
     */
    fun saveStickerByMd5(md5: String, fileName: String? = null): String? {
        return try {
            val sticker = decryptStickerByMd5(md5) ?: return null
            val outPath = KnownPaths.downloads /
                    (fileName ?: "sticker_${System.currentTimeMillis()}.${sticker.extension}")
            outPath.deleteIfExists()
            outPath.outputStream().use { it.write(sticker.bytes) }
            WeLogger.i(TAG, "saved sticker: $outPath")
            outPath.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveStickerByMd5 failed", e)
            null
        }
    }

    /**
     * 根据 msgSvrId 解密贴纸并保存到 Download/zhiyou/。
     * @return 保存后的文件路径, 失败返回 null
     */
    fun cacheAndSaveSticker(msgSvrId: Long): String? {
        val md5 = queryImgPathByMsgSvrId(msgSvrId) ?: run {
            WeLogger.e(TAG, "cacheAndSaveSticker: no imgPath for msgSvrId=$msgSvrId")
            return null
        }
        return saveStickerByMd5(md5)
    }

    /**
     * 根据加密路径解码语音 (silk → mp3) 并保存到 Download/zhiyou/。
     * @return 保存后的 mp3 文件路径, 失败返回 null
     */
    fun saveVoiceByEncPath(encPath: String): String? {
        return try {
            val silkOriginalPath = getVoiceFullPath(encPath).asPath
            val silkPath = KnownPaths.downloads / silkOriginalPath.name
            val pcmPath = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".pcm")
            val mp3Path = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".mp3")

            silkPath.deleteIfExists()
            silkOriginalPath.copyTo(silkPath, overwrite = true)
            AudioUtils.silkToPcm(silkPath.absolutePathString(), pcmPath.absolutePathString())
            AudioUtils.pcmToMp3(pcmPath.absolutePathString(), mp3Path.absolutePathString())
            pcmPath.deleteIfExists()
            WeLogger.i(TAG, "saved voice: $mp3Path")
            mp3Path.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "saveVoiceByEncPath failed", e)
            null
        }
    }

    /**
     * 根据 msgSvrId 解码语音 (silk → mp3) 并保存到 Download/zhiyou/。
     * @return 保存后的 mp3 文件路径, 失败返回 null
     */
    fun cacheAndSaveVoice(msgSvrId: Long): String? {
        val encPath = queryImgPathByMsgSvrId(msgSvrId) ?: run {
            WeLogger.e(TAG, "cacheAndSaveVoice: no imgPath for msgSvrId=$msgSvrId")
            return null
        }
        return saveVoiceByEncPath(encPath)
    }

    // ---- 文件缓存/下载 ----

    private data class AppAttachInfo(val status: Long, val offset: Long, val totalLen: Long, val fileFullPath: String?) {
        // 与微信 d.n0() + status==199 一致: 文件已完整落地
        val isComplete get() = status == 199L || totalLen > 0 && offset == totalLen
    }

    /** 从 appattach 表按 (msgInfoId, msgInfoTalker) 读取文件附件的下载状态与本地路径。 */
    private fun queryAppAttach(msgId: Long, talker: String): AppAttachInfo? {
        val rows = WeDatabaseApi.rawQuery(
            "SELECT status, offset, totalLen, fileFullPath FROM appattach WHERE msgInfoId=? AND msgInfoTalker=?",
            arrayOf(msgId.toString(), talker)
        )
        return rows.use { rows ->
            if (!rows.moveToFirst()) null
            else AppAttachInfo(rows.getLong(0), rows.getLong(1), rows.getLong(2), rows.getString(3))
        }
    }

    /**
     * 触发微信内部下载 (相当于点击文件气泡"下载"): 解析 appmsg XML、创建 appattach 行、向 CDN 发起下载任务。
     * 通过复用微信自己的 autoDownloadFile Runnable 完成。
     */
    private fun triggerFileDownload(msgInfoInstance: Any): Boolean {
        return try {
            val runnable = classAppAttachAutoDownload.clazz.createInstance(msgInfoInstance, isPublic = false)
            (runnable as Runnable).run()
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "triggerFileDownload failed", e)
            false
        }
    }

    private fun pollUntilFileDownloaded(msgId: Long, talker: String): String? {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val info = queryAppAttach(msgId, talker)
            if (info != null && info.isComplete && !info.fileFullPath.isNullOrEmpty()) {
                return info.fileFullPath
            }
            Thread.sleep(1000)
        }
        return null
    }

    /**
     * 缓存文件 (直接使用已有的 msgInfo 实例)。
     *
     * 优先走此重载: 聊天里长按弹出的菜单已经持有微信正在渲染的、字段完整的 msgInfo 实例,
     * 直接复用即可, 无需按 msgSvrId 重新查库重建 —— 重建出的实例可能字段缺失或压根查不到行,
     * 导致微信内部 [op0.q.u]/parse msg 返回 null 后被解引用而抛 NPE。
     *
     * 缓存与下载分离 —— 此方法只负责把文件缓存到微信内部, 不拷贝到 Download/zhiyou/。
     * @return 缓存后文件在微信内部的绝对路径, 失败返回 null
     */
    fun cacheFile(msgInfoInstance: Any): String? {
        return try {
            val mi = MessageInfo(msgInfoInstance)
            val talker = mi.talker

            // 已缓存则直接返回
            queryAppAttach(mi.id, talker)?.let { info ->
                if (info.isComplete && !info.fileFullPath.isNullOrEmpty()) return info.fileFullPath
            }

            // 内容为空时微信的下载逻辑会解析 appmsg XML 失败并对 null 解引用抛 NPE,
            // 在此提前拦截, 给出可读的错误而不是把崩溃丢进微信内部。
            if (mi.content.isBlank()) {
                WeLogger.e(TAG, "cacheFile: msgInfo content is empty (msgSvrId=${mi.serverId}), cannot trigger download")
                return null
            }

            if (!triggerFileDownload(msgInfoInstance)) return null
            pollUntilFileDownloaded(mi.id, talker)
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheFile failed", e)
            null
        }
    }

    /**
     * 缓存文件 (按 msgSvrId 重新查库重建 msgInfo 实例)。
     *
     * 仅供无法拿到现成 msgInfo 实例的调用方 (如远程 API / WeAgent) 使用;
     * 能拿到实例时请改用 [cacheFile] 的实例重载。
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     * @return 缓存后文件在微信内部的绝对路径, 失败返回 null
     */
    fun cacheFile(msgSvrId: Long, talker: String? = null): String? {
        return try {
            val msgInfoInstance = getMsgInfoInstanceByMsgSvrId(msgSvrId, talker)
            cacheFile(msgInfoInstance)
        } catch (e: Exception) {
            WeLogger.e(TAG, "cacheFile failed", e)
            null
        }
    }

    /**
     * 下载文件 (直接使用已有的 msgInfo 实例): 先确保文件已缓存到微信内部, 再拷贝到 Download/zhiyou/。
     * @return 拷贝到 Download/zhiyou/ 后的绝对路径, 失败返回 null
     */
    fun downloadFile(msgInfoInstance: Any): String? {
        return try {
            val cachedPath = cacheFile(msgInfoInstance) ?: run {
                WeLogger.e(TAG, "downloadFile: failed to cache file")
                return null
            }
            val srcPath = cachedPath.asPath
            val destPath = KnownPaths.downloads / srcPath.name
            destPath.deleteIfExists()
            srcPath.copyTo(destPath, overwrite = true)
            WeLogger.i(TAG, "downloaded file: $destPath")
            destPath.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadFile failed", e)
            null
        }
    }

    /**
     * 下载文件 (按 msgSvrId 重新查库重建 msgInfo 实例)。
     * 能拿到实例时请改用 [downloadFile] 的实例重载。
     * @param talker 会话 username; 传 null 时自动从 message 表反查 (仅覆盖 C2C/群聊)。
     * @return 拷贝到 Download/zhiyou/ 后的绝对路径, 失败返回 null
     */
    fun downloadFile(msgSvrId: Long, talker: String? = null): String? {
        return try {
            val msgInfoInstance = getMsgInfoInstanceByMsgSvrId(msgSvrId, talker)
            downloadFile(msgInfoInstance)
        } catch (e: Exception) {
            WeLogger.e(TAG, "downloadFile failed", e)
            null
        }
    }

    fun setReferringMessage(chatFooter: ChatFooter, msgInfoInstance: Any) {
        val quoteMethod = chatFooter.reflekt()
            .firstMethod {
                parameters { args -> args[0] == classMsgInfo.clazz }
                returnType = Boolean::class
            }.self
        if (quoteMethod.parameterCount == 1) quoteMethod.invoke(chatFooter, msgInfoInstance)
        else quoteMethod.invoke(chatFooter, msgInfoInstance, null)
    }
}
