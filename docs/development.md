# 开发与构建

## 环境

- JDK 21、Android SDK（compileSdk 37）、NDK（版本见 `gradle/libs.versions.toml`）
- SDK 路径写在 `local.properties` 的 `sdk.dir`，该文件不入库（`.gitignore` 已排除）
- 依赖缓存可用独立的 `GRADLE_USER_HOME` 与默认环境隔离；这些路径都属于本机配置，不写进仓库
- Rust 只用于重建 `libwekit_native.so`：源码在 `app/src/main/rust/wekit-native/`，
  Gradle **不调 cargo**；改 Rust 需自己 `cargo build` 后替换 `app/src/main/jniLibs/*/libwekit_native.so`

## 构建

```bash
./gradlew :app:assembleLegacyDebug     # 日常迭代（debug 签名）
./gradlew :app:assembleLegacyRelease   # R8 压缩，约 15~25 分钟
```

| 变体 | 入口 | 适用 |
|---|---|---|
| `standard` | libxposed API 101/102 + `META-INF/xposed/*` | 大多数框架 |
| `legacy` | `Xp51HookEntry` + `assets/xposed_init` | libxposed 兼容性差的设备 |

两个变体只差入口源码集。产物：

```
app/build/outputs/apk/legacy/debug/app-legacy-arm64-v8a-debug.apk
app/build/outputs/apk/legacy/release/app-legacy-arm64-v8a-release-unsigned.apk
```

## 版本与发布

- `versionCode` / `versionName` 在 `app/build.gradle.kts` **手工维护**（当前 `2` / `"1.1"`），
  发版只改这两行，`versionCode` 只能加不能减。
- 应用内更新读仓库根的 `update.json`：**每次发版必须同步它的 `versionCode` 与下载地址**，
  否则客户端收不到提示。发版时按顺序改三处：`build.gradle.kts` 的版本号 → `update.json` 的
  `versionCode`/`versionName`/下载地址 → 提交并推送；清单不更新，客户端就一直认为没有新版。
- `BuildConfig.BRAND` 是对外显示名（取「知友」），标题、设置入口、关于页一律用它。
- `BuildConfig.TAG` 固定 `WCX`，**不要改**：日志目录 `/sdcard/Android/data/<宿主>/WCX/`、
  MMKV 文件名与 Intent key 都依赖它。改名会迁移数据目录并丢掉 dex 缓存。
- 签名顺序：`WEKIT_KEYSTORE_*` 环境变量 → gradle 属性 → `~/wcx-keystore.jks` 等；
  都找不到时 **不签名，也不回退 debug 证书**。CI 用 secrets 里的固定 keystore，缺失即不出包。

## 安装与验证

```bash
bash scripts/install-apk.sh                        # 默认 legacy debug 包
bash scripts/install-apk.sh path/to/xxx.apk        # 指定包
```

脚本行为：唤醒屏幕 → force-stop → `install -r` → 再 force-stop → 冷启动 → 汇总 `E/WCX`、`W/WCX`。

- 必须装完再 force-stop 一次，否则新进程会用旧 APK 路径加载 `.so`；
- 日志：`/sdcard/Android/data/<宿主包名>/WCX/logs/wcx-YYYY-MM-DD.log`；
- `logcat -c` 会清掉现场，非必要不清。
