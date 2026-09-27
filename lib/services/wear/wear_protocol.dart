import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/ester.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';

class WearRequestException implements Exception {
  final String message;
  const WearRequestException(this.message);
}

String newWearDatasetId() {
  final random = Random.secure();
  final bytes = List.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((v) => v.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String wearDigest(Object? value) {
  Object? canonical(Object? item) {
    if (item is Map) {
      final keys = item.keys.cast<String>().toList()..sort();
      return {for (final key in keys) key: canonical(item[key])};
    }
    if (item is List) return item.map(canonical).toList();
    return item;
  }

  return sha256.convert(utf8.encode(jsonEncode(canonical(value)))).toString();
}

String wearString(Object? value, String field, {int max = 120}) {
  if (value is! String || value.trim().isEmpty || value.length > max) {
    throw WearRequestException('Invalid $field.');
  }
  return value.trim();
}

int wearInt(Object? value, String field, int min, int max) {
  if (value is! int || value < min || value > max) {
    throw WearRequestException('Invalid $field.');
  }
  return value;
}

int wearEntityId(Object? value) {
  final id = value is String ? int.tryParse(value) : null;
  if (id == null || id <= 0) {
    throw const WearRequestException('Invalid record ID.');
  }
  return id;
}

Decimal wearDose(Object? value, {String field = 'dose'}) {
  if (value is! String ||
      value.length > 30 ||
      !RegExp(r'^\d+(\.\d+)?$').hasMatch(value)) {
    throw WearRequestException('Invalid $field.');
  }
  final dose = Decimal.parse(value);
  if (dose <= Decimal.zero) throw WearRequestException('Invalid $field.');
  return dose;
}

Map<String, dynamic> wearMap(Object? value) {
  if (value is! Map<String, dynamic>) {
    throw const WearRequestException('Invalid request.');
  }
  return value;
}

List<dynamic> wearList(Object? value, {int max = 24}) {
  if (value is! List || value.length > max) {
    throw const WearRequestException('Invalid list.');
  }
  return value;
}

String wearDate(Date value) => value.toString().substring(0, 10);
int wearMinute(TimeOfDay value) => value.hour * 60 + value.minute;
TimeOfDay wearTime(Object? value) {
  final minute = wearInt(value, 'time', 0, 1439);
  return TimeOfDay(hour: minute ~/ 60, minute: minute % 60);
}

String wearDoseUnit(Molecule molecule, DosingBasis dosingBasis) =>
    switch (dosingBasis) {
      DosingBasis.mass => molecule.massUnit,
      DosingBasis.releaseRate => molecule.rateUnit ?? molecule.massUnit,
    };

Map<String, Object?> wearSchedule(MedicationSchedule schedule) {
  final recurrence = switch (schedule.scheduling) {
    DailySchedule s => <String, Object?>{'type': 'daily', 'notify': s.notify},
    IntervalDaysSchedule s => {
        'type': 'intervalDays',
        'intervalDays': s.intervalDays,
        'notify': s.isNotifiable
      },
    DynamicIntervalSchedule s => {
        'type': 'dynamicInterval',
        'intervalDays': s.intervalDays,
        'notify': s.isNotifiable
      },
    WeeklySchedule s => {
        'type': 'weekly',
        'weekdays': s.daysOfWeek,
        'notify': s.isNotifiable
      },
    MonthlySchedule s => {
        'type': 'monthly',
        'dayOfMonth': s.dayOfMonth,
        'intervalMonths': s.intervalMonths,
        'notify': s.isNotifiable
      },
    AsNeededSchedule() => {'type': 'asNeeded', 'notify': false},
  };
  recurrence['times'] = schedule.intakeTimes.map(wearMinute).toList();
  final data = <String, Object?>{
    'id': schedule.id.toString(),
    'name': schedule.name,
    'dose': schedule.dose.toString(),
    'unitDose': schedule.unitDose?.toString(),
    'moleculeName': schedule.molecule.name,
    'unit': wearDoseUnit(schedule.molecule, schedule.dosingBasis),
    'route': schedule.administrationRoute.name,
    'ester': schedule.ester?.name,
    'startDate': wearDate(schedule.startDate),
    'recurrence': recurrence,
    'doseOverrides': schedule.doseOverrides
        .map((v) => {'minute': wearMinute(v.time), 'dose': v.dose.toString()})
        .toList(),
  };
  return {...data, 'revision': wearDigest(data)};
}

MedicationSchedule parseWearSchedule(Map<String, dynamic> data, int id) {
  final routeName = wearString(data['route'], 'route');
  final route =
      AdministrationRoute.values.where((v) => v.name == routeName).firstOrNull;
  if (route == null) {
    throw const WearRequestException('Unknown administration route.');
  }
  final esterName = data['ester'];
  final ester = Ester.values.where((v) => v.name == esterName).firstOrNull;
  if (esterName != null && ester == null) {
    throw const WearRequestException('Unknown ester.');
  }
  final moleculeName = wearString(data['moleculeName'], 'medication');
  final unit = wearString(data['unit'], 'unit', max: 20);
  final molecule = KnownMolecules.all
          .where((candidate) =>
              candidate.normalizedName == moleculeName.toLowerCase() &&
              (candidate.massUnit == unit ||
                  (route == AdministrationRoute.patch &&
                      candidate.rateUnit == unit)))
          .firstOrNull ??
      Molecule(name: moleculeName, massUnit: unit);
  final dosingBasis =
      route == AdministrationRoute.patch && molecule.rateUnit == unit
          ? DosingBasis.releaseRate
          : DosingBasis.mass;
  if (molecule.normalizedName == KnownMolecules.estradiol.normalizedName &&
      route == AdministrationRoute.injection &&
      ester == null) {
    throw const WearRequestException(
        'Choose an ester for injectable estradiol.');
  }
  final dateString = wearString(data['startDate'], 'start date', max: 10);
  final dateValue = DateTime.tryParse('${dateString}T00:00:00.000Z');
  if (dateValue == null ||
      dateValue.toIso8601String().substring(0, 10) != dateString ||
      dateValue.year < 1900 ||
      dateValue.year > 2200) {
    throw const WearRequestException('Invalid start date.');
  }
  final recurrence = wearMap(data['recurrence']);
  if (recurrence['notify'] is! bool) {
    throw const WearRequestException('Invalid reminder preference.');
  }
  final notify = recurrence['notify'] as bool;
  final times = wearList(recurrence['times']).map(wearTime).toList();
  if (times.toSet().length != times.length) {
    throw const WearRequestException('Duplicate reminder time.');
  }
  final notificationTimes = notify ? times : <TimeOfDay>[];
  final SchedulingStrategy scheduling;
  switch (recurrence['type']) {
    case 'daily':
      if (times.isEmpty) throw const WearRequestException('Choose a time.');
      scheduling = DailySchedule(intakeTimes: times, notify: notify);
    case 'intervalDays':
      scheduling = IntervalDaysSchedule(
          intervalDays: wearInt(
              recurrence['intervalDays'], 'interval', 1, maxIntervalDays),
          notificationTimes: notificationTimes);
    case 'dynamicInterval':
      scheduling = DynamicIntervalSchedule(
          intervalDays: wearInt(
              recurrence['intervalDays'], 'interval', 1, maxIntervalDays),
          notificationTimes: notificationTimes);
    case 'weekly':
      final days = wearList(recurrence['weekdays'], max: 7)
          .map((v) => wearInt(v, 'weekday', 1, 7))
          .toList();
      if (days.isEmpty || days.toSet().length != days.length) {
        throw const WearRequestException('Choose unique weekdays.');
      }
      scheduling = WeeklySchedule(
          daysOfWeek: days, notificationTimes: notificationTimes);
    case 'monthly':
      scheduling = MonthlySchedule(
          dayOfMonth: wearInt(recurrence['dayOfMonth'], 'day of month', 1, 28),
          intervalMonths: wearInt(recurrence['intervalMonths'],
              'month interval', 1, maxIntervalMonths),
          notificationTimes: notificationTimes);
    case 'asNeeded':
      if (times.isNotEmpty || notify) {
        throw const WearRequestException(
            'As-needed medications do not have reminders.');
      }
      scheduling = const AsNeededSchedule();
    default:
      throw const WearRequestException('Unknown recurrence.');
  }
  final overrides = wearList(data['doseOverrides'] ?? []).map((entry) {
    final override = wearMap(entry);
    final time = wearTime(override['minute']);
    if (!times.contains(time)) {
      throw const WearRequestException('Dose override needs a scheduled time.');
    }
    return ScheduledDose(time: time, dose: wearDose(override['dose']));
  }).toList();
  if (overrides.map((v) => v.time).toSet().length != overrides.length) {
    throw const WearRequestException('Duplicate dose override.');
  }
  return MedicationSchedule(
      id: id,
      name: wearString(data['name'], 'name'),
      dose: wearDose(data['dose']),
      unitDose: data['unitDose'] == null
          ? null
          : wearDose(data['unitDose'], field: 'unit dose'),
      doseOverrides: overrides,
      startDate: Date.fromUtc(dateValue),
      molecule: molecule,
      dosingBasis: dosingBasis,
      administrationRoute: route,
      ester: ester,
      scheduling: scheduling);
}
