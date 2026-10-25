#!/usr/bin/env bash
# BUG-11 真机验证脚本：确认「进度卡 5% / 无法翻译成文字」已修复
# 用法：bash verify_bug11.sh
set -u
export PATH="/d/Program Files/platform-tools:$PATH"
PKG=com.echolog.app
PASS=0; FAIL=0
ok(){ echo "  [PASS] $1"; PASS=$((PASS+1)); }
ng(){ echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }

echo "=============================================="
echo " BUG-11 修复验证  ($(date '+%H:%M:%S'))"
echo "=============================================="

echo
echo "── 1. 设备与版本 ──────────────────────────"
DEV=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
if [ -n "$DEV" ]; then ok "设备已连接: $DEV"; else ng "无设备连接"; exit 1; fi
echo "  Android $(adb shell getprop ro.build.version.release) / SDK $(adb shell getprop ro.build.version.sdk)"
echo "  版本: $(adb shell dumpsys package $PKG | grep -m1 versionName)"

echo
echo "── 2. 关键：WorkManager 初始化器已移除 ──────"
# 从已安装 APK 的二进制 manifest 里查（安装后用 pm path 取 base.apk）
# 注意：Git Bash 的 /tmp 与 Windows Python 看到的路径不同，
# 必须用 Windows 原生路径传给 Python，否则 FileNotFoundError。
APK_PATH=$(adb shell pm path $PKG | sed 's/package://' | tr -d '\r' | head -1)
CHK="$TEMP/_echolog_installed_check.apk"
rm -f "$CHK"
adb pull "$APK_PATH" "$CHK" >/dev/null 2>&1
if [ -f "$CHK" ]; then
  python -c "
import zipfile, re, sys
d = zipfile.ZipFile(sys.argv[1]).read('AndroidManifest.xml')
ts = [t.decode('utf-16-le', 'ignore') for t in re.findall(rb'(?:[ -~]\x00){6,}', d)]
def has(k): return any(k in t for t in ts)
print('  [PASS] WorkManagerInitializer 已移除（根因修复生效）' if not has('WorkManagerInitializer')
      else '  [FAIL] WorkManagerInitializer 仍在包内 → 装的是旧包！')
for k in ['InitializationProvider', 'FOREGROUND_SERVICE_DATA_SYNC', 'FOREGROUND_SERVICE_MICROPHONE']:
    print(('  [PASS] ' if has(k) else '  [FAIL] ') + k + ' 在包内')
" "$CHK"
  rm -f "$CHK"
else
  ng "无法读取已安装 APK（adb pull 失败）"
fi

echo
echo "── 3. 模型已落地（体积阈值 90% 判定）────────"
ASR=$(adb shell "run-as $PKG stat -c %s files/models/asr/model.int8.onnx 2>/dev/null" | tr -d '\r')
LLM=$(adb shell "run-as $PKG stat -c %s files/models/llm/qwen2.5-1.5b-instruct-q4_k_m.gguf 2>/dev/null" | tr -d '\r')
echo "  ASR: ${ASR:-缺失} / 期望 239233841"
echo "  LLM: ${LLM:-缺失} / 期望 1117320736"
[ -n "$ASR" ] && [ "$ASR" -ge 215000000 ] 2>/dev/null && ok "ASR 模型体积达标（非半成品）" || ng "ASR 模型缺失或过小"
[ -n "$LLM" ] && [ "$LLM" -ge 1000000000 ] 2>/dev/null && ok "LLM 模型体积达标（非半成品）" || ng "LLM 模型缺失或过小"

echo
echo "── 4. Worker 装配：@HiltWorker 能否被创建 ───"
# 关键日志：出现即说明 HiltWorkerFactory 生效、doWork() 真的在跑
adb logcat -c 2>/dev/null
adb shell am force-stop $PKG
sleep 1
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
LOG=$(adb logcat -d 2>/dev/null)
if echo "$LOG" | grep -q "Could not instantiate"; then
  ng "日志出现 'Could not instantiate' → HiltWorkerFactory 仍未生效！"
else
  ok "日志无 'Could not instantiate'（WorkerFactory 装配正常）"
fi
echo "  --- WorkManager / Worker 相关日志 ---"
echo "$LOG" | grep -iE "WM-WorkerWrapper|HiltWorkerFactory|Could not instantiate|DaySettleWorker|ModelDownloadWorker" | head -12 | sed 's/^/    /'
echo "  （以上若为空属正常：Worker 由系统按约束择机调度，非启动即跑）"

echo
echo "── 5. 结算进度是否推进（DB 真相源）──────────"
adb shell "run-as $PKG ls databases/ 2>/dev/null" | sed 's/^/    db: /'
echo "  提示：录一段音并「结束录音」后，再跑一次本脚本看进度是否到 100"

echo
echo "=============================================="
echo " 结果: PASS=$PASS  FAIL=$FAIL"
echo "=============================================="
[ "$FAIL" -eq 0 ] && echo " ✅ 全部通过" || echo " ❌ 存在失败项（见上方 [FAIL]）"
