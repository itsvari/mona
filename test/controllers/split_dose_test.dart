import 'package:clock/clock.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/controllers/notification_planner.dart';
import 'package:mona/controllers/slots_builder.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/medication_schedule_provider.dart';
import 'package:mona/services/preferences_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:timezone/data/latest_all.dart' as tz;

import '../data/providers/generic_repository_mock.dart';
import '../fixtures.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  for (final type in ['daily', 'interval', 'dynamic', 'weekly', 'monthly']) {
    test('$type portions retain independent completion and evening reminders',
        () async {
      await withClock(Clock.fixed(DateTime(2026, 9, 22, 12)), () async {
        SharedPreferences.setMockInitialValues({});
        final preferences = await PreferencesService.init();
        final intakes = MedicationIntakeProvider(
          repository: GenericRepositoryMock<MedicationIntake>(),
        );
        final schedules = MedicationScheduleProvider(
          preferences: preferences,
          repository: GenericRepositoryMock<MedicationSchedule>(),
        );
        final strategy = switch (type) {
          'daily' => aDailyStrategy(intakeTimes: [morning, evening]),
          'interval' =>
            anIntervalStrategy(notificationTimes: [morning, evening]),
          'dynamic' =>
            aDynamicIntervalStrategy(notificationTimes: [morning, evening]),
          'weekly' => aWeeklyStrategy(
              daysOfWeek: [Date.today().weekday],
              notificationTimes: [morning, evening]),
          _ => aMonthlyStrategy(
              dayOfMonth: 22, notificationTimes: [morning, evening]),
        };
        final schedule = aMedicationSchedule(
                id: 100, dose: Decimal.fromInt(6), scheduling: strategy)
            .copyWith(unitDose: Decimal.fromInt(2), doseOverrides: [
          ScheduledDose(time: morning, dose: Decimal.fromInt(2)),
          ScheduledDose(time: evening, dose: Decimal.fromInt(4)),
        ]);
        await schedules.add(schedule);
        await intakes.fetchIntakes();
        final builder = SlotsBuilder(intakes, schedules);
        expect(builder.intakeSlots().map((s) => s.status),
            [ScheduleStatus.today, ScheduleStatus.today]);
        await intakes.add(aMedicationIntake(
                scheduleId: schedule.id,
                time: morning,
                dose: Decimal.fromInt(2))
            .copyWith(takenDateTime: DateTime.utc(2026, 9, 22, 9)));

        final slots = builder.intakeSlots();
        expect(slots.map((s) => s.time), [morning, evening]);
        expect(slots.map((s) => s.status),
            [ScheduleStatus.taken, ScheduleStatus.today]);
        expect(slots.last.schedule.doseAt(slots.last.time), Decimal.fromInt(4));
        final plans = NotificationPlanner(intakes, schedules)
            .planNotifications(daysAhead: 2);
        expect(plans.any((p) => p.firstFire == DateTime(2026, 9, 22, 20, 30)),
            isTrue);

        await intakes.add(aMedicationIntake(
                scheduleId: schedule.id,
                time: evening,
                dose: Decimal.fromInt(4))
            .copyWith(takenDateTime: DateTime.utc(2026, 9, 22, 20, 30)));
        expect(
            builder
                .intakeSlots()
                .every((s) => s.status == ScheduleStatus.taken),
            isTrue);
        final remaining = NotificationPlanner(intakes, schedules)
            .planNotifications(daysAhead: 2);
        expect(
            remaining.any((p) => p.firstFire == DateTime(2026, 9, 22, 20, 30)),
            isFalse);
        schedules.dispose();
        intakes.dispose();
        preferences.dispose();
      });
    });
  }
}
