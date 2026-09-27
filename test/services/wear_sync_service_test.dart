import 'dart:convert';

import 'package:clock/clock.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/controllers/medication_intake_manager.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/ester.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/medication_supply_item.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/model/supply_item.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/supply_item_provider.dart';
import 'package:mona/i18n/helpers/medication_intake_l10n.dart';
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
  late Database db;
  late PreferencesService preferences;
  late WearSyncService service;
  late MedicationSchedule schedule;
  late MedicationSupplyItem supply;
  late String dataset;
  final now = DateTime.utc(2026, 9, 23, 12);

  setUp(() async {
    AppDatabase.reset();
    db = await AppDatabase.getInstance(inMemory: true).database;
    SharedPreferences.setMockInitialValues({});
    preferences = await PreferencesService.init();
    logicalDayStartMinutes = 240;
    service = WearSyncService(db, preferences);
    schedule = MedicationSchedule(
        id: 10,
        name: 'Morning dose',
        dose: Decimal.parse('2'),
        molecule: KnownMolecules.estradiol,
        dosingBasis: DosingBasis.mass,
        administrationRoute: AdministrationRoute.oral,
        startDate: Date(year: 2026, month: 1, day: 1),
        scheduling:
            const DailySchedule(intakeTimes: [TimeOfDay(hour: 8, minute: 0)]));
    supply = MedicationSupplyItem(
        id: 20,
        name: 'Tablets',
        totalDose: Decimal.parse('100'),
        dosePerUnit: Decimal.parse('2'),
        molecule: schedule.molecule,
        dosingBasis: schedule.dosingBasis,
        administrationRoute: schedule.administrationRoute);
    await db.insert('medication_schedules', schedule.toMap());
    await db.insert('supply_items', supply.toMap());
    dataset = (await service.snapshot('UTC'))['datasetId'] as String;
  });

  tearDown(() async {
    preferences.dispose();
    await db.close();
    AppDatabase.reset();
  });

  Map<String, dynamic> command(
          {String id = '11111111-1111-4111-8111-111111111111',
          Map<String, dynamic> overrides = const {}}) =>
      {
        'version': 1,
        'id': id,
        'installationId': '22222222-2222-4222-8222-222222222222',
        'datasetId': dataset,
        'kind': 'recordDose',
        'payload': {
          'scheduleId': '10',
          'scheduleRevision': wearSchedule(schedule)['revision'],
          'dose': '2',
          'at': now.millisecondsSinceEpoch,
          'zoneId': 'UTC',
          'scheduledMinute': 480,
          'supplyId': '20',
          'notes': null,
          ...overrides
        },
      };

  Future<Map<String, Object?>> apply(Map<String, dynamic> value) =>
      withClock(Clock.fixed(now), () => service.apply(value));
  Future<String> usedDose() async =>
      (await db.query('supply_items')).single['usedDose'] as String;

  test('patch schedules retain release-rate units through Wear sync', () async {
    schedule = schedule.copyWith(
      administrationRoute: AdministrationRoute.patch,
      dosingBasis: DosingBasis.releaseRate,
      dose: Decimal.fromInt(100),
    );
    await db.update('medication_schedules', schedule.toMap());
    final exported = wearSchedule(schedule);
    expect(exported['unit'], 'µg/day');
    final parsed =
        parseWearSchedule(Map<String, dynamic>.from(exported), schedule.id);
    expect(parsed.molecule, schedule.molecule);
    expect(parsed.dosingBasis, DosingBasis.releaseRate);
    expect(parsed.dose, schedule.dose);

    final receipt = await apply(command(overrides: {
      'supplyId': null,
      'dose': '100',
    }));
    expect(receipt['status'], 'applied');
    final intake = MedicationIntakeMapper.fromMap(
        (await db.query('medication_intakes')).single);
    expect(intake.dosingBasis, DosingBasis.releaseRate);
    final snapshot = await service.snapshot('UTC');
    expect((snapshot['history'] as List).single['unit'], 'µg/day');
  });

  test('a committed retry returns its receipt and deducts inventory once',
      () async {
    final first = await apply(command());
    final second = await apply(command());
    expect(first['status'], 'applied');
    expect(second, first);
    expect(await db.query('medication_intakes'), hasLength(1));
    expect(await usedDose(), '2');
    expect(await db.query('wear_commands'), hasLength(1));
  });

  test('a receipt insert failure rolls back the intake and stock together',
      () async {
    await db.execute(
        "CREATE TRIGGER fail_receipt BEFORE INSERT ON wear_commands BEGIN SELECT RAISE(ABORT, 'simulated crash'); END");
    await expectLater(apply(command()), throwsA(isA<DatabaseException>()));
    expect(await db.query('medication_intakes'), isEmpty);
    expect(await usedDose(), '0');
    await db.execute('DROP TRIGGER fail_receipt');
    expect((await apply(command()))['status'], 'applied');
    expect(await usedDose(), '2');
  });

  test('deleting the intake does not allow replaying a committed command',
      () async {
    final receipt = await apply(command());
    await db.delete('medication_intakes');
    expect(await apply(command()), receipt);
    expect(await db.query('medication_intakes'), isEmpty);
    expect(await usedDose(), '2');
  });

  test('two devices logging the same scheduled dose share one intake',
      () async {
    final first = await apply(command());
    final second =
        await apply(command(id: '33333333-3333-4333-8333-333333333333'));
    expect(second['entityId'], first['entityId']);
    expect(await db.query('medication_intakes'), hasLength(1));
    expect(await usedDose(), '2');
  });

  test('a changed immutable request cannot reuse a command ID', () async {
    await apply(command());
    expect(
        (await apply(command(overrides: {'dose': '3'})))['status'], 'rejected');
    expect(await usedDose(), '2');
  });

  test(
      'stale schedules, replaced datasets and incompatible supplies reject without mutation',
      () async {
    expect(
        (await apply(
            command(overrides: {'scheduleRevision': 'stale'})))['status'],
        'rejected');
    await service.replaceDataset();
    expect(
        (await apply(
            command(id: '33333333-3333-4333-8333-333333333333')))['status'],
        'rejected');
    dataset = (await service.snapshot('UTC'))['datasetId'] as String;
    await db.update('supply_items', {'administrationRoute': 'injection'});
    expect(
        (await apply(
            command(id: '44444444-4444-4444-8444-444444444444')))['status'],
        'rejected');
    expect(await db.query('medication_intakes'), isEmpty);
    expect(await usedDose(), '0');
  });

  test('capture timezone determines logical date after delayed travel sync',
      () async {
    final at = DateTime.utc(2026, 9, 22, 23);
    await apply(command(
        overrides: {'zoneId': 'Asia/Tokyo', 'at': at.millisecondsSinceEpoch}));
    final row = (await db.query('medication_intakes')).single;
    expect(row['takenTimeZone'], 'Asia/Tokyo');
    final snapshot = await service.snapshot('America/Los_Angeles');
    expect((snapshot['history'] as List).single['logicalDate'], '2026-09-23');
  });

  test('medication creation persists an actual schedule and returns stable ID',
      () async {
    final value = command()..['kind'] = 'createMedication';
    value['payload'] = Map<String, dynamic>.from(wearSchedule(schedule))
      ..remove('id')
      ..remove('revision')
      ..['name'] = 'New medication';
    final result = await apply(value);
    expect(result['status'], 'applied');
    expect(await apply(value), result);
    final schedules = await db.query('medication_schedules');
    expect(schedules, hasLength(2));
    expect(schedules.last['name'], 'New medication');
    expect(jsonDecode(schedules.last['scheduling'] as String)['type'], 'daily');
  });

  test('invalid creation is a durable rejection', () async {
    final value = command()..['kind'] = 'createMedication';
    value['payload'] = Map<String, dynamic>.from(wearSchedule(schedule))
      ..['dose'] = '-2';
    expect((await apply(value))['status'], 'rejected');
    expect(await db.query('medication_schedules'), hasLength(1));
    expect(await db.query('wear_commands'), hasLength(1));
  });

  test(
      'phone logging refreshes stale inventory and is atomic with watch logging',
      () async {
    final intakes = MedicationIntakeProvider(
        repository: Repository(
            db: db,
            tableName: 'medication_intakes',
            toMap: (MedicationIntake v) => v.toMap(),
            fromMap: MedicationIntakeMapper.fromMap));
    final supplies = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await Future.wait([intakes.ready, supplies.ready]);
    final staleSupply = supplies.medicationItems.single;
    await apply(command());
    final manager =
        MedicationIntakeManager(intakes, supplies, preferences, database: db);
    await manager.takeMedication(
        takenDose: Decimal.parse('3'),
        takenDateTime: now.add(const Duration(days: 1)),
        takenTimeZone: 'UTC',
        scheduledTime: const TimeOfDay(hour: 8, minute: 0),
        schedule: schedule,
        medicationItem: staleSupply);
    expect(await usedDose(), '5');
    expect(await db.query('medication_intakes'), hasLength(2));
    intakes.dispose();
    supplies.dispose();
  });

  test('selected supply concentration determines the number of pills',
      () async {
    schedule = schedule.copyWith(unitDose: Decimal.parse('2'));
    await db.update('medication_schedules', schedule.toMap());
    await db.update('supply_items', {'dosePerUnit': '1'});
    await apply(command());
    final intake = MedicationIntakeMapper.fromMap(
        (await db.query('medication_intakes')).single);
    expect(intake.unitDose, Decimal.one);
    expect(intake.localizedSummary, startsWith('2 pills'));
  });

  test(
      'injected doses do not acquire tablet unitDose from supply concentration',
      () async {
    schedule = schedule.copyWith(
        unitDose: Decimal.parse('2'),
        administrationRoute: AdministrationRoute.injection,
        ester: Ester.enanthate);
    await db.update('medication_schedules', schedule.toMap());
    await db.update('supply_items', {
      'administrationRoute': 'injection',
      'ester': 'enanthate',
      'dosePerUnit': '20'
    });
    await apply(command());
    final intake = MedicationIntakeMapper.fromMap(
        (await db.query('medication_intakes')).single);
    expect(intake.unitDose, isNull);
  });

  test('editing stale supply metadata preserves a concurrent watch deduction',
      () async {
    final provider = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await provider.ready;
    final original = provider.medicationItems.single;
    await apply(command());
    expect(
        await provider.editMedicationItem(
            original, original.copyWith(name: 'Renamed')),
        isTrue);
    expect(await usedDose(), '2');
    expect((await db.query('supply_items')).single['name'], 'Renamed');
    provider.dispose();
  });

  test(
      'an explicit stock correction preserves the concurrent consumption delta',
      () async {
    final provider = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await provider.ready;
    final original = provider.medicationItems.single;
    await apply(command());
    expect(
        await provider.editMedicationItem(
            original, original.copyWith(usedDose: Decimal.one)),
        isTrue);
    expect(await usedDose(), '3');
    provider.dispose();
  });

  test(
      'a conflicting supply edit cannot overwrite newer metadata or exceed stock',
      () async {
    final provider = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await provider.ready;
    final original = provider.medicationItems.single;
    await apply(command());
    expect(
        await provider.editMedicationItem(
            original, original.copyWith(totalDose: Decimal.one)),
        isFalse);
    await db.update('supply_items', {'name': 'Changed elsewhere'});
    expect(
        await provider.editMedicationItem(
            original, original.copyWith(name: 'Stale edit')),
        isFalse);
    expect(await usedDose(), '2');
    expect(
        (await db.query('supply_items')).single['name'], 'Changed elsewhere');
    provider.dispose();
  });

  test('manual phone history additions permit extra doses on a scheduled day',
      () async {
    final intakes = MedicationIntakeProvider(
        repository: Repository(
            db: db,
            tableName: 'medication_intakes',
            toMap: (MedicationIntake v) => v.toMap(),
            fromMap: MedicationIntakeMapper.fromMap));
    final supplies = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await Future.wait([intakes.ready, supplies.ready]);
    await apply(command());
    final manager =
        MedicationIntakeManager(intakes, supplies, preferences, database: db);
    await manager.takeMedication(
        takenDose: Decimal.parse('3'),
        takenDateTime: now,
        takenTimeZone: 'UTC',
        schedule: schedule,
        medicationItem: supply);
    await manager.takeMedication(
        takenDose: Decimal.parse('4'),
        takenDateTime: now,
        takenTimeZone: 'UTC',
        schedule: schedule,
        medicationItem: supply);
    final rows = await db.query('medication_intakes');
    expect(rows, hasLength(3));
    expect(rows.where((row) => row['scheduledTime'] == null), hasLength(2));
    expect(await usedDose(), '9');
    intakes.dispose();
    supplies.dispose();
  });

  test(
      'explicit scheduled phone logging reconciles a dose already logged on watch',
      () async {
    final intakes = MedicationIntakeProvider(
        repository: Repository(
            db: db,
            tableName: 'medication_intakes',
            toMap: (MedicationIntake v) => v.toMap(),
            fromMap: MedicationIntakeMapper.fromMap));
    final supplies = SupplyItemProvider(
        repository: Repository(
            db: db,
            tableName: 'supply_items',
            toMap: (SupplyItem v) => v.toMap(),
            fromMap: SupplyItemMapper.fromMap));
    await Future.wait([intakes.ready, supplies.ready]);
    await apply(command());
    final manager =
        MedicationIntakeManager(intakes, supplies, preferences, database: db);
    await manager.takeMedication(
        takenDose: Decimal.parse('2'),
        takenDateTime: now,
        takenTimeZone: 'UTC',
        scheduledOccurrence: true,
        scheduledTime: const TimeOfDay(hour: 8, minute: 0),
        schedule: schedule,
        medicationItem: supply);
    expect(await db.query('medication_intakes'), hasLength(1));
    expect(await usedDose(), '2');
    intakes.dispose();
    supplies.dispose();
  });
}
