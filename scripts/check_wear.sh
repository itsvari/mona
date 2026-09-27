#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
flutter_bin="${FLUTTER_BIN:-flutter}"
fixture_output="$(mktemp)"
trap 'rm -f "$fixture_output"' EXIT

"$flutter_bin" test test/services/wear_sync_service_test.dart test/controllers/medication_intake_manager_test.dart
TZ=UTC WEAR_FIXTURE_OUTPUT="$fixture_output" "$flutter_bin" test test/services/wear_recurrence_fixtures_test.dart
cmp "$fixture_output" android/wear/src/test/resources/recurrence-fixtures.json
android/gradlew -p android :app:testStandaloneDebugUnitTest
android/gradlew -p android/wear testStandaloneDebugUnitTest lintStandaloneDebug assembleStandaloneDebug assembleStoreRelease

echo 'Wear contract, recurrence parity, unit tests, lint, and APK builds passed.'
