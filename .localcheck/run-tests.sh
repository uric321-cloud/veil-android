#!/bin/bash
# Compiles and runs the engine tests in .localcheck/test/ on a plain JVM.
# Needs only a JDK (17+), curl and unzip: the first run downloads the Kotlin
# compiler and a public android.jar (compile-time only) into $VEIL_TOOLS.
#
#   .localcheck/run-tests.sh          offline tests: Engine, TextEngine, InApp, Image
#   .localcheck/run-tests.sh --live   also UpstreamTest (real DNS lookups; prints
#                                     results to read, no pass/fail)
set -euo pipefail
ROOT=${VEIL_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}
TOOLS=${VEIL_TOOLS:-$HOME/.cache/veil-localcheck}
KOTLIN_VERSION=2.1.21   # keep in step with the Kotlin plugin in build.gradle.kts
OUT=$ROOT/.localcheck/testout
J=$ROOT/app/src/main/java/app/veil/android

TESTS=(Engine TextEngine InApp Image)
[ "${1:-}" = "--live" ] && TESTS+=(Upstream)

# Sources each test compiles against, besides the test file itself.
sources() {
    case $1 in
        Engine) echo "$J/dns/DnsMessage.kt $J/rules/Matcher.kt $J/rules/RuleStore.kt $J/rules/ListSource.kt $J/VeilLog.kt $J/screen/TextRuleEngine.kt $J/vpn/Packets.kt" ;;
        TextEngine) echo "$J/screen/TextRuleEngine.kt $J/screen/Tiers.kt" ;;
        InApp) echo "$J/screen/InAppMatch.kt" ;;
        Image) echo "$J/screen/ImageVerdict.kt" ;;
        Upstream) echo "$J/dns/DnsMessage.kt $J/dns/Upstream.kt $J/VeilLog.kt" ;;
    esac
}

mkdir -p "$TOOLS"
KOTLINC=$TOOLS/kotlinc-$KOTLIN_VERSION/bin/kotlinc
if [ ! -x "$KOTLINC" ]; then
    echo "Downloading Kotlin $KOTLIN_VERSION compiler..."
    curl -sSLf --retry 4 --retry-delay 5 -o "$TOOLS/kotlinc.zip" \
        "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
    rm -rf "$TOOLS/kotlinc" "$TOOLS/kotlinc-$KOTLIN_VERSION"
    unzip -q "$TOOLS/kotlinc.zip" -d "$TOOLS"
    mv "$TOOLS/kotlinc" "$TOOLS/kotlinc-$KOTLIN_VERSION"
    rm "$TOOLS/kotlinc.zip"
fi
# RuleStore/ListSource reference Context and SharedPreferences; any API level has them.
ANDROID_JAR=$TOOLS/android-4.1.1.4.jar
if [ ! -f "$ANDROID_JAR" ]; then
    echo "Downloading android.jar (compile-time stubs)..."
    curl -sSLf --retry 4 --retry-delay 5 -o "$ANDROID_JAR" \
        https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar
fi
STDLIB=$TOOLS/kotlinc-$KOTLIN_VERSION/lib/kotlin-stdlib.jar

rm -rf "$OUT" && mkdir -p "$OUT/shim"
# Runtime stand-in for android.util.Log, so VeilLog works off-device.
javac -d "$OUT/shim" "$ROOT/.localcheck/shimsrc/android/util/Log.java" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true

# Drops the proxy notice the JVM prints when JAVA_TOOL_OPTIONS is set.
quiet() { grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

failed=()
for t in "${TESTS[@]}"; do
    echo "===== ${t}Test"
    rc=0
    # shellcheck disable=SC2046
    "$KOTLINC" -jvm-target 17 -nowarn -cp "$OUT/shim:$ANDROID_JAR" -d "$OUT/$t.jar" \
        $(sources "$t") "$ROOT/.localcheck/test/${t}Test.kt" > "$OUT/$t.compile.log" 2>&1 || rc=$?
    quiet < "$OUT/$t.compile.log"
    if [ $rc -ne 0 ]; then
        echo "  COMPILE FAILED"; failed+=("$t"); continue
    fi
    java -cp "$OUT/$t.jar:$OUT/shim:$STDLIB" "${t}TestKt" > "$OUT/$t.log" 2>&1 || rc=$?
    quiet < "$OUT/$t.log"
    [ $rc -eq 0 ] || failed+=("$t")
done

echo
if [ ${#failed[@]} -eq 0 ]; then
    echo "All test programs passed: ${TESTS[*]}"
else
    echo "Failed: ${failed[*]}"
    exit 1
fi
