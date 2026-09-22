import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/services/db/app_database.dart';
import 'package:mona/services/db/db_tables.dart';
import 'package:mona/services/db/historical_schemas.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  for (var version = oldestImportableVersion;
      version <= currentDatabaseVersion;
      version++) {
    test('backup schema v$version upgrades to current columns', () async {
      final restored =
          await openDatabase(inMemoryDatabasePath, singleInstance: false);
      final fresh =
          await openDatabase(inMemoryDatabasePath, singleInstance: false);
      addTearDown(restored.close);
      addTearDown(fresh.close);
      for (final sql in historicalSchemaFor(version)) {
        await restored.execute(sql);
      }
      for (final sql in [
        createSupplyItemsTable,
        createMedicationSchedulesTable,
        createMedicationIntakesTable,
        createBloodTestsTable
      ]) {
        await fresh.execute(sql);
      }
      await AppDatabase.getInstance()
          .applyAppUpgrades(restored, version, currentDatabaseVersion);
      for (final table in [
        'medication_schedules',
        'medication_intakes',
        'supply_items',
        'blood_tests'
      ]) {
        final actual = await restored.rawQuery('PRAGMA table_info($table)');
        final expected = await fresh.rawQuery('PRAGMA table_info($table)');
        expect(actual.map((column) => column['name']).toSet(),
            expected.map((column) => column['name']).toSet(),
            reason: table);
      }
    });
  }

  test('v21 release-rate doses survive the unit-dose upgrade', () async {
    final database = await openDatabase(inMemoryDatabasePath);
    addTearDown(database.close);
    for (final statement in historicalSchemaFor(21)) {
      await database.execute(statement);
    }
    const molecule = '{"name":"estradiol","massUnit":"mg","rateUnit":"µg/day"}';
    await database.insert('medication_schedules', {
      'id': 1,
      'name': 'Estradiol patch',
      'dose': '100',
      'startDate': '2026-09-22T00:00:00.000Z',
      'molecule': molecule,
      'administrationRoute': 'patch',
      'scheduling': '{"type":"daily","intakeTimes":["8:0"],"notify":true}',
      'dosingBasis': 'releaseRate',
    });
    await database.insert('medication_intakes', {
      'id': 2,
      'scheduleId': 1,
      'takenDose': '100',
      'molecule': molecule,
      'administrationRoute': 'patch',
      'genericSupplyItemIds': '[]',
      'placements': '[]',
      'dosingBasis': 'releaseRate',
    });

    await AppDatabase.getInstance()
        .applyAppUpgrades(database, 21, currentDatabaseVersion);
    final schedule = MedicationScheduleMapper.fromMap(
        (await database.query('medication_schedules')).single);
    final intake = MedicationIntakeMapper.fromMap(
        (await database.query('medication_intakes')).single);
    expect(schedule.dosingBasis, DosingBasis.releaseRate);
    expect(schedule.dose, Decimal.fromInt(100));
    expect(schedule.unitDose, isNull);
    expect(schedule.doseOverrides, isEmpty);
    expect(intake.dosingBasis, DosingBasis.releaseRate);
    expect(intake.takenDose, Decimal.fromInt(100));
    expect(intake.unitDose, isNull);
  });

  test('v20 schedules and intakes retain their amounts after upgrading',
      () async {
    final db = await openDatabase(inMemoryDatabasePath);
    addTearDown(db.close);
    for (final sql in historicalSchemaFor(20)) {
      await db.execute(sql);
    }
    await db.insert('medication_schedules', {
      'id': 1,
      'name': 'Estradiol',
      'dose': '6',
      'startDate': '2026-09-22T00:00:00.000Z',
      'molecule': '{"name":"estradiol","unit":"mg"}',
      'administrationRoute': 'oral',
      'scheduling':
          '{"type":"daily","intakeTimes":["8:0","20:0"],"notify":true}',
    });
    await db.insert('medication_intakes', {
      'id': 2,
      'scheduleId': 1,
      'takenDose': '2',
      'molecule': '{"name":"estradiol","unit":"mg"}',
      'administrationRoute': 'oral',
      'genericSupplyItemIds': '[]',
      'placements': '[]',
    });

    await AppDatabase.getInstance()
        .applyAppUpgrades(db, 20, currentDatabaseVersion);
    final schedule = MedicationScheduleMapper.fromMap(
        (await db.query('medication_schedules')).single);
    final intake = MedicationIntakeMapper.fromMap(
        (await db.query('medication_intakes')).single);
    expect(schedule.unitDose, isNull);
    expect(schedule.doseOverrides, isEmpty);
    expect(schedule.doseAt(const TimeOfDay(hour: 8, minute: 0)),
        Decimal.fromInt(6));
    expect(intake.takenDose, Decimal.fromInt(2));
    expect(intake.unitDose, isNull);

    final updated = schedule.copyWith(
      unitDose: Decimal.fromInt(2),
      doseOverrides: [
        ScheduledDose(
            time: const TimeOfDay(hour: 8, minute: 0),
            dose: Decimal.fromInt(2)),
        ScheduledDose(
            time: const TimeOfDay(hour: 20, minute: 0),
            dose: Decimal.fromInt(4)),
      ],
    );
    await db.update('medication_schedules', updated.toMap(),
        where: 'id = ?', whereArgs: [1]);
    final restored = MedicationScheduleMapper.fromMap(
        (await db.query('medication_schedules')).single);
    expect(restored, updated);
    expect(restored.doseAt(const TimeOfDay(hour: 20, minute: 0)),
        Decimal.fromInt(4));
  });
}
