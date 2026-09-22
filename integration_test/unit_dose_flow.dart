import 'dart:io';

import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:material_symbols_icons/symbols.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/ester.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/medication_schedule_provider.dart';
import 'package:mona/main.dart' as app;
import 'package:mona/services/db/app_database.dart';
import 'package:mona/ui/views/home/intake_tile.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

bool _androidSurfaceConverted = false;

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('configure, record, edit and reload pill and patch quantities',
      (tester) async {
    SharedPreferences.setMockInitialValues({'language_tag': 'en'});
    AppDatabase.getInstance(inMemory: true);
    app.main();
    await waitFor(tester, find.byIcon(Symbols.settings_rounded));

    await tap(tester, find.byIcon(Symbols.settings_rounded));
    await tap(tester, find.byKey(const ValueKey('settingsSchedulesTile')));
    await tap(tester, find.byIcon(Symbols.add_rounded));
    await enter(tester, 'newScheduleName', 'Split pill test');
    await tap(tester, find.byType(DropdownButtonFormField<Molecule>));
    await tap(tester, find.text('Estradiol').last);
    await tap(
        tester, find.byType(DropdownButtonFormField<AdministrationRoute>));
    await tap(tester, find.text('Oral').last);
    await enter(tester, 'newScheduleAmount', '6');
    await enter(tester, 'scheduleUnitDose', '2');
    await tap(tester, find.byKey(const ValueKey('newScheduleNext')));
    await addTime(tester, 8);
    await addTime(tester, 20);
    await setDose(tester, 8, '1');
    await setDose(tester, 20, '2');
    expect(
        find.textContaining('Total across intake times: 6 mg',
            findRichText: true),
        findsOneWidget);
    await snapshot(tester, binding, 'schedule');
    await tap(tester, find.byKey(const ValueKey('newScheduleSave')));
    await waitFor(tester, find.text('Split pill test'));

    final db = await AppDatabase.getInstance().database;
    var schedule = MedicationScheduleMapper.fromMap(
      (await db.query('medication_schedules')).single,
    );
    expect(schedule.unitDose, Decimal.fromInt(2));
    expect(schedule.doseAt(const TimeOfDay(hour: 8, minute: 0)),
        Decimal.fromInt(2));
    expect(schedule.doseAt(const TimeOfDay(hour: 20, minute: 0)),
        Decimal.fromInt(4));

    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();
    Finder slot(int hour) => find.byWidgetPredicate((widget) =>
        widget is IntakeTile &&
        widget.slot.schedule.name == 'Split pill test' &&
        widget.slot.time?.hour == hour);
    await tap(tester, slot(8));
    expect(quantity(tester), '1');
    await tap(tester, find.byKey(const ValueKey('increaseDoseQuantity')));
    expect(quantity(tester), '2');
    await tap(tester, find.byKey(const ValueKey('decreaseDoseQuantity')));
    expect(quantity(tester), '1');
    await tap(tester, find.byKey(const ValueKey('takeIntakeSubmit')));
    await waitFor(tester, slot(20));
    expect(
        tester.widget<IntakeTile>(slot(8)).slot.status, ScheduleStatus.taken);
    expect(
        tester.widget<IntakeTile>(slot(20)).slot.status, ScheduleStatus.today);

    await tap(tester, slot(20));
    expect(quantity(tester), '2');
    expect(find.text('Taken amount: 4 mg'), findsOneWidget);
    await snapshot(tester, binding, 'logging');
    await tap(tester, find.byKey(const ValueKey('takeIntakeSubmit')));
    await waitFor(tester, slot(20));
    var intakes = (await db.query('medication_intakes'))
        .map(MedicationIntakeMapper.fromMap)
        .toList();
    expect(intakes.map((i) => i.takenDose),
        [Decimal.fromInt(2), Decimal.fromInt(4)]);
    expect(intakes.every((i) => i.unitDose == Decimal.fromInt(2)), isTrue);

    await tap(tester, slot(20));
    await enter(tester, 'doseQuantity', '1.5');
    await tap(tester, find.byKey(const ValueKey('editIntakeSave')));
    await waitFor(tester, slot(20));
    intakes = (await db.query('medication_intakes'))
        .map(MedicationIntakeMapper.fromMap)
        .toList();
    expect(intakes.last.takenDose, Decimal.fromInt(3));

    await tap(tester, find.byIcon(Symbols.settings_rounded));
    await tap(tester, find.byKey(const ValueKey('settingsSchedulesTile')));
    await tap(tester, find.text('Split pill test'));
    await enter(tester, 'scheduleUnitDose', '1');
    await tap(tester, find.byKey(const ValueKey('editScheduleSave')));
    await waitFor(tester, find.text('Split pill test'));
    await tap(tester, find.text('Split pill test'));
    await tap(tester, find.byKey(const ValueKey('editScheduleSchedulingTile')));
    await setDose(tester, 20, '4');
    await tap(tester, find.byKey(const ValueKey('editSchedulingSave')));
    await tap(tester, find.byKey(const ValueKey('editScheduleSave')));
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tap(tester, slot(20));
    expect(quantity(tester), '1.5',
        reason: 'Historical intake retains its 2 mg pill strength.');
    await tap(tester, find.byTooltip('Close'));
    await tester.pumpAndSettle();

    final context = tester.element(slot(20));
    final schedules = context.read<MedicationScheduleProvider>();
    final intakeProvider = context.read<MedicationIntakeProvider>();
    schedule = MedicationScheduleMapper.fromMap(
        (await db.query('medication_schedules')).single);
    await schedules.add(schedule.copyWith(
      id: schedule.id + 100,
      name: 'Patch test',
      administrationRoute: AdministrationRoute.patch,
      dose: Decimal.parse('0.1'),
      unitDose: Decimal.parse('0.05'),
      doseOverrides: [],
      scheduling: const AsNeededSchedule(),
    ));
    await schedules.fetchSchedules();
    await intakeProvider.fetchIntakes();
    await tester.pumpAndSettle();
    final patch = find.byWidgetPredicate((widget) =>
        widget is IntakeTile && widget.slot.schedule.name == 'Patch test');
    await tap(tester, patch);
    expect(quantity(tester), '2');
    await tap(tester, find.byKey(const ValueKey('increaseDoseQuantity')));
    expect(find.text('Taken amount: 0.15 mg'), findsOneWidget);
    await tap(tester, find.byKey(const ValueKey('takeIntakeSubmit')));
    await waitFor(tester, patch);
    final patches = await db.query('medication_intakes',
        where: 'administrationRoute = ?', whereArgs: ['patch']);
    expect(patches.single['takenDose'], '0.15');
    expect(patches.single['unitDose'], '0.05');
    await schedules.add(schedule.copyWith(
      id: schedule.id + 200,
      name: 'Injection test',
      administrationRoute: AdministrationRoute.injection,
      ester: Ester.enanthate,
      unitDose: null,
      doseOverrides: [],
      scheduling: const AsNeededSchedule(),
    ));
    await tester.pumpAndSettle();
    final injection = find.byWidgetPredicate((widget) =>
        widget is IntakeTile && widget.slot.schedule.name == 'Injection test');
    await tap(tester, injection);
    expect(find.byKey(const ValueKey('doseQuantity')), findsNothing);
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('doseAmount')))
            .controller!
            .text,
        '6');
    await tap(tester, find.byKey(const ValueKey('takeIntakeSubmit')));
    await waitFor(tester, injection);
    final injections = await db.query('medication_intakes',
        where: 'administrationRoute = ?', whereArgs: ['injection']);
    expect(injections.single['takenDose'], '6');
    expect(injections.single['unitDose'], isNull);
    expect(tester.takeException(), isNull);
  });
}

