#!/bin/sh
# Rebuilds trust-agent-stubs.jar (compile-only stub of the @SystemApi TrustAgentService).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
rm -rf out && mkdir -p out
javac --release 17 -cp "$SDK/platforms/android-34/android.jar" -d out src/android/service/trust/TrustAgentService.java
(cd out && jar cf ../trust-agent-stubs.jar android)
rm -rf out
