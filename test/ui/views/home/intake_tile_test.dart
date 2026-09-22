import 'package:clock/clock.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/ui/views/home/intake_tile.dart';
import 'package:timezone/data/latest_all.dart' as tz;

import '../../../data/providers/generic_repository_mock.dart';
import '../../../fixtures.dart';
import '../../../mocks/mocks.mocks.dart';

void main() {
  tz.initializeTimeZones();
  setUpAll(() => initializeDateFormatting('en'));

  for (final status in [
    ScheduleStatus.todayOverdue,
    ScheduleStatus.overdue,
    ScheduleStatus.upcoming,
  ]) {
    testWidgets('$status identifies the evening portion and its last intake',
        (tester) async {
      await withClock(Clock.fixed(DateTime(2026, 9, 23, 12)), () async {
        final schedule = aMedicationSchedule(
          scheduling: anIntervalStrategy(notificationTimes: [morning, evening]),
        ).copyWith(doseOverrides: [
          ScheduledDose(time: evening, dose: Decimal.fromInt(2)),
        ]);
        final intakes = MedicationIntakeProvider(
          repository: GenericRepositoryMock<MedicationIntake>(),
        );
        addTearDown(intakes.dispose);
        await intakes.add(
            aMedicationIntake(scheduleId: schedule.id, time: evening)
                .copyWith(takenDateTime: DateTime.utc(2026, 9, 21, 20, 30)));
        await intakes.add(
            aMedicationIntake(scheduleId: schedule.id, time: morning)
                .copyWith(takenDateTime: DateTime.utc(2026, 9, 23, 9)));
        final date = Date(
            year: 2026,
            month: 9,
            day: status == ScheduleStatus.upcoming ? 25 : 22);
        late IntakeTileViewModel model;
        await tester.pumpWidget(MaterialApp(
          home: Builder(builder: (context) {
            model = IntakeTileViewModel(
              schedule: schedule,
              status: status,
              slotTime: evening,
              date: date,
              intakeProvider: intakes,
              supplyProvider: MockSupplyItemProvider(),
              now: clock.now(),
              languageTag: 'en',
              context: context,
            );
            return Text(model.scheduledText ?? '');
          }),
        ));

        expect(model.lastTaken, Date(year: 2026, month: 9, day: 21));
        expect(model.scheduledText, contains('8:30 PM'));
        if (status == ScheduleStatus.todayOverdue) {
          expect(model.warningText, contains('Sep 21'));
        } else {
          expect(model.scheduledText,
              contains(status == ScheduleStatus.upcoming ? '25' : '22'));
        }
      });
    });
  }
}
