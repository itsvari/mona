import 'dart:convert';
import 'dart:io';

import 'package:clock/clock.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/controllers/notification_planner.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/medication_schedule_provider.dart';
import 'package:mona/services/db/app_database.dart';
import 'package:mona/services/preferences_service.dart';
import 'package:mona/services/repository.dart';
import 'package:mona/services/wear/wear_protocol.dart';
import 'package:mona/services/wear/wear_sync_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:timezone/data/latest_all.dart' as tzdata;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  tzdata.initializeTimeZones();
  databaseFactory = databaseFactoryFfi;

  test('export real Mona reminder planning cases for Wear parity', () async {
    final output = Platform.environment['WEAR_FIXTURE_OUTPUT'];
    if (output != null) {
      expect(DateTime.now().timeZoneOffset, Duration.zero,
          reason:
              'Export with TZ=UTC so DateTime matches the declared fixture zone.');
    }
    const morning = TimeOfDay(hour: 8, minute: 0);
    const evening = TimeOfDay(hour: 20, minute: 0);
    const early = TimeOfDay(hour: 2, minute: 0);
    final now = DateTime(2026, 9, 23, 7);
    final fixtures = <Map<String, Object?>>[];
    final cases = <({
      String name,
      SchedulingStrategy recurrence,
      bool taken,
      bool split,
      Date start,
      DateTime now
    })>[
      (
        name: 'daily',
        recurrence: const DailySchedule(intakeTimes: [morning, evening]),
        taken: false,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
      (
        name: 'daily_taken',
        recurrence: const DailySchedule(intakeTimes: [morning, evening]),
        taken: true,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
      (
        name: 'daily_logical_day',
        recurrence: const DailySchedule(intakeTimes: [early]),
        taken: false,
        split: false,
        start: Date(year: 2026),
        now: DateTime(2026, 9, 23, 1)
      ),
      (
        name: 'fixed_interval',
        recurrence: const IntervalDaysSchedule(
            intervalDays: 3, notificationTimes: [morning, evening]),
        taken: false,
        split: false,
        start: Date(year: 2026, month: 9, day: 20),
        now: now
      ),
      (
        name: 'fixed_interval_taken',
        recurrence: const IntervalDaysSchedule(
            intervalDays: 3, notificationTimes: [morning, evening]),
        taken: true,
        split: false,
        start: Date(year: 2026, month: 9, day: 20),
        now: now
      ),
      (
        name: 'fixed_interval_split',
        recurrence: const IntervalDaysSchedule(
            intervalDays: 3, notificationTimes: [morning, evening]),
        taken: true,
        split: true,
        start: Date(year: 2026, month: 9, day: 20),
        now: now
      ),
      (
        name: 'dynamic_initial',
        recurrence: const DynamicIntervalSchedule(
            intervalDays: 5, notificationTimes: [morning]),
        taken: false,
        split: false,
        start: Date(year: 2026, month: 9, day: 23),
        now: now
      ),
      (
        name: 'dynamic_taken',
        recurrence: const DynamicIntervalSchedule(
            intervalDays: 5, notificationTimes: [morning, evening]),
        taken: true,
        split: false,
        start: Date(year: 2026, month: 9, day: 20),
        now: now
      ),
      (
        name: 'dynamic_split',
        recurrence: const DynamicIntervalSchedule(
            intervalDays: 5, notificationTimes: [morning, evening]),
        taken: true,
        split: true,
        start: Date(year: 2026, month: 9, day: 23),
        now: now
      ),
      (
        name: 'weekly',
        recurrence: const WeeklySchedule(
            daysOfWeek: [1, 3, 5], notificationTimes: [morning, evening]),
        taken: false,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
      (
        name: 'weekly_taken',
        recurrence: const WeeklySchedule(
            daysOfWeek: [1, 3, 5], notificationTimes: [morning, evening]),
        taken: true,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
      (
        name: 'monthly_interval',
        recurrence: const MonthlySchedule(
            dayOfMonth: 28, intervalMonths: 2, notificationTimes: [morning]),
        taken: false,
        split: false,
        start: Date(year: 2026, month: 1, day: 29),
        now: now
      ),
      (
        name: 'future_start',
        recurrence: const DailySchedule(intakeTimes: [morning]),
        taken: false,
        split: false,
        start: Date(year: 2027, month: 1, day: 1),
        now: now
      ),
      (
        name: 'disabled',
        recurrence: const DailySchedule(intakeTimes: [morning], notify: false),
        taken: false,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
      (
        name: 'as_needed',
        recurrence: const AsNeededSchedule(),
        taken: false,
        split: false,
        start: Date(year: 2026),
        now: now
      ),
    ];
    for (final item in cases) {
      AppDatabase.reset();
      final db = await AppDatabase.getInstance(inMemory: true).database;
      SharedPreferences.setMockInitialValues({'notifications_enabled': true});
      final prefs = await PreferencesService.init();
      logicalDayStartMinutes = 240;
      final schedule = MedicationSchedule(
          id: 1,
          name: item.name,
          dose: Decimal.parse('2'),
          doseOverrides: item.split
              ? [ScheduledDose(time: evening, dose: Decimal.parse('3'))]
              : [],
          molecule: KnownMolecules.estradiol,
          dosingBasis: DosingBasis.mass,
          administrationRoute: AdministrationRoute.oral,
          scheduling: item.recurrence,
          startDate: item.start);
      await db.insert('medication_schedules', schedule.toMap());
      if (item.taken) {
        await db.insert(
            'medication_intakes',
            MedicationIntake(
                    id: 2,
                    takenDose: Decimal.parse('2'),
                    scheduledTime:
                        item.recurrence is DailySchedule || item.split
                            ? morning
                            : null,
                    takenDateTime: now.toUtc(),
                    takenTimeZone: 'UTC',
                    scheduleId: 1,
                    molecule: schedule.molecule,
                    dosingBasis: schedule.dosingBasis,
                    administrationRoute: schedule.administrationRoute)
                .toMap());
      }
      final intakes = MedicationIntakeProvider(
          repository: Repository(
              db: db,
              tableName: 'medication_intakes',
              toMap: (MedicationIntake v) => v.toMap(),
              fromMap: MedicationIntakeMapper.fromMap));
      final schedules = MedicationScheduleProvider(
          preferences: prefs,
          repository: Repository(
              db: db,
              tableName: 'medication_schedules',
              toMap: (MedicationSchedule v) => v.toMap(),
              fromMap: MedicationScheduleMapper.fromMap));
      await Future.wait([intakes.ready, schedules.ready]);
      final fixture = await withClock(Clock.fixed(item.now), () async {
        final plans = NotificationPlanner(intakes, schedules)
            .planNotifications(daysAhead: 20)
          ..sort((a, b) => a.firstFire.compareTo(b.firstFire));
        final firstPerTime = <int, Map<String, Object?>>{};
        for (final plan in plans) {
          final minute = plan.firstFire.hour * 60 + plan.firstFire.minute;
          firstPerTime.putIfAbsent(
              minute,
              () => {
                    'minute': minute,
                    'at': plan.firstFire.toUtc().millisecondsSinceEpoch,
                    'dose': schedule
                        .doseAt(TimeOfDay.fromDateTime(plan.firstFire))
                        .toString()
                  });
        }
        return <String, Object?>{
          'name': item.name,
          'now': item.now.toUtc().millisecondsSinceEpoch,
          'zoneId': 'UTC',
          'snapshot': await WearSyncService(db, prefs).snapshot('UTC'),
          'nextReminders': firstPerTime.values.toList()
        };
      });
      (fixture['snapshot'] as Map<String, Object?>)['datasetId'] =
          '00000000-0000-4000-8000-000000000001';
      fixtures.add(fixture);
      expect(wearSchedule(schedule)['recurrence'], isA<Map>());
      intakes.dispose();
      schedules.dispose();
      prefs.dispose();
      await db.close();
    }
    AppDatabase.reset();
    if (output != null) {
      await File(output)
          .writeAsString(const JsonEncoder.withIndent('  ').convert(fixtures));
    }
    expect(fixtures, hasLength(15));
  });
}
