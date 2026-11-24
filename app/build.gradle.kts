import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

android {
    namespace = libs.versions.namespace.get()
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = libs.versions.namespace.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 版本号单一数据源：CI（.github/workflows/ci.yml）直接读取此处，
        // 发版时只需在此递增 versionCode / versionName
        versionCode = 2
        versionName = "1.1"

        // TAG 是**内部标识**：日志 tag、数据目录（/sdcard/.../WCX）、Intent extra key 都用它。
        // 改名会迁移整个数据目录并丢掉已有 dex 缓存，所以品牌改名只走 BRAND，不动 TAG。
        buildConfigField("String", "TAG", "\"WCX\"")
        // BRAND 是**对外显示名**：标题、设置入口、关于页等所有用户可见处一律用它。
        buildConfigField("String", "BRAND", "\"知友\"")
        buildConfigField("boolean", "BEAUTIFY_ENABLED", "true")
    }

    splits {
        abi {
            reset()
            isEnable = true
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Two entry-point variants:
    //  - standard: ships the modern libxposed entry point (entry/lxp/* sources +
    //              META-INF/xposed/*), placed in the `standard` flavor source set.
    //  - legacy:   omits both, so frameworks with poor libxposed compatibility fall
    //              back to the traditional de.robv entry (Xp51HookEntry via
    //              assets/xposed_init, which lives in `main` and is shared by both).
    flavorDimensions += "entrypoint"
    productFlavors {
        create("standard") {
            dimension = "entrypoint"
            // ships the libxposed entry point (entry/lxp/* + META-INF/xposed/*)
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "true")
            buildConfigField("String", "FLAVOR_SLUG", "\"standard\"")
        }
        create("legacy") {
            dimension = "entrypoint"
            // no libxposed entry; framework falls back to the de.robv api
            buildConfigField("boolean", "HAS_LIBXPOSED_ENTRY", "false")
            buildConfigField("String", "FLAVOR_SLUG", "\"legacy\"")
        }
    }

    sourceSets["main"].jniLibs.directories += "src/main/jniLibs"

    var foundKeystore = false

    // ─── 签名配置 ──────────────────────────────────────────────────────────
    // 取值优先级：
    //   1. 环境变量 WEKIT_KEYSTORE_FILE / _PASSWORD / KEY_ALIAS / KEY_PASSWORD
    //   2. 同名 gradle 属性（-PWEKIT_KEYSTORE_PASSWORD=…）
    //   3. local.properties 里的同名 key（本机使用，该文件不入库）
    //   4. keystore 默认位置：~/wcx-keystore.jks → ~/.wcx/wcx-keystore.jks → <rootProject>/wcx-keystore.jks
    //
    // **密码没有默认值**：仓库里不保存任何口令。取不到密码就退回 debug 签名，
    // 需要正式包时在本机 shell 或 local.properties 里提供。
    // ────────────────────────────────────────────────────────────────────────
    @Suppress("LocalVariableName")
    signingConfigs {
        fun localProp(name: String): String? = runCatching {
            val f = rootProject.file("local.properties")
            if (!f.exists()) null
            else Properties().apply { f.inputStream().use { load(it) } }.getProperty(name)
        }.getOrNull()

        fun resolveProp(name: String): String? =
            System.getenv(name)
                ?: runCatching { project.property(name) }.getOrNull() as? String?
                ?: localProp(name)

        val home = System.getProperty("user.home") ?: ""
        val defaultStore = listOf("$home/wcx-keystore.jks", "$home/.wcx/wcx-keystore.jks")
            .firstOrNull { File(it).exists() }
            ?: rootProject.file("wcx-keystore.jks").takeIf { it.exists() }?.absolutePath

        val _storeFile = resolveProp("WEKIT_KEYSTORE_FILE") ?: defaultStore
        val _storePassword = resolveProp("WEKIT_KEYSTORE_PASSWORD")
        val _keyAlias = resolveProp("WEKIT_KEY_ALIAS") ?: "wcx"
        val _keyPassword = resolveProp("WEKIT_KEY_PASSWORD") ?: _storePassword

        val existingStore = _storeFile?.takeIf { File(it).exists() }
        if (existingStore != null && _storePassword != null) {
            create("release") {
                foundKeystore = true
                storeFile = file(existingStore)
                storePassword = _storePassword
                keyAlias = _keyAlias
                keyPassword = _keyPassword

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName(if (foundKeystore) "release" else "debug")
        }

        release {
            isMinifyEnabled = !project.hasProperty("disableMinify")
            isShrinkResources = !project.hasProperty("disableMinify")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 用户要求：release 永远使用固定签名，禁止回退 debug keystore
            // （避免产出无法覆盖安装的包）。未找到固定签名时不设 signingConfig
            // （产物未签名），并由 CI 的「Verify APK signatures」步骤兜底拦下。
            if (foundKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("未找到固定签名 keystore：release 将不签名（已禁用 debug 兜底）。请检查 WEKIT_KEYSTORE_FILE 或项目根 wcx-keystore.jks。\n")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get().toInt())
    }

    packaging {
        // 严禁开启 jniLibs.useLegacyPackaging：LSPosed 在微信进程内通过 System.loadLibrary
        // 从模块 APK 直接 mmap 加载 .so，该机制只对 STORED(未压缩) 生效。开启后 .so 变
        // DEFLATED，System.loadLibrary 抛 UnsatisfiedLinkError → NativeLoader 类初始化失败
        // → 整个模块在初始化阶段崩溃，微信里看不到任何模块痕迹，LSPosed 日志也无记录。
        resources.excludes += listOf(
            "kotlin/**",
            "**.bin",
            "kotlin-tooling-metadata.json",
            "META-INF/INDEX.LIST"
        )
        resources.merges += listOf(
            "META-INF/io.netty.versions.properties",
            "META-INF/xposed/*"
        )
    }

    @Suppress("UnstableApiUsage")
    androidResources {
        localeFilters += setOf("zh")
    }

    buildFeatures {
        resValues = false
        compose = true
        buildConfig = true
    }

    lint {
        // 默认 locale values/ 仅有少量 key，完整字符串位于 values-zh-rCN；
        // androidResources.localeFilters=zh 打包时只保留中文资源，ExtraTranslation 属误报。
        disable += "ExtraTranslation"
    }
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.jdk.get()))
    }
}

