#!/bin/bash
# 装 APK 到三星手机 —— 只用这一个脚本，别直接敲 adb install
#
# 为什么必须加 --user 0：
#   这台 S26 Ultra 开着「应用双开」(User 95: DUAL_APP)。
#   `adb install -r` 默认会装到**所有**用户空间，于是双开空间里也有一份，
#   桌面上就会出现两个 i记账 图标。卸载 user 95 也没用 —— 下次升级又装回来。
#
# 用法： ./scripts/install.sh /tmp/i-accounting-vX.Y.apk
set -euo pipefail

APK="${1:-}"
[ -n "$APK" ] || { echo "用法: $0 <apk 路径>"; exit 1; }
[ -f "$APK" ] || { echo "✗ 找不到 $APK"; exit 1; }

ADB="${ADB:-$HOME/.hermes/bin/adb35}"
[ -x "$ADB" ] || ADB=adb   # 兜底用 PATH 里的

PKG=com.qinkun.accountbook

echo "==> 安装到主空间（不动双开空间）"
"$ADB" install --user 0 -r "$APK"

echo "==> 双开空间若被顺带装上，立刻清掉"
if "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -qE "^ *User 95:.*installed=true"; then
  "$ADB" shell pm uninstall --user 95 "$PKG" >/dev/null 2>&1 || true
  echo "    已清除 User 95"
fi

echo "==> 核对"
"$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|^    User (0|95):" | head -3

# 主空间流水条数（防装完数据没了却不知道）
N=$("$ADB" exec-out run-as "$PKG" cat databases/accountbook.db 2>/dev/null \
    | python3 -c "
import sys,sqlite3,tempfile,os
d=sys.stdin.buffer.read()
if not d: print('读不到'); raise SystemExit
f=tempfile.NamedTemporaryFile(delete=False,suffix='.db'); f.write(d); f.close()
try:
    c=sqlite3.connect(f.name); print(c.execute('select count(*) from transactions').fetchone()[0])
except Exception: print('读不到')
finally: os.unlink(f.name)
" 2>/dev/null || echo "读不到")
echo "    主空间流水：${N} 笔"
