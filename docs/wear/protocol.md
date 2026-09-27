# Mona Wear protocol, version 1

Mona on the Android phone owns the records. The watch stores a confirmed snapshot and a separate durable outbox. Pending actions are visibly pending until a phone receipt arrives. All amounts are decimal strings; entity IDs are strings containing Mona integer IDs. Timestamps are UTC epoch milliseconds, with a separate IANA zone ID captured when logging.

The apps use matching application IDs and signing certificates. A native phone listener persists incoming commands and schedules a unique WorkManager worker. The worker starts a bounded headless Flutter engine, applies commands in Dart, and alone publishes snapshots. No native code opens Mona's SQLite database.

Data Layer items contain a UTF-8 JSON string in DataMap key `json`:

- `/mona/v1/snapshot`: phone snapshot.
- `/mona/v1/commands/{installationId}/{id}`: immutable watch command. Both IDs are UUIDs.
- `/mona/v1/results/{installationId}/{id}`: durable phone receipt.
- `/mona/v1/refresh/{installationId}`: watch request for a fresh snapshot, with a changing timestamp.

The originating watch deletes a command only after persisting its result. The phone deletes the transport receipt after seeing that command deletion. Database receipts remain, so deleting an intake cannot make an old command execute again.

## Snapshot

`{version:1, datasetId:string, revision:integer, generatedAt:epochMs, zoneId:string, logicalDayStartMinutes:integer, notificationsEnabled:boolean, schedules:[], history:[], supplies:[], molecules:[]}`

- Schedule: `{id, revision, name, dose, unitDose:null|string, moleculeName, unit, route, ester:null|string, startDate:"YYYY-MM-DD", recurrence, doseOverrides:[]}`. `revision` is a stable content digest. Override: `{minute:integer, dose:string}`.
- Recurrence: `{type, times:[minutesSinceMidnight], notify:boolean, intervalDays?:integer, weekdays?:[ISO1to7], dayOfMonth?:integer, intervalMonths?:integer}`. Types: `daily`, `intervalDays`, `dynamicInterval`, `weekly`, `monthly`, `asNeeded`. The logical-day boundary moves times before it to the next calendar day.
- History: `{id, scheduleId:null|string, dose, unit, at:epochMs, zoneId, scheduledMinute:null|integer, logicalDate:"YYYY-MM-DD"}`. Includes recent history plus the latest intake for every schedule/time, regardless of age. Confirmed records only.
- Supply: `{id, name, moleculeName, unit, route, ester:null|string, remainingDose, unitDose, deliveryForm:null|string}`. Explicit choice is required before decrementing inventory. Name, unit, route, and ester must match the medication.
- Molecule: `{name, unit}`. Includes Mona's known and custom molecules.

Within one dataset the watch accepts only a newer revision. On a changed dataset it replaces confirmed state and marks old pending commands rejected for review. It never replays those commands into the replacement database.

## Commands

Envelope: `{version:1, id, installationId, datasetId, kind, payload}`.

- `recordDose`: `{scheduleId, scheduleRevision, dose, at, zoneId, scheduledMinute:null|integer, supplyId:null|string, notes:null|string}`. Uses the event timezone. Stale schedule revisions and missing or incompatible supplies are rejected. A selected scheduled dose already logged that logical day resolves to the existing intake instead of adding a duplicate. As-needed logging permits separate intakes.
- `createMedication`: schedule fields above without `id` or `revision`. Phone validates all fields and creates a real Mona medication schedule. Inventory creation is distinct and remains in Mona.

Receipt: `{version:1, id, datasetId, revision:integer, status:"applied"|"rejected", entityId:null|string, message:string}`. The transport revision identifies the accompanying confirmed snapshot; pending previews stop applying when that revision is received. One SQLite transaction stores the mutation and receipt core with a digest of the immutable request. Transient system failures leave commands pending. Reusing a command ID with different content is rejected.

## Flutter / Android worker boundary

Channel: `mona/wear`. Root Dart entrypoint: `wearBackgroundMain`, retained from `lib/main.dart`.

- Dart invokes `loadCommands`, receiving a list of `{path:string,json:string}`.
- Dart invokes `complete` with `{snapshot:string, results:[{path:string,json:string}], processedPaths:[string]}`. Result paths are fully qualified Data Layer paths. Android publishes all results and snapshot before removing native inbox entries.
- Dart invokes `failed` with a short non-sensitive message on retryable failure.
- Foreground Dart invokes `requestSync` after data changes and on resume. Native enqueues work.
- Native invokes `refresh` on the foreground channel after the worker commits; Dart reloads its providers without enqueueing an endless sync loop.
- Foreground Dart invokes `beginImport` / `endImport` around database replacement. Android serializes replacement against worker engine lifetime, and schedules a fresh snapshot afterward.

## Design decision

Selected the bounded headless worker design after comparing it with a single shared UI/background engine. Keeping Flutter activity ownership avoids changing every existing plugin's activity lifecycle. Adopted persistent dataset revisions and history retention from the shared-engine proposal. Rejected native database writes, activity-only synchronization, and local-only notification suppression. The watch disables bridging of Mona's phone notifications while it can deliver its own reminders, and restores bridging when watch notifications are disabled or denied. Mona currently uses notifications for medication reminders; any future non-medication channel will need an explicit bridging policy.

The watch computes recurrence from the complete confirmed schedule and pending-dose overlay, so reminders continue offline without a fixed expiration horizon. Recompute after synchronization, logging, reboot, clock changes, and timezone changes. Exact alarms require the user's system grant; use a visible inexact fallback when absent. Test recurrence against Dart-generated fixtures.
