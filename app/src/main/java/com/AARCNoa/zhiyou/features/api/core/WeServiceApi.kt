package com.AARCNoa.zhiyou.features.api.core

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexClass
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.api.core.models.MessageInfo
import com.AARCNoa.zhiyou.features.core.ApiFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.BString
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@Feature(name = "微信服务管理服务", categories = ["API"], description = "提供获取并使用微信服务的能力")
object WeServiceApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeServiceApi"

    private val methodServiceManagerGetService by dexMethod {
        matcher {
            modifiers(Modifier.STATIC)
            paramTypes(Class::class.java)
            usingEqStrings("calling getService(...)")
        }
    }
    private val classEmojiFeatureService by dexClass {
        searchPackages("com.tencent.mm.feature.emoji")
        matcher {
            methods {
                add {
                    usingEqStrings("MicroMsg.EmojiFeatureService", "[onAccountInitialized]")
                }
            }
        }
    }
    private val classStorageFeatureService by dexClass {
        searchPackages("com.tencent.mm.plugin.messenger.foundation")
        matcher {
            addMethod {
                returnType {
                    usingEqStrings("PRAGMA table_info( contact_ext )")
                }
            }
            addMethod {
                returnType {
                    usingEqStrings("MicroMsg.MsgInfoStorage", "deleted dirty msg ,count is %d")
                }
            }
            addMethod {
                returnType {
                    usingEqStrings("PRAGMA table_info( rconversation)")
                }
            }
        }
    }
    private val classChatroomService by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChatroomService", "[isEnableRoomManager]")
        }
    }
    private val methodChatroomStorageGetMemberCount by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.ChatroomStorage", "[getMemberCount] init field_memberCount! username:%s count:%s")
        }
    }
    val classImageInfoStorage by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ImgInfoStorage", "generateMd5: %s, %s")
        }
    }
    val methodDownloadImageServiceDownloadImage by dexMethod {
        matcher {
            usingEqStrings("ModelImage.DownloadImgService", "] add failed, task already done")
        }
    }
    val classImageFeatureService by dexClass {
        matcher {
            addFieldForType(classImageInfoStorage.clazz)
            addFieldForType(methodDownloadImageServiceDownloadImage.method.declaringClass)
        }
    }
    private val methodApiManagerGetApi by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.manager")
        matcher {
            usingEqStrings("[get] ", " is not a interface!")
        }
    }
    private val methodMmKernelGetServiceImpl by dexMethod()
    private val methodVideoPathFeatureServiceRestoreMp4Path by dexMethod() // formerly VideoInfoStorage
    val classVideoService by dexClass {
        matcher {
            usingEqStrings("MicroMsg.VideoService", "MicroMsg.SubCoreVideo", "quitVideoSendThread")
        }
    }
    val classEmojiMgrImpl by dexClass {
        matcher {
            usingEqStrings("MicroMsg.emoji.EmojiMgrImpl", "sendEmoji: context is null")
        }
    }
    val classEmojiInfoStorage by dexClass {
        matcher {
            usingEqStrings("MicroMsg.emoji.EmojiInfoStorage", "md5 is null or invalue. md5:%s")
        }
    }
    private val classEmojiStorageMgr by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.emoji.EmojiStorageMgr")
        }
    }
    val methodSaveEmojiThumb by dexMethod {
        matcher {
            declaredClass("com.tencent.mm.storage.emotion.EmojiInfo")
            usingEqStrings("save emoji thumb error")
        }
    }
    val apiManagerClass: Class<*>
        get() = methodApiManagerGetApi.method.declaringClass

    val emojiFeatureService
        get() = getServiceByClass(classEmojiFeatureService.clazz)

    /**
     * 反射定位结果缓存。
     *
     * 下面这些 getter 位于发消息 / 表情 / 图片 / DB 读写等热路径上，原来每次访问都要
     * `reflekt()` 扫一遍方法表。方法（Field）本身与账号无关，可以安全缓存；**实例**仍每次
     * 重新取，账号切换后不会拿着旧库的服务对象。
     */
    private val methodEmojiStorageMgr by lazy {
        classEmojiStorageMgr.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            returnType = classEmojiStorageMgr.clazz
        }.self
    }
    private val methodEmojiInfoStorage by lazy {
        emojiStorageMgr.reflekt().firstMethod {
            returnType = classEmojiInfoStorage.clazz
        }.self
    }
    private val methodMsgInfoStorage by lazy {
        storageFeatureService.reflekt().firstMethod {
            parameterCount = 0
            returnType = WeMessageApi.classMsgInfoStorage.clazz
        }.self
    }
    private val methodImageInfoStorage by lazy {
        classImageFeatureService.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            returnType = classImageInfoStorage.clazz
        }.self
    }
    private val fieldChatroomStorage by lazy {
        chatroomService.reflekt().firstField {
            type = methodChatroomStorageGetMemberCount.method.declaringClass
        }.self
    }

    val emojiStorageMgr
        get() = methodEmojiStorageMgr.invoke(null)!!

    val emojiMgr
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classEmojiMgrImpl.clazz
            }.invoke(emojiFeatureService)!!

    val emojiMgrImpl
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!

    fun processEmojiPath(path: String): String {
        return emojiMgrImpl.reflekt().firstMethod {
            parameters(Context::class, BString)
            returnType = BString
        }.invoke(HostInfo.application, path)!! as String
    }

    fun saveEmojiThumb(path: String): Any {
        return emojiInfoStorage.reflekt().firstMethod {
            parameters(BString)
            returnType = methodSaveEmojiThumb.method.declaringClass
        }.invoke(path)!!
    }

    fun getEmojiMd5FromPath(context: Context, path: String): String {
        return emojiMgrImpl
            .reflekt()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String
    }

    val emojiInfoStorage
        get() = methodEmojiInfoStorage.invoke(emojiStorageMgr)!!

    fun getEmojiInfoByMd5(md5: String): Any {
        return emojiInfoStorage.reflekt()
            .firstMethod {
                parameters(BString)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!
    }

    val storageFeatureService
        get() = getServiceByClass(classStorageFeatureService.clazz)

    val msgInfoStorage
        get() = methodMsgInfoStorage.invoke(storageFeatureService)!!

    val chatroomService
        get() = getServiceImplByClass(classChatroomService.clazz.interfaces[0])

    val chatroomStorage
        get() = fieldChatroomStorage.get(chatroomService)!!

    fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    fun getServiceImplByClass(clazz: Class<*>): Any {
        return methodMmKernelGetServiceImpl.method.invoke(null, clazz)!!
    }

    fun getApiByClass(apiManager: Any, clazz: Class<*>): Any {
        return methodApiManagerGetApi.method.invoke(apiManager, clazz.interfaces[0])!!
    }

    val imageInfoStorage
        get() = methodImageInfoStorage.invoke(null)!!

    fun getImageMd5FromMsgInfo(msgInfo: MessageInfo): String {
        return imageInfoStorage.reflekt()
            .firstMethod {
                returnType = String::class
                parameters(WeMessageApi.classMsgInfo.clazz)
            }.invoke(msgInfo.instance)!! as String
    }

    val videoPathFeatureService
        get() = getServiceByClass(
            methodVideoPathFeatureServiceRestoreMp4Path
                .method.declaringClass
                .interfaces[0]
        )

    fun getVideoMp4PathFromMsgInfo(msgInfo: MessageInfo): String {
        return methodVideoPathFeatureServiceRestoreMp4Path.method.invoke(
            videoPathFeatureService, msgInfo.imagePath!!
        ) as String
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 零命中 / 多命中都只让本功能降级：抛异常会中断整轮 resolveDex。
        val classMmKernel = dexKit.findClass {
            matcher {
                usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.")
            }
        }.singleOrNull() ?: run {
            WeLogger.e(TAG, "MMKernel 类未找到或多命中，视频路径恢复不可用")
            return
        }

        methodMmKernelGetServiceImpl.find(dexKit) {
            matcher {
                declaredClass = classMmKernel.name
                paramTypes(Class::class.java)
            }
        }

        val results = dexKit.findMethod {
            // >= 8.0.61
            matcher {
                usingEqStrings("MicroMsg.C2CVideoPathFeatureService", "success restore file, from ", ".mp4")
            }
        }.ifEmpty {
            // < 8.0.61
            dexKit.findMethod {
                matcher {
                    usingEqStrings("MicroMsg.VideoInfoStorage", "success restore file, from ", ".mp4")
                }
            }
        }
        val restoreMethod = results.singleOrNull() ?: run {
            WeLogger.e(TAG, "视频路径恢复方法未找到或多命中（${results.size} 个），该能力不可用")
            return
        }
        methodVideoPathFeatureServiceRestoreMp4Path.setDescriptor(restoreMethod)
    }
}
