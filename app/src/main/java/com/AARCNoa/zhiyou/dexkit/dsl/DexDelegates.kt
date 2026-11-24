@file:Suppress("unused")

package com.AARCNoa.zhiyou.dexkit.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.AARCNoa.zhiyou.dexkit.DexMethodDescriptor
import com.AARCNoa.zhiyou.features.core.BaseFeature
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindField
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * 所有 Dex 委托的公共接口，用于统一缓存读写。
 * 每个委托负责自己的序列化/反序列化。
 */
sealed interface BaseDexDelegate {
    val key: String
    fun getDescriptorString(): String?

    /** 是否尚未解析成功（占位符或没有 descriptor）。统一给 FeaturesLoader 做失效汇总。 */
    val isPlaceholder: Boolean

    /** 从缓存字符串恢复状态 */
    fun loadDescriptor(value: String)

    /** 执行内联查找（如果是内联声明的话） */
    fun findInline(dexKit: DexKitBridge): Boolean = true
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexClassDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexClassDelegate>, BaseDexDelegate {

    private var descriptorString: String? = null
    private var cachedClass: Class<*>? = null
    internal var cachedData: ClassData? = null

    /**
     * 最近一次查找命中的**全部**候选类（含被选中的那个）。
     *
     * 用途：`allowMultiple = true` 的场景下，调用方需要自己按方法/字段签名
     * 从多个候选里挑出真正要用的那个（例：WeMessageApi:classImportMultiVideo
     * 在 8.0.79 上会同时命中「视频转码参数类」和「Uri 工具类」）。
     */
    internal var candidates: List<ClassData> = emptyList()
        private set

    val clazz: Class<*>
        get() {
            if (descriptorString == "com.tencent.mm.ui.LauncherUI")
                error("Class resolution has failed: $key")
            if (cachedClass == null && descriptorString != null)
                cachedClass = descriptorString!!.toClassOrNull()
            return cachedClass ?: error("Class not found for key: $key")
        }

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) {
        descriptorString = className
        cachedClass = null
        cachedData = null
    }

    @Suppress("unused")
    fun setDescriptor(c: ClassData) {
        setDescriptor(c.name)
    }

    fun setPlaceholderDescriptor() {
        WeLogger.d("DexClassDelegate", "placeholder: $key")
        setDescriptor("com.tencent.mm.ui.LauncherUI")
    }

    /**
     * 是否**不可用**（占位符 或 **尚未解析**）。
     *
     * 把 `descriptor == null` 也算进来是关键：特征解析还没跑（缓存未建立、
     * await re-resolution）时委托根本没有 descriptor，此时 `isPlaceholder` 若仍返回
     * false，调用方的守卫会放行，接着 `.clazz` / `.method` 就抛
     * `Class/Method not found for key: ...`（8.0.76 实测一个 key 被反复抛 15+ 次）。
     */
    override val isPlaceholder
        get() = descriptorString == null || descriptorString == "com.tencent.mm.ui.LauncherUI"

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    /**
     * 查找 Dex 类。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        multipleIndex: Int = 0,
        block: FindClass.() -> Unit
    ): Boolean = findWithFallback(
        key = key,
        tag = "DexClassDelegate",
        kind = "class",
        allowMultiple = allowMultiple,
        allowFailure = allowFailure,
        index = multipleIndex,
        finder = { dexKit.findClass(block).also { candidates = it } },
        describe = { it.name },
        apply = { setDescriptor(it.name) },
        markPlaceholder = { setPlaceholderDescriptor() },
    )

    fun getClassData(dexKit: DexKitBridge): ClassData =
        dexKit.findClassData(getDescriptorString()!!)!!

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexClassDelegate = this
}

// ---------------------------------------------------------------------------
// DexFieldDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 字段委托 — 自动生成 Key，自动反射获取 Field。
 */
class DexFieldDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexFieldDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexFieldDelegate>, BaseDexDelegate {

    private var descriptorString: String? = null
    private var cachedField: Field? = null
    internal var cachedData: FieldData? = null

    val field: Field
        get() {
            if (descriptorString == PLACEHOLDER_DESCRIPTOR)
                error("Field resolution has failed: $key")
            if (cachedField == null && descriptorString != null)
                cachedField = getFieldInstance(descriptorString!!)
            return cachedField ?: error("Field not found for key: $key")
        }

    fun setDescriptor(desc: String) {
        descriptorString = desc
        cachedField = null
        cachedData = null
    }

    @Suppress("unused")
    fun setDescriptor(f: FieldData) {
        setDescriptor(f.descriptor)
    }

