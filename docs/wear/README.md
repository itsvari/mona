# Mona for Wear OS

The watch companion shows Mona's medication schedules, reminds you when doses are due, records doses, and creates medication schedules. Mona on the Android phone owns the database. The watch keeps a confirmed copy and a persistent queue; an offline action says **Saved on watch · waiting for Mona** until the phone commits it.

The watch supports Wear OS 3 and later (API 30+). It uses native Wear Compose Material 3: dynamic colors, transforming lists, edge buttons, confirmation animations, rotary scrolling and pickers, swipe dismissal, and the system voice/keyboard chooser. Primary edge actions register the platform's one-handed gesture on supported devices. Gesture support depends on the watch and its system settings. Touch remains available throughout.

Compose for Wear OS **1.7.0-rc01** supplies the new gesture APIs; this is a release candidate. The watch has its own Gradle settings with AGP 9.2.1 and compile SDK 37, so Mona's existing Flutter/Android build does not need an AGP migration. Check current AndroidX release notes before moving these pins.

## Build and install

Use JDK 21, Mona's configured Flutter SDK, Android SDK platform 37 (`platforms;android-37.0`), and the checked-in Gradle wrapper. Set `ANDROID_HOME` and `JAVA_HOME` for your machine.

From the repository root:

```sh
flutter pub get
flutter build apk --debug --flavor standalone
android/gradlew -p android/wear assembleStandaloneDebug

adb -s PHONE_SERIAL install -r build/app/outputs/flutter-apk/app-standalone-debug.apk
adb -s WATCH_SERIAL install -r android/wear/build/outputs/apk/standalone/debug/mona-wear-standalone-debug.apk
```

Pair the watch with the Android phone using its supported companion app, install both APKs, finish Mona's first-run setup, and open Mona on both devices. The watch requests its first snapshot. **Both apps must have the same application ID and signing certificate.** Debug builds use `com.deliacheminot.mona.dev`; release builds use `com.deliacheminot.mona`. A debug companion cannot synchronize with a release phone app. Use Android Studio's pairing assistant when testing paired emulators.

For release builds:

```sh
flutter build apk --release --flavor store
android/gradlew -p android/wear assembleStoreRelease bundleStoreRelease
```

The watch reads the phone's `android/key.properties`, resolving a relative keystore path against `android/app`, just as the phone build does. Without that file it uses the debug certificate for local testing. Configure release signing before distributing either app. Wear version names follow `pubspec.yaml`; Wear version codes are the Mona build number plus `100000000`, keeping phone and watch APK codes distinct. The watch's `standalone` flavor means sideload distribution, not independent operation: its manifest correctly declares a non-standalone companion.

Open `android/wear` as a separate Android Studio project for Wear previews and device tools.

## Using the companion

- **Log a dose:** choose a medication and tap **Log dose**. You can first change the amount, actual time/date, scheduled dose, note, and optional inventory source. Inventory changes only when you explicitly choose a compatible supply.
- **Add medication:** scroll to **Add**, enter its name through the system voice/keyboard chooser, choose a dose, route, and recurrence, review, then save. All Mona recurrence types are supported. Injectable estradiol requires an ester. This creates a real medication schedule; supply purchasing and inventory creation stay in Mona.
- **Reminders:** enable medication notifications in Mona, then allow watch notifications in **Reminders & sync**. The watch can request the system's exact-alarm permission. If it is absent, the UI explains that reminders may arrive late and uses the OS's inexact fallback.
- **Activity:** pending, confirmed, and rejected actions remain visible. A rejected action includes a reason. Review it before logging again; the app never silently changes its dose to match an updated schedule.

Reminders continue from the last synchronized rules while offline. A pending dose suppresses that occurrence locally. Reboot, clock changes, timezone changes, synchronized updates, and dose logging rebuild the alarm plan. Native watch reminders suppress bridged Mona notifications while watch notifications are available; turning them off or denying permission restores phone bridging. Force-stopping an Android app disables its alarms until it is reopened.

The watch UI is currently English. It uses the device's time/date formatting and system input languages. Physical one-handed gestures and voice recognition require a suitable watch; they cannot be fully validated by sending emulator key events.

## Data and lifecycle

[The protocol](protocol.md) defines the versioned snapshots, commands, and receipts. Data Layer is the only production transport; there is no extra account, server, or analytics service.

The phone listener writes commands to a private native inbox. A bounded background Flutter engine applies them through Mona's Dart models and SQLite transactions, including any inventory deduction. Each command and its receipt commit together. Repeating a command returns its original result, including after an intake is later deleted. Replacing Mona's database creates a new dataset identity so queued actions from the old dataset cannot be replayed into the new one.

Android phone notification planning has an independent persisted work queue, so a disconnected watch or unavailable Google Play Services cannot delay phone reminders. Both queues serialize Flutter execution against imports. The active phone UI reloads its providers after background commits.

Snapshots and commands are stored privately on each device. Watch cloud backup and device transfer are disabled to prevent an outbox or installation identity from being cloned. Large phone snapshots use Data Layer assets; the watch rejects unsupported or oversized updates while retaining its last readable state.

## Verification

Run the repeatable contract, Dart/Kotlin recurrence comparison, lint, and build checks:

```sh
FLUTTER_BIN=/path/to/flutter scripts/check_wear.sh
```

Run notification and persistence instrumentation on an isolated Wear emulator:

```sh
ANDROID_SERIAL=WATCH_SERIAL android/gradlew -p android/wear connectedStandaloneDebugAndroidTest
```

For unpaired emulators, install both debug APKs and finish the phone's first-run setup, then run:

```sh
python3 scripts/verify_wear.py --phone emulator-5554 --watch emulator-5556
```

This adds a test schedule and intake on disposable emulators. It calls production persistence and the cold phone worker through debug-only receivers protected by `android.permission.DUMP`. It proves offline process restart, watch-to-Mona creation/logging, receipt ordering, and retry deduplication. It **replaces the Data Layer radio transport with ADB** and is not evidence of Bluetooth/Wi-Fi pairing. Release APKs contain neither receiver. Output is saved under the ignored `build/wear-verification` directory.

Use `--seed-only` to copy current test-phone data to the watch for UI checks. After making changes through the watch UI, `--relay-only` delivers its actual outbox and returns Mona's snapshot and receipts. Neither mode runs against a physical device.

Before release, run a real paired-device pass: cold phone logging, disconnection/reconnection, medication creation, phone-side edits/deletions, backup replacement with a pending watch action, notification permissions, exact alarms, daylight-saving/timezone changes, rotary input, TalkBack, large text, and one-handed gestures. Confirm every accepted watch action in Mona and verify that each reminder appears once on the watch.

## Android references

- [Material 3 for Wear OS](https://developer.android.com/training/wearables/compose/material3)
- [Compose for Wear OS release notes](https://developer.android.com/jetpack/androidx/releases/wear-compose)
- [Rotary input](https://developer.android.com/training/wearables/compose/rotary-input)
- [System remote input](https://developer.android.com/reference/androidx/wear/input/RemoteInputIntentHelper)
- [Data Layer](https://developer.android.com/training/wearables/data/overview)
- [Notification bridging](https://developer.android.com/training/wearables/notifications/bridger)
- [Phone and emulator pairing](https://developer.android.com/training/wearables/get-started/connect-phone)
