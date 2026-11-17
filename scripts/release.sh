#!/bin/bash
# ---------------------------------------------------------------
# i记账 发版脚本
#
# 用法：
#   ./scripts/release.sh 2.5 "修了什么 / 加了什么（一句话）"
#
# 它会依次做：
#   1. 检查工作区
#   2. versionCode +1、versionName 改成你给的版本
#   3. 编译 + 跑全部测试（不过就停）
#   4. 打包 APK 到 /tmp/i-accounting-v<版本>.apk
#   5. 往 CHANGELOG.md 顶部插入一条记录
#   6. commit + push
#   7. 打 tag + 建 GitHub Release（自动附上 APK）
#
# 任何一步失败都会停下，不会留下半成品提交。
# ---------------------------------------------------------------
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_DIR}"

VERSION="${1:-}"
NOTE="${2:-}"

if [ -z "${VERSION}" ] || [ -z "${NOTE}" ]; then
  echo "用法: ./scripts/release.sh <版本号> <更新说明>"
  echo "例:   ./scripts/release.sh 2.5 \"修复提现方向判定\""
  exit 1
fi

echo "==> 版本 ${VERSION}"

# 1. 工作区检查
if [ -n "$(git status --porcelain)" ]; then
  echo "✗ 工作区有未提交改动，先提交或撤销："
  git status --short
  exit 1
fi

if git rev-parse "v${VERSION}" >/dev/null 2>&1; then
  echo "✗ 标签 v${VERSION} 已存在"
  exit 1
fi

# 2. 版本号
GRADLE="app/build.gradle.kts"
OLD_CODE=$(grep -E '^\s*versionCode = ' "${GRADLE}" | grep -oE '[0-9]+')
OLD_NAME=$(grep -E '^\s*versionName = ' "${GRADLE}" | grep -oE '"[^"]+"' | tr -d '"')
NEW_CODE=$((OLD_CODE + 1))

echo "   versionCode ${OLD_CODE} → ${NEW_CODE}"
echo "   versionName ${OLD_NAME} → ${VERSION}"

sed -i '' "s/versionCode = ${OLD_CODE}$/versionCode = ${NEW_CODE}/" "${GRADLE}"
sed -i '' "s/versionName = \"${OLD_NAME}\"$/versionName = \"${VERSION}\"/" "${GRADLE}"

# 3. 构建 + 测试
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-${HOME}/Android/sdk}"

echo "==> 编译与测试（这一步最慢）"
if ! ./gradlew assembleDebug testDebugUnitTest --no-daemon > /tmp/release_build.log 2>&1; then
  echo "✗ 编译或测试失败，已回滚版本号。看 /tmp/release_build.log"
  sed -i '' "s/versionCode = ${NEW_CODE}$/versionCode = ${OLD_CODE}/" "${GRADLE}"
  sed -i '' "s/versionName = \"${VERSION}\"$/versionName = \"${OLD_NAME}\"/" "${GRADLE}"
  exit 1
fi

TESTS=$(find app/build/test-results -name "*.xml" | head -1 | xargs -I{} python3 -c "
import sys, xml.etree.ElementTree as ET
t = ET.parse('{}').getroot()
print(f\"{t.get('tests')}/{t.get('tests')} 通过\")
")
echo "   测试: ${TESTS}"

# 4. 打包（英文名，GitHub 会吞中文文件名）
APK="/tmp/i-accounting-v${VERSION}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "${APK}"
# Downloads 不一定存在（新机器/无桌面环境），先确保目录在，否则 cp 会失败并把发版卡在中途
mkdir -p "${HOME}/Downloads"
cp "${APK}" "${HOME}/Downloads/i记账-v${VERSION}.apk" 2>/dev/null || true
echo "   APK: /tmp/i-accounting-v${VERSION}.apk ($(du -h "${APK}" | cut -f1))"

# 5. CHANGELOG
TODAY=$(date +%Y-%m-%d)
ENTRY="## [${VERSION}] - ${TODAY}

${NOTE}

"
python3 - "${VERSION}" "${ENTRY}" <<'PY'
import sys
ver, entry = sys.argv[1], sys.argv[2]
p = "CHANGELOG.md"
s = open(p, encoding="utf-8").read()
anchor = "## ["
i = s.find(anchor)
if i < 0:
    s = s.rstrip() + "\n\n" + entry
else:
    s = s[:i] + entry + s[i:]
open(p, "w", encoding="utf-8").write(s)
print("   CHANGELOG 已更新")
PY

# 6. 提交推送
git add -A
git commit -q -m "v${VERSION}：${NOTE}"
git push -q origin main
echo "   已推送 main"

# 7. 标签 + Release
git tag -f "v${VERSION}" -m "v${VERSION}：${NOTE}"
git push -f origin "v${VERSION}" 2>/dev/null

gh release create "v${VERSION}" \
  --title "v${VERSION} · ${NOTE}" \
  --notes "${NOTE}

## 安装
下载下面的 \`i-accounting-v${VERSION}.apk\` 直接安装（覆盖安装不会丢数据）。

完整更新记录见 [CHANGELOG.md](https://github.com/XXKG2026/i-accounting/blob/main/CHANGELOG.md)" \
  --latest >/dev/null

gh release upload "v${VERSION}" "${APK}" --clobber >/dev/null
echo "   Release 已建，APK 已上传"

echo
echo "✓ 完成：https://github.com/XXKG2026/i-accounting/releases/tag/v${VERSION}"
