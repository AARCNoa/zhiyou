# ─── Xposed 入口（框架按【字符串类名】加载，必须保原名）──────────────
# assets/xposed_init            → com.AARCNoa.zhiyou.loader.entry.xp51.Xp51HookEntry
# META-INF/xposed/java_init.list → com.AARCNoa.zhiyou.loader.entry.lxp.LxpHookEntry
# 旧规则写成 com.AARCNoa.zhiyou.entry.**，与实际包名不匹配（等于没生效）。
-keep class com.AARCNoa.zhiyou.loader.entry.** { *; }
-keep class com.AARCNoa.zhiyou.application.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }

# ─── Feature / Hook 类 ──────────────────────────────────────────
-keep class com.AARCNoa.zhiyou.features.** { *; }
-keep,allowobfuscation class com.AARCNoa.zhiyou.hooks.** { *; }
-keep,allowobfuscation class com.AARCNoa.zhiyou.datas.** { *; }

-keepclassmembers,allowobfuscation class * {
    @com.AARCNoa.zhiyou.annotations.* *;
}

# ─── JNI：按符号名查找，类名与方法名不能改 ───────────────────────
-keep class org.luckypray.dexkit.DexKitBridge { *; }
-keep class me.yun.silk.** { *; }

# ─── Kotlin ─────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**
# kotlin-reflect 的反射路径无法静态分析，先保留内建表（约 0.9MB 字节码，可评估移除）
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Serialization ──────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.AARCNoa.zhiyou.**$$serializer { *; }
-keepclassmembers class com.AARCNoa.zhiyou.** {
    *** Companion;
}
-keepclasseswithmembers class com.AARCNoa.zhiyou.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.AnnotationsKt

# ─── Room ───────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Compose ────────────────────────────────────────────────────
# 不要全量 keep androidx.compose：Compose 自带 consumer rules，
# 全量 keep 会阻止 R8 删除未使用的 UI/运行时实现。
-dontwarn androidx.compose.**

# ─── 第三方（只压告警，允许 R8 优化）─────────────────────────────
-dontwarn io.netty.**
-dontwarn com.google.protobuf.**
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn com.materialkolor.**
-dontwarn miuix.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**
# dexkit 的可选依赖（原生 ndk 辅助类，运行时按需加载）
-dontwarn dev.rikka.ndk.thirdparty.**

# ─── 混淆增强 ────────────────────────────────────────────────────
-repackageclasses
-allowaccessmodification
-overloadaggressively

# ─── 属性 ───────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable

# ─── 资源名引用 ──────────────────────────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}
# R$plurals 必须整类保留：R8 会因字段被常量内联而移除该类，
# 但 Kotlin 对 pluralStringResource(R.plurals.*) 的编译引用仍指向它。
-keep class **.R$plurals { *; }

# Android Xml API 返回系统 XmlPullParser 实现，接口 ABI 不能重命名
-keep class org.xmlpull.v1.** { *; }

# Miuix Nav/Compose 从库字节码解析该 composition local
-keep class androidx.lifecycle.viewmodel.compose.** { *; }
