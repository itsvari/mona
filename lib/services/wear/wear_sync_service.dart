import 'dart:convert';

import 'package:clock/clock.dart';
import 'package:mona/controllers/medication_intake_manager.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/medication_supply_item.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/data/model/supply_item.dart';
import 'package:mona/services/preferences_service.dart';
import 'package:mona/services/wear/wear_protocol.dart';
import 'package:sqflite/sqflite.dart';
import 'package:timezone/timezone.dart' as tz;

class WearSyncService {
  final Database database;
  final PreferencesService preferences;

  WearSyncService(this.database, this.preferences);

  Future<String> _dataset(DatabaseExecutor db) async {
    var rows = await db.query('wear_state');
    if (rows.isEmpty) {
      await db.insert('wear_state',
          {'singleton': 1, 'datasetId': newWearDatasetId(), 'revision': 0});
      rows = await db.query('wear_state');
    }
    return rows.single['datasetId'] as String;
  }

  Future<void> replaceDataset() => database.transaction((txn) async {
        await txn.delete('wear_commands');
        await txn.delete('wear_state');
        await _dataset(txn);
      });

  Future<Map<String, Object?>> apply(Map<String, dynamic> command) =>
      database.transaction((txn) async {
        final dataset = await _dataset(txn);
        final id = wearString(command['id'], 'command ID', max: 36);
        final requestDataset =
            wearString(command['datasetId'], 'dataset ID', max: 36);
        final digest = wearDigest(command);
        Map<String, Object?> receipt(String status, String message,
                [int? entityId]) =>
            {
              'version': 1,
              'id': id,
              'datasetId': requestDataset,
              'status': status,
              'entityId': entityId?.toString(),
              'message': message,
            };
        final previous = await txn.query('wear_commands',
            where: 'datasetId = ? AND commandId = ?',
            whereArgs: [requestDataset, id]);
        if (previous.isNotEmpty) {
          if (previous.single['digest'] != digest) {
            return receipt('rejected', 'This action ID was already used.');
          }
          return Map<String, Object?>.from(
              jsonDecode(previous.single['receipt'] as String));
        }
        Map<String, Object?> result;
        try {
          if (command['version'] != 1) {
            throw const WearRequestException('Update Mona on both devices.');
          }
          if (dataset != requestDataset) {
            throw const WearRequestException(
                'Mona data was replaced. Review this action on your watch.');
          }
          final payload = wearMap(command['payload']);
          final entityId = switch (command['kind']) {
            'recordDose' => await _recordDose(txn, payload),
            'createMedication' => await _createMedication(txn, payload),
            _ => throw const WearRequestException('Unknown action.'),
          };
          result = receipt('applied', 'Saved in Mona.', entityId);
        } on WearRequestException catch (error) {
          result = receipt('rejected', error.message);
        }
        await txn.insert('wear_commands', {
          'datasetId': requestDataset,
          'commandId': id,
          'digest': digest,
          'receipt': jsonEncode(result)
        });
        return result;
      });

  Future<int> _nextId(DatabaseExecutor db, String table) async =>
      Sqflite.firstIntValue(
          await db.rawQuery('SELECT COALESCE(MAX(id), 0) + 1 FROM $table'))!;

  Future<int> _createMedication(
      Transaction txn, Map<String, dynamic> data) async {
    final id = await _nextId(txn, 'medication_schedules');
    final schedule = parseWearSchedule(data, id);
    await txn.insert('medication_schedules', schedule.toMap());
    return id;
  }

  Future<int> _recordDose(Transaction txn, Map<String, dynamic> data) async {
    final scheduleId = wearEntityId(data['scheduleId']);
    final rows = await txn.query('medication_schedules',
        where: 'id = ?', whereArgs: [scheduleId]);
    if (rows.isEmpty) {
      throw const WearRequestException('This medication was deleted in Mona.');
    }
    final schedule = MedicationScheduleMapper.fromMap(rows.single);
    if (wearSchedule(schedule)['revision'] != data['scheduleRevision']) {
      throw const WearRequestException(
          'This medication changed in Mona. Sync and review the dose.');
    }
    final dose = wearDose(data['dose']);
    final at = DateTime.fromMillisecondsSinceEpoch(
        wearInt(data['at'], 'dose time', 0, 7258118400000),
        isUtc: true);
    if (at.isAfter(clock.now().toUtc().add(const Duration(minutes: 10)))) {
      throw const WearRequestException(
          'The dose time is in the future. Check your watch clock.');
    }
    final zone = wearString(data['zoneId'], 'time zone', max: 100);
    final tz.Location location;
    try {
      location = tz.getLocation(zone);
    } on tz.LocationNotFoundException {
      throw const WearRequestException('Unknown time zone.');
    }
    final time = data['scheduledMinute'] == null
        ? null
        : wearTime(data['scheduledMinute']);
    final usesTimes =
        schedule.scheduling is DailySchedule || schedule.hasSplitDoses;
    if (usesTimes && (time == null || !schedule.intakeTimes.contains(time))) {
      throw const WearRequestException('Choose a scheduled dose time.');
    }
    if (!usesTimes && time != null) {
      throw const WearRequestException(
          'This medication does not use separate dose times.');
    }
    if (schedule.scheduling is! AsNeededSchedule) {
      final day = Date.fromDateTime(tz.TZDateTime.from(at, location));
      final intakes = await txn.query('medication_intakes',
          where: 'scheduleId = ?', whereArgs: [scheduleId]);
      for (final row in intakes) {
        final intake = MedicationIntakeMapper.fromMap(row);
        if (intake.isTaken &&
            intake.takenLocalDate == day &&
            (!usesTimes || intake.scheduledTime == time)) {
          return intake.id;
        }
      }
    }
    MedicationSupplyItem? supply;
    if (data['supplyId'] != null) {
      final supplies = await txn.query('supply_items',
          where: 'id = ?', whereArgs: [wearEntityId(data['supplyId'])]);
      final item =
          supplies.isEmpty ? null : SupplyItemMapper.fromMap(supplies.single);
      if (item is! MedicationSupplyItem ||
          item.molecule != schedule.molecule ||
          item.administrationRoute != schedule.administrationRoute ||
          item.ester != schedule.ester ||
          item.dosingBasis != schedule.dosingBasis) {
        throw const WearRequestException(
            'The selected supply is unavailable or incompatible.');
      }
      if (item.remainingDose < dose) {
        throw const WearRequestException(
            'The selected supply has insufficient medication.');
      }
      supply = item;
    }
    final notes = data['notes'] == null
        ? null
        : wearString(data['notes'], 'notes', max: 1000);
    final id = await _nextId(txn, 'medication_intakes');
    final manager =
        await MedicationIntakeManager.forTransaction(txn, preferences);
    try {
      await manager.takeMedication(
          intakeId: id,
          takenTimeZone: zone,
          takenDose: dose,
          unitDose:
              schedule.administrationRoute == AdministrationRoute.injection
                  ? null
                  : supply?.dosePerUnit ?? schedule.unitDose,
          scheduledTime: time,
          takenDateTime: at,
          schedule: schedule,
          medicationItem: supply,
          notes: notes);
    } finally {
      manager.disposeTransactionProviders();
    }
    return id;
  }

