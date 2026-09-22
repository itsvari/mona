import 'package:clock/clock.dart';
import 'package:dart_mappable/dart_mappable.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/custom_mappers.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/ester.dart';
import 'package:mona/data/model/mapping_hooks.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/i18n/translations.g.dart';
import 'package:mona/util/validators.dart';

part 'medication_schedule.mapper.dart';

@MappableClass(
  includeCustomMappers: [
    DecimalStringMapper(),
    DateStringMapper(),
  ],
  generateMethods: GenerateMethods.all,
)
class MedicationSchedule with MedicationScheduleMappable {
  final int id;
  final String name;
  final Decimal dose;
  final Decimal? unitDose;
  @MappableField(hook: JsonStringHook())
  final List<ScheduledDose> doseOverrides;
  final Date startDate;
  @MappableField(hook: JsonStringHook())
  final Molecule molecule;
  final AdministrationRoute administrationRoute;
  final Ester? ester;
  @MappableField(hook: JsonStringHook())
  final SchedulingStrategy scheduling;
  final DosingBasis dosingBasis;

  MedicationSchedule({
    int? id,
    required this.name,
    required this.dose,
    this.unitDose,
    this.doseOverrides = const [],
    required this.scheduling,
    Date? startDate,
    required this.molecule,
    required this.administrationRoute,
    this.ester,
    required this.dosingBasis,
  })  : id = id ?? clock.now().millisecondsSinceEpoch,
        startDate = startDate ?? Date.today();

  List<TimeOfDay> get intakeTimes => switch (scheduling) {
        DailySchedule(:final intakeTimes) => intakeTimes,
        IntervalDaysSchedule(:final notificationTimes) => notificationTimes,
        DynamicIntervalSchedule(:final notificationTimes) => notificationTimes,
        WeeklySchedule(:final notificationTimes) => notificationTimes,
        MonthlySchedule(:final notificationTimes) => notificationTimes,
        AsNeededSchedule() => const [],
      };

  bool get hasSplitDoses =>
      doseOverrides.any((entry) => intakeTimes.contains(entry.time));

  Decimal doseAt(TimeOfDay? time) {
    for (final entry in doseOverrides) {
      if (entry.time == time) return entry.dose;
    }
    return dose;
  }

  static String? Function(Ester?) esterValidator(
      Molecule? molecule, AdministrationRoute? administrationRoute) {
    return (Ester? value) {
      return (molecule == KnownMolecules.estradiol &&
              administrationRoute == AdministrationRoute.injection &&
              value == null)
          ? t.requiredField
          : null;
    };
  }

  // coverage:ignore-start
  static String? validateName(String? value) => requiredString(value);

  static String? validateDose(String? value) =>
      requiredStrictlyPositiveDecimal(value);

  static String? validateUnitDose(String? value) =>
      strictlyPositiveDecimal(value);

  static String? validateStartDate(Date? value) => requiredDate(value);

  static String? validateMolecule(Molecule? value) => requiredMolecule(value);

  static String? validateAdministrationRoute(AdministrationRoute? value) =>
      requiredAdministrationRoute(value);
  // coverage:ignore-end
}