Future<void> waitFor(WidgetTester tester, Finder finder) async {
  for (var i = 0; i < 100 && finder.evaluate().isEmpty; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
  expect(finder, findsWidgets);
  await tester.pumpAndSettle();
}

Future<void> tap(WidgetTester tester, Finder finder) async {
  await waitFor(tester, finder);
  await tester.ensureVisible(finder);
  await tester.tap(finder);
  await tester.pumpAndSettle();
}

Future<void> enter(WidgetTester tester, String key, String text) async {
  final finder = find.byKey(ValueKey(key));
  await tester.ensureVisible(finder);
  await tester.enterText(finder, text);
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pumpAndSettle();
}

String quantity(WidgetTester tester) => tester
    .widget<TextField>(find.byKey(const ValueKey('doseQuantity')))
    .controller!
    .text;

Future<void> addTime(WidgetTester tester, int hour) async {
  await tap(tester, find.byKey(const ValueKey('addNotificationTile')));
  final switchMode = find.byTooltip('Switch to text input mode');
  if (switchMode.evaluate().isNotEmpty) await tap(tester, switchMode);
  final fields = find.descendant(
      of: find.byType(TimePickerDialog), matching: find.byType(TextField));
  // The test devices use the English locale's 12-hour time entry.
  final hasPeriod = find.text('PM').evaluate().isNotEmpty;
  await tester.enterText(
      fields.at(0), '${hasPeriod ? (hour % 12 == 0 ? 12 : hour % 12) : hour}');
  await tester.enterText(fields.at(1), '00');
  if (hasPeriod) await tap(tester, find.text(hour < 12 ? 'AM' : 'PM').last);
  await tap(tester, find.text('OK'));
}

Future<void> setDose(WidgetTester tester, int hour, String value) async {
  await tap(tester, find.byKey(ValueKey('scheduledDose$hour:0')));
  await enter(tester, 'doseQuantity', value);
  await tap(tester, find.byKey(const ValueKey('saveScheduledDose')));
}

Future<void> snapshot(WidgetTester tester,
    IntegrationTestWidgetsFlutterBinding binding, String name) async {
  if (Platform.isAndroid && !_androidSurfaceConverted) {
    await binding.convertFlutterSurfaceToImage();
    _androidSurfaceConverted = true;
    await tester.pumpAndSettle();
  }
  final bytes = await binding.takeScreenshot(name);
  await File('${Directory.systemTemp.path}/mona-$name.png').writeAsBytes(bytes);
}
