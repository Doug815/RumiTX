#!/usr/bin/env bash
# JVM tests for auth, transmit policy, the command layer, and tone frames. No radio, no RF.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/.ci-tests"
mkdir -p "$OUT"
javac --release 8 -d "$OUT" \
  "$ROOT/app/src/main/java/com/rumitx/app/RadioIntentParser.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/RumiChatRouter.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/ToneIndex.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/Dmr007Frame.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/Channel.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/AiTempChannel.java" \
  "$ROOT/app/src/main/java/com/rumitx/app/agent/"*.java \
  "$ROOT/tools/tests/AgentApiTest.java" \
  "$ROOT/tools/tests/ToneSettingsTest.java"
java -cp "$OUT" com.rumitx.app.agent.AgentApiTest
java -cp "$OUT" com.rumitx.app.ToneSettingsTest
echo "PASS: tools/test_agent_api.sh"
