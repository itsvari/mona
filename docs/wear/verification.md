# Verification record — 23 September 2026

Implemented and exercised against Flutter 3.47.4, JDK 21, a phone emulator on API 34, and a round Wear OS 6.1 emulator on API 36.1.

| Check | Result |
| --- | --- |
| Full Flutter suite | 715 passed, 1 skipped |
| Flutter analyzer | No issues |
| Focused Wear SQLite tests after final review | 17 passed |
| Phone Android JVM suite | 12 passed |
| Watch JVM suite | 32 passed, including 15 Dart-generated recurrence cases |
| Watch Android instrumentation | 4 passed |
| Watch Android lint | No errors; dependency/style warnings remain |
| Phone standalone debug APK | Built and installed |
| Watch standalone debug and store release APKs | Built; debug installed |
| Debug signing | Phone/watch package IDs and certificates match |
| Release debug-adapter exclusion | Watch release has no debug receiver |
| Diff whitespace check | Passed |

`scripts/check_wear.sh` ran successfully. It compares the committed Kotlin recurrence fixtures with a fresh export from Mona's actual Dart planner, then runs both Android unit suites, lint, and watch builds.

`scripts/verify_wear.py` ran successfully on both emulators. It created a real Mona schedule and intake through the watch's persistent outbox and a cold phone Flutter worker, restarted processes, delivered the receipt before its snapshot, and retried the identical dose. The result remained one intake with the watch's captured timezone.

Manual watch UI checks created a medication using actual system keyboard taps and a rotary dose picker, then logged a dose. The ADB adapter relayed those actual UI-generated commands. Both the schedule and intake appeared in Mona's database and UI. The native voice/keyboard chooser was opened. Round-screen layout was inspected at 227 dp and 192 dp, including 1.2× text scaling. Long headers, edge labels, and the amount input layout were corrected during those checks.

Instrumentation exercised the real watch alarm receiver and NotificationManager: a posted reminder survived rescheduling, a logged dose canceled it and armed the next alarm, an edited schedule invalidated its old in-flight alarm, and a persisted outbox survived reload without duplicating a tap. Phone reminders were also verified with Google Play Services disabled and with radio work in backoff.

**Not yet verified:** a real paired Data Layer radio connection, physical one-handed gestures, speech transcription, and TalkBack interaction. Emulator synchronization used the explicit debug-only ADB transport adapter. These checks do not establish paired-device delivery or production release readiness. The [setup guide](README.md#verification) includes the remaining device acceptance pass.
