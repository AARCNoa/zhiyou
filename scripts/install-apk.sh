#!/usr/bin/env bash
# 安装模块包到设备，并规避「安装间隙被系统拉起」导致的假报错。
#
# 背景：install -r 期间系统会拉起微信，那一两个进程拿到的是正在被替换的 APK，
# 必然报 `UnsatisfiedLinkError: couldn't find "libdexkit.so"`。这不是缺陷，
# 但只要装完立刻 force-stop 就不会留下这两条日志。
#
# 用法：
#   scripts/install-apk.sh                      # 装默认的 legacy debug 包
#   scripts/install-apk.sh path/to/x.apk        # 装指定包
#   scripts/install-apk.sh -q                   # 只装不打印日志

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export MSYS_NO_PATHCONV=1

# adb 解析顺序：显式 $ADB → local.properties 的 sdk.dir（不入库）→
#              $ANDROID_HOME / $ANDROID_SDK_ROOT → PATH 里的 adb
SDK_CANDIDATES=()
if [ -f "$ROOT/local.properties" ]; then
  SDK_CANDIDATES+=("$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)")
fi
SDK_CANDIDATES+=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}")

if [ -z "${ADB:-}" ]; then
  for sdk in "${SDK_CANDIDATES[@]}"; do
    [ -n "$sdk" ] || continue
    for exe in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
      if [ -x "$exe" ]; then ADB="$exe"; break 2; fi
    done
  done
fi
ADB="${ADB:-adb}"

QUIET=0
APK=""
for arg in "$@"; do
  case "$arg" in
    -q|--quiet) QUIET=1 ;;
    *) APK="$arg" ;;
  esac
done

if [ -z "$APK" ]; then
  APK="$ROOT/app/build/outputs/apk/legacy/debug/app-legacy-arm64-v8a-debug.apk"
fi
[ -f "$APK" ] || { echo "找不到 APK: $APK" >&2; exit 1; }

echo "== 设备"
"$ADB" devices | tail -n +2 | grep -q "device$" || { echo "没有可用设备" >&2; exit 1; }

echo "== 唤醒并停止微信（避免安装间隙抢跑）"
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell input keyevent 82 >/dev/null 2>&1 || true
"$ADB" shell am force-stop com.tencent.mm || true

# 等进程真的退出再覆盖 APK。`am force-stop` 返回时进程可能还在收尾，
# 一旦 install 覆盖了 APK，这个进程就会拿着旧的 modulePath 去加载 native 库，
# 启动日志里留下「StartupAgent 跳过本次启动：模块 native 库不在 modulePath 下」。
for _ in $(seq 1 12); do
  [ -z "$("$ADB" shell pidof com.tencent.mm 2>/dev/null | tr -d '\r\n')" ] && break
  sleep 0.5
done

echo "== 安装 $(basename "$APK")"
# git-bash 的 /d/... 路径 Windows 版 adb 读不了，装机前转成盘符路径
APK_FOR_ADB="$APK"
command -v cygpath >/dev/null 2>&1 && APK_FOR_ADB="$(cygpath -w "$APK")"
# -d（allow-downgrade）：项目版本号已重置为 1.0 / versionCode 1，
# 从旧 v347 包覆盖安装属于降级，不加这个参数会被 INSTALL_FAILED_VERSION_DOWNGRADE 拒绝。
"$ADB" install -r -d "$APK_FOR_ADB" | tail -2

LOG="/sdcard/Android/data/com.tencent.mm/WCX/logs/wcx-$(date +%Y-%m-%d).log"
# 先记下当前行数：只统计本次启动新增的行。
# 旧实现用 tail -n 200，新包日志很少时会把上一版（或上一次启动）的错误算进来，
# 表现为「明明 0 错误却报 3 条」——排查时极易误判为本次改动引入。
BEFORE_LINES=$("$ADB" shell "wc -l < '$LOG' 2>/dev/null" | tr -d '\r\n' | tr -dc '0-9')
[ -z "$BEFORE_LINES" ] && BEFORE_LINES=0

echo "== 立刻再次停止，然后冷启动"
"$ADB" shell am force-stop com.tencent.mm || true
sleep 1
"$ADB" shell am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1 || true

if [ "$QUIET" -eq 0 ]; then
  echo "== 等待 20s 后汇总日志（只统计第 $((BEFORE_LINES + 1)) 行之后）"
  sleep 20
  echo "-- 本次启动 E/W 计数"
  "$ADB" shell "tail -n +$((BEFORE_LINES + 1)) '$LOG' | grep -c ' E/WCX'" || true
  "$ADB" shell "tail -n +$((BEFORE_LINES + 1)) '$LOG' | grep -c ' W/WCX'" || true
  echo "-- 关键行"
  "$ADB" shell "tail -n +$((BEFORE_LINES + 1)) '$LOG' | grep -aE 'dex cache|took|skipping|待适配'" || true
fi