    fun setPlaceholderDescriptor() {
        WeLogger.d("DexFieldDelegate", "placeholder: $key")
        setDescriptor(PLACEHOLDER_DESCRIPTOR)
    }

    /** 是否不可用（占位符 或 尚未解析 —— 未解析时 `.field` 同样会抛错）。 */
    override val isPlaceholder
        get() = descriptorString == null || descriptorString == PLACEHOLDER_DESCRIPTOR

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindField.() -> Unit
    ): Boolean = findWithFallback(
        key = key,
        tag = "DexFieldDelegate",
        kind = "field",
        allowMultiple = allowMultiple,
        allowFailure = allowFailure,
        index = resultIndex,
        finder = { dexKit.findField(block) },
        describe = { "${it.className}::${it.fieldName}" },
        apply = { setDescriptor(it.descriptor) },
        markPlaceholder = { setPlaceholderDescriptor() },
    )

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexFieldDelegate = this

    private fun getFieldInstance(descriptor: String): Field {
        val arrow = descriptor.indexOf("->")
        val colon = descriptor.indexOf(':', arrow)
        require(arrow >= 0 && colon >= 0) { descriptor }
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val fieldName = descriptor.substring(arrow + 2, colon)
        var current: Class<*>? = ClassLoaders.HOST.loadClass(className)
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(descriptor)
    }

    companion object {
        private const val PLACEHOLDER_DESCRIPTOR =
            "Lcom/tencent/mm/ui/LauncherUI;->INSTANCE:Lcom/tencent/mm/ui/LauncherUI;"
    }
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexMethodDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexMethodDelegate>, BaseDexDelegate {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedMethod: Method? = null
    internal var cachedData: MethodData? = null

    /**
     * 最近一次查找命中的**全部**方法候选（含被选中的那个）。
     *
     * 用途：`allowMultiple = true` 时调用方可以自行处置多个命中，典型两类：
     * - 择优：按参数/返回类型签名校验挑出真正的目标（如 ImportMultiVideo）
     * - 全 hook：谓词类方法的多个重载都该处理（如「是否占用」检查，见 RemoveLimitsDuringCalls）
     */
    internal var candidates: List<MethodData> = emptyList()
        private set

    /** 全部候选转成可反射 hook 的 java Method；解析失败的候选自动丢弃。 */
    fun candidateMethods(): List<Method> = runCatching {
        val resolved = candidates.mapNotNull { cd ->
            runCatching {
                DexMethodDescriptor(cd.className, cd.methodName, cd.methodSign)
                    .getMethodInstance(ClassLoaders.HOST)
            }.getOrNull()
        }
        if (resolved.isNotEmpty()) return@runCatching resolved

        // 缓存命中时只走 loadDescriptor，find() 不会执行，candidates 永远是空的。
        // 这里必须按描述符回退一个方法：否则调用方全部「no candidate method resolved」
        // 而跳过 hook —— 表现为首次启动正常、之后每次启动功能都不生效（实测
        // 自定义好友本地头像、拦截来电铃声、通话限制都踩到这一点）。
        val desc = descriptor ?: return@runCatching emptyList()
        if (desc.descriptor == PLACEHOLDER_METHOD_DESCRIPTOR) return@runCatching emptyList()
        listOfNotNull(runCatching { desc.getMethodInstance(ClassLoaders.HOST) }.getOrNull())
    }.getOrDefault(emptyList())

    val method: Method
        get() {
            // 占位符必须按完整描述符比较。DexMethodDescriptor.name 只是方法名（如 "getInstance"），
            // 拿它跟整串描述符比永远不会相等，占位符就会被当成"已解析"放行，最后 hook 到
            // LauncherUI.getInstance() 这个高频零参方法上。
            if (descriptor != null && descriptor!!.descriptor == PLACEHOLDER_METHOD_DESCRIPTOR)
                error("Method resolution has failed: $key")
            if (cachedMethod == null && descriptor != null)
                cachedMethod = descriptor!!.getMethodInstance(ClassLoaders.HOST)
            return cachedMethod ?: error("Method not found for key: $key")
        }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedMethod = null
        cachedData = null
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun setDescriptor(m: MethodData) = setDescriptor(DexMethodDescriptor(m.className, m.methodName, m.methodSign))

    /** 不可用：占位符或尚未解析。`descriptor == null` 为何必须算进来，见 [DexClassDelegate.isPlaceholder]。 */
    override val isPlaceholder
        get() = descriptor == null || descriptor!!.descriptor == PLACEHOLDER_METHOD_DESCRIPTOR

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    fun setPlaceholderDescriptor() {
        WeLogger.d("DexMethodDelegate", "placeholder: $key")
        setDescriptor(DexMethodDescriptor(PLACEHOLDER_METHOD_DESCRIPTOR))
    }

    companion object {
        /**
         * 解析失败时的占位方法。用 LauncherUI.getInstance() 是因为它一定存在、
         * 一定零参，任何 `parameterCount` 校验都能一眼识别出来。
         */
        const val PLACEHOLDER_METHOD_DESCRIPTOR =
            "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"
    }

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedMethod = null
        cachedData = null
    }