  Future<Map<String, Object?>> snapshot(String zoneId) =>
      database.transaction((txn) async {
        final dataset = await _dataset(txn);
        await txn.rawUpdate(
            'UPDATE wear_state SET revision = revision + 1 WHERE singleton = 1');
        final revision = (await txn.query('wear_state')).single['revision'];
        final schedules = (await txn.query('medication_schedules'))
            .map(MedicationScheduleMapper.fromMap)
            .toList();
        final order = preferences.scheduleOrder;
        schedules.sort((a, b) {
          final ai = order.indexOf(a.id), bi = order.indexOf(b.id);
          if (ai >= 0 && bi >= 0) return ai.compareTo(bi);
          if (ai >= 0) return -1;
          if (bi >= 0) return 1;
          return a.id.compareTo(b.id);
        });
        final all = (await txn.query('medication_intakes'))
            .map(MedicationIntakeMapper.fromMap)
            .where((v) => v.isTaken)
            .toList()
          ..sort((a, b) => b.takenDateTime!.compareTo(a.takenDateTime!));
        final history = <int, MedicationIntake>{
          for (final intake in all.take(120)) intake.id: intake
        };
        final latestKeys = <String>{};
        for (final intake in all) {
          if (intake.scheduleId == null) continue;
          final key =
              '${intake.scheduleId}/${intake.scheduledTime == null ? '' : wearMinute(intake.scheduledTime!)}';
          if (latestKeys.add(key)) history[intake.id] = intake;
        }
        final supplies = (await txn.query('supply_items'))
            .map(SupplyItemMapper.fromMap)
            .whereType<MedicationSupplyItem>();
        final molecules = <String, Molecule>{
          for (final molecule in [
            ...KnownMolecules.all,
            ...preferences.customMolecules,
            ...schedules.map((v) => v.molecule)
          ])
            '${molecule.name}/${molecule.massUnit}': molecule,
        };
        return {
          'version': 1,
          'datasetId': dataset,
          'revision': revision,
          'generatedAt': clock.now().toUtc().millisecondsSinceEpoch,
          'zoneId': zoneId,
          'logicalDayStartMinutes': preferences.logicalDayStartMinutesRaw,
          'notificationsEnabled': preferences.notificationsEnabled,
          'schedules': schedules.map(wearSchedule).toList(),
          'history': history.values
              .map((v) => {
                    'id': v.id.toString(),
                    'scheduleId': v.scheduleId?.toString(),
                    'dose': v.takenDose.toString(),
                    'unit': wearDoseUnit(v.molecule, v.dosingBasis),
                    'at': v.takenDateTime!.millisecondsSinceEpoch,
                    'zoneId': v.takenTimeZone,
                    'scheduledMinute': v.scheduledTime == null
                        ? null
                        : wearMinute(v.scheduledTime!),
                    'logicalDate': wearDate(v.takenLocalDate!),
                  })
              .toList(),
          'supplies': supplies
              .map((v) => {
                    'id': v.id.toString(),
                    'name': v.name,
                    'moleculeName': v.molecule.name,
                    'unit': wearDoseUnit(v.molecule, v.dosingBasis),
                    'route': v.administrationRoute.name,
                    'ester': v.ester?.name,
                    'remainingDose': v.remainingDose.toString(),
                    'unitDose': v.dosePerUnit.toString(),
                    'deliveryForm': v.deliveryForm?.name,
                  })
              .toList(),
          'molecules': molecules.values
              .map((v) => {'name': v.name, 'unit': v.massUnit})
              .toList(),
        };
      });
}
