#!/bin/bash
# Type-checks all app sources against android.jar (API 30) with stubs for generated classes.
set -e
ROOT=${VEIL_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
TOOLS=${VEIL_TOOLS:-/tmp/claude-0/-home-claude/46b3a895-91d8-517b-b934-3becd054f0af/scratchpad/tools}
KOTLINC=$TOOLS/kotlinc/bin/kotlinc
OUT=$ROOT/.localcheck/out
rm -rf $OUT && mkdir -p $OUT
# Compile the Java stubs first
javac -d $OUT $(find $ROOT/.localcheck/stubs -name '*.java') 2>&1
SRCS="$(find $ROOT/app/src/main/java -name '*.kt') $(ls $ROOT/.localcheck/stubs/app/veil/android/ui/MainActivity.kt 2>/dev/null || true)"
if [ -f $ROOT/app/src/main/java/app/veil/android/ui/MainActivity.kt ]; then
  SRCS="$(find $ROOT/app/src/main/java -name '*.kt')"
fi
$KOTLINC -jvm-target 17 -no-reflect -Xno-call-assertions -Xno-param-assertions \
  -cp "$TOOLS/android30.jar:$OUT" -d $OUT $SRCS 2>&1 | grep -v "^warning: " | grep -v JAVA_TOOL || true
echo "check done"