    /**
     * 查找 Dex 方法。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean = findWithFallback(
        key = key,
        tag = "DexMethodDelegate",
        kind = "method",
        allowMultiple = allowMultiple,
        allowFailure = allowFailure,
        index = resultIndex,
        finder = { dexKit.findMethod(block).also { candidates = it } },
        describe = { "${it.className}::${it.methodName}" },
        apply = { setDescriptor(DexMethodDescriptor(it.className, it.methodName, it.methodSign)) },
        markPlaceholder = { setPlaceholderDescriptor() },
    )

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexMethodDelegate = this
}

// ---------------------------------------------------------------------------
// DexConstructorDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 构造函数委托 — 自动生成 Key，自动反射获取 Constructor。
 */
class DexConstructorDelegate internal constructor(
    override val key: String,
    private val inlineBlock: ((DexConstructorDelegate, DexKitBridge) -> Boolean)? = null
) : ReadOnlyProperty<BaseFeature, DexConstructorDelegate>, BaseDexDelegate {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedConstructor: Constructor<*>? = null
    internal var cachedData: MethodData? = null

    val constructor: Constructor<*>
        get() {
            // 与 DexMethodDelegate.method 同样的守卫：占位符必须按完整描述符识别，
            // 否则 newInstance() 会去实例化 LauncherUI.getInstance()，报错信息还极具误导性。
            if (descriptor != null &&
                descriptor!!.descriptor == DexMethodDelegate.PLACEHOLDER_METHOD_DESCRIPTOR
            )
                error("Constructor resolution has failed: $key")
            if (cachedConstructor == null && descriptor != null)
                cachedConstructor = descriptor!!.getConstructorInstance(ClassLoaders.HOST)
            return cachedConstructor ?: error("Constructor not found for key: $key")
        }

    /** 是否不可用（占位符 或 尚未解析 —— 未解析时 `.constructor` 同样会抛错）。 */
    override val isPlaceholder
        get() = descriptor == null ||
                descriptor!!.descriptor == DexMethodDelegate.PLACEHOLDER_METHOD_DESCRIPTOR

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedConstructor = null
        cachedData = null
    }

    fun setPlaceholderDescriptor() {
        WeLogger.d("DexConstructorDelegate", "placeholder: $key")
        setDescriptor(DexMethodDescriptor("Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"))
    }