val adbProvider = androidComponents.sdkComponents.adb
androidComponents {
    onVariants { variant ->
        val kotlinSources = variant.sources.kotlin ?: return@onVariants

        kotlinSources.addGeneratedSourceDirectory(
            generateMethodHashes,
            GenerateMethodHashesTask::getOutputDir
        )

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val embedAboutLibraries = tasks.register<EmbedAboutLibrariesTask>("embedAboutLibraries$variantName") {
            group = "wekit"
            description = "Embed aboutlibraries.json as a String constant for $variantName"

            val aboutLibrariesJson = layout.buildDirectory.file("generated/aboutLibraries/${variant.name}/res/raw/aboutlibraries.json")
            inputFile.set(aboutLibrariesJson)
            outputDir.set(layout.buildDirectory.dir("generated/source/aboutlibraries/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        embedAboutLibraries.configure {
            dependsOn(tasks.named("prepareLibraryDefinitions$variantName"))
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedAboutLibraries,
            EmbedAboutLibrariesTask::getOutputDir
        )

        val embedEruda = tasks.register<EmbedErudaTask>("embedEruda$variantName") {
            group = "wekit"
            description = "Embed eruda.min.js as a String constant for $variantName"

            url.set("https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js")
            outputDir.set(layout.buildDirectory.dir("generated/source/eruda/${variant.name}"))
            namespace.set(libs.versions.namespace.get())
        }

        kotlinSources.addGeneratedSourceDirectory(
            embedEruda,
            EmbedErudaTask::getOutputDir
        )

    }
}

// --- tasks ---

val generateMethodHashes = tasks.register<GenerateMethodHashesTask>("generateMethodHashes") {
    description = "Generate resolveDex() method hashes"
    group = "wekit"
    sourceDir.set(file("src/main/java"))
    outputDir.set(layout.buildDirectory.dir("generated/source/methodhashes"))
    namespace.set(libs.versions.namespace.get())
}

// --- end tasks ---


ksp {
    // Room schema export for migration diffing
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.profileinstaller)
    // miuix 0.9.4 内置 aar（对齐上游 app/libs；maven 0.9.3 API 与上游代码不兼容）
    implementation(files("libs/miuix-ui-android-0.9.4.aar"))
    implementation(files("libs/miuix-core-android-0.9.4.aar"))
    implementation(files("libs/miuix-squircle-android-0.9.4.aar"))
    implementation(files("libs/miuix-preference-android-0.9.4.aar"))
    implementation(files("libs/miuix-nav-android-0.9.4.aar"))
    implementation(files("libs/miuix-blur-android-0.9.4.aar"))
    implementation(files("libs/miuix-shader-android-0.9.4.aar"))
    implementation(libs.materialkolor)
    implementation(libs.coil)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.google.protobuf.javalite)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.mmkv)
    implementation("dev.rikka.ndk.thirdparty:cxx:1.2.0")
    // dexmaker 的传递依赖要 dalvik-dx 9.0.0_r3（镜像上已不可用），显式提到 16.0.1 覆盖
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

    // 内置 Xposed API 82：Maven Central / 腾讯镜像都没有该构件，只能从 api.xposed.info 拉，
    // 而该源在 CI 上偶发超时会导致整个 master 构建失败（缓存丢失时必现）。内置后构建可复现。
    compileOnly(files("libs/xposed-api-82.jar"))
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(files("libs/dexkit-2.2.0-76551eb-idle.aar"))
    // DexKit 的 query/schema 层直接依赖 flatbuffers（BatchFindClassUsingStrings、AnnotationEncodeArray）。
    // 缺了它 debug 能编译通过（R8 关闭时不校验缺失类），但 release R8 会报 Missing class，
    // 且一旦走到对应查询路径就是 NoClassDefFoundError。
    implementation("com.google.flatbuffers:flatbuffers-java:23.5.26")
    implementation(libs.hiddenapibypass)
    implementation(project(":libs:common:reflekt"))
    implementation(libs.libsu.core)
    implementation(libs.dexmaker)
//    implementation(libs.arsclib)
//    implementation(libs.apksig)
//    implementation(libs.bouncycastle.prov)
//    implementation(libs.bouncycastle.pkix)
    @Suppress("AvoidDuplicateDependencies")
    implementation(project(":libs:common:annotation-scanner"))
    @Suppress("AvoidDuplicateDependencies")
    ksp(project(":libs:common:annotation-scanner"))

    implementation(libs.okhttp3.okhttp)


    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)

    implementation(libs.mcp.server)
    implementation(libs.mcp.client)
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.osmdroid.android)

    compileOnly(project(":libs:common:stubs"))
}

// markwon conflict
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")

//    resolutionStrategy {
//        force("androidx.compose.ui:ui:1.12.0-beta01")
//        force("androidx.compose.ui:ui-android:1.12.0-beta01")
//        force("androidx.compose.material3:material3:1.5.0-alpha21")
//        force("androidx.compose.material3:material3-android:1.5.0-alpha21")
//    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