    @Suppress("unused")
    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedConstructor = null
        cachedData = null
    }

    /**
     * 查找 Dex 构造函数。结果直接写入委托自身。
     */
    fun find(
        dexKit: DexKitBridge,
        allowMultiple: Boolean = false,
        allowFailure: Boolean = false,
        resultIndex: Int = 0,
        block: FindMethod.() -> Unit
    ): Boolean = findWithFallback(
        key = key,
        tag = "DexConstructorDelegate",
        kind = "constructor",
        allowMultiple = allowMultiple,
        allowFailure = allowFailure,
        index = resultIndex,
        finder = {
            dexKit.findMethod {
                block()
                if (matcher == null) matcher { name = "<init>" } else matcher!!.name = "<init>"
            }
        },
        describe = { "${it.className}::<init>" },
        apply = { setDescriptor(DexMethodDescriptor(it.className, "<init>", it.methodSign)) },
        markPlaceholder = { setPlaceholderDescriptor() },
    )

    override fun findInline(dexKit: DexKitBridge): Boolean {
        return inlineBlock?.invoke(this, dexKit) ?: true
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 Feature
// ---------------------------------------------------------------------------

/**
 * 创建 dexConstructor 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexConstructor(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexField 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key).also { item.registerDexDelegate(it) }
    }

@Suppress("NOTHING_TO_INLINE")
inline fun DexKitBridge.findClassData(clazz: String): ClassData? =
    findClass { matcher { className = clazz } }.singleOrNull()

// ---------------------------------------------------------------------------
// 内联查找委托工厂函数
// ---------------------------------------------------------------------------

/**
 * 创建带有内联查找逻辑的 dexConstructor 委托
 */
fun dexConstructor(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexConstructorDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexClass 委托
 */
fun dexClass(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    multipleIndex: Int = 0,
    block: FindClass.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexClassDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, multipleIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexField 委托
 */
fun dexField(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindField.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexFieldDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 创建带有内联查找逻辑的 dexMethod 委托
 */
fun dexMethod(
    allowMultiple: Boolean = false,
    allowFailure: Boolean = false,
    resultIndex: Int = 0,
    block: FindMethod.() -> Unit
): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = "${item::class.simpleName}:${property.name}"
        DexMethodDelegate(key) { delegate, dexKit ->
            delegate.find(dexKit, allowMultiple, allowFailure, resultIndex, block)
        }.also { item.registerDexDelegate(it) }
    }

/**
 * 四个委托共用的查找骨架。
 *
 * 原先每个委托各写一遍「try → 空结果 → 多结果 → 取第 N 个」，四份之间已经出现分歧：
 * 构造函数在异常/空结果时**不设占位符**，类委托在 `allowFailure` 时也不设。
 * 这里统一为：**任何失败都落占位符**，保证 `isPlaceholder` 为真、上层守卫必定跳过挂载 ——
 * 比保留旧描述符去挂一个已失效锚点安全（旧行为在「重新解析失败」时会带着过期锚点继续挂）。
 *
 * @param index 取第几个候选（越界由 [safeIndex] 夹紧）
 * @param finder 实际调用 DexKit 的查找
 * @param describe 单条候选的可读标识，用于「命中多个」时的日志与异常信息
 * @param apply 选中一条后写入委托
 * @param markPlaceholder 失败时把委托降级为占位符
 */
private fun <T> findWithFallback(
    key: String,
    tag: String,
    kind: String,
    allowMultiple: Boolean,
    allowFailure: Boolean,
    index: Int,
    finder: () -> List<T>,
    describe: (T) -> String,
    apply: (T) -> Unit,
    markPlaceholder: () -> Unit,
): Boolean {
    val results = try {
        finder()
    } catch (e: Exception) {
        WeLogger.w(tag, "DexKit find$kind failed for key: $key, error: ${e.message}")
        markPlaceholder()
        return false
    }

    if (results.isEmpty()) {
        if (!allowFailure) WeLogger.w(tag, "DexKit: No $kind found for key: $key")
        markPlaceholder()
        return false
    }

    if (results.size > 1 && !allowMultiple) {
        if (allowFailure) {
            WeLogger.w(
                tag,
                "DexKit: Multiple ${kind}s found for key: $key, count: ${results.size}, " +
                    "using first match: ${describe(results[0])}",
            )
            apply(results[0])
            return true
        }
        error(
            "DexKit: Multiple ${kind}s found for key: $key, count: ${results.size}, " +
                "candidates: ${results.joinToString(",") { describe(it) }}"
        )
    }

    apply(results[safeIndex(results, index, key, kind)])
    return true
}

/**
 * 防止 resultIndex / multipleIndex 越界导致整个 Dex 扫描崩溃。
 * 越界时记录日志并回退到最后一个有效结果。
 */
private fun <T> safeIndex(results: List<T>, index: Int, key: String, kind: String): Int {
    if (index < results.size) return index
    WeLogger.w(
        "DexDelegate",
        "resultIndex $index out of bounds (size=${results.size}) for $kind key=$key, clamping to last"
    )
    return results.lastIndex.coerceAtLeast(0)
}

// ---- 上游兼容：`.data` DexKit metadata 访问器（合并进来的官方功能会用到）----

val DexClassDelegate.data: org.luckypray.dexkit.result.ClassData
    get() = cachedData ?: com.AARCNoa.zhiyou.dexkit.resolution.DexResolutionContext.dexKit
        .getClassData(getDescriptorString()!!)!!.also { cachedData = it }

val DexMethodDelegate.data: org.luckypray.dexkit.result.MethodData
    get() = cachedData ?: com.AARCNoa.zhiyou.dexkit.resolution.DexResolutionContext.dexKit
        .getMethodData(getDescriptorString()!!)!!.also { cachedData = it }

val DexConstructorDelegate.data: org.luckypray.dexkit.result.MethodData
    get() = cachedData ?: com.AARCNoa.zhiyou.dexkit.resolution.DexResolutionContext.dexKit
        .getMethodData(getDescriptorString()!!)!!.also { cachedData = it }

val DexFieldDelegate.data: org.luckypray.dexkit.result.FieldData
    get() = cachedData ?: com.AARCNoa.zhiyou.dexkit.resolution.DexResolutionContext.dexKit
        .getFieldData(getDescriptorString()!!)!!.also { cachedData = it }
