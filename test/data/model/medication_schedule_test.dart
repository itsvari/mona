import 'package:decimal/decimal.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/ester.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/data/model/scheduled_dose.dart';
import 'package:mona/data/model/scheduling_strategy.dart';

import '../../fixtures.dart';

void main() {
  group('MedicationSchedule', () {
    test('old serialized schedules default to full doses', () {
      final original = aMedicationSchedule(dose: Decimal.fromInt(6));
      final oldMap = original.toMap()
        ..remove('unitDose')
        ..remove('doseOverrides');
      final restored = MedicationScheduleMapper.fromMap(oldMap);
      expect(restored.unitDose, isNull);
      expect(restored.hasSplitDoses, isFalse);
      expect(restored.doseAt(morning), Decimal.fromInt(6));
    });

    test('split doses survive serialization and preserve decimal amounts', () {
      final schedule = aMedicationSchedule(
        dose: Decimal.fromInt(6),
        scheduling: const DailySchedule(intakeTimes: [morning, evening]),
      ).copyWith(unitDose: Decimal.fromInt(2), doseOverrides: [
        ScheduledDose(time: morning, dose: Decimal.parse('1.5')),
        ScheduledDose(time: evening, dose: Decimal.parse('4.5')),
      ]);
      final restored = MedicationScheduleMapper.fromJson(schedule.toJson());
      expect(restored, schedule);
      expect(restored.hasSplitDoses, isTrue);
      expect(restored.doseAt(morning), Decimal.parse('1.5'));
      expect(restored.doseAt(evening), Decimal.parse('4.5'));
      expect(restored.doseAt(null), Decimal.fromInt(6));
    });

    test('unit strength accepts empty or positive decimals only', () {
      expect(MedicationSchedule.validateUnitDose(''), isNull);
      expect(MedicationSchedule.validateUnitDose('0.025'), isNull);
      for (final input in ['0', '-2', 'invalid']) {
        expect(MedicationSchedule.validateUnitDose(input), isNotNull);
      }
    });

    test('validateEster works correctly', () {
      final cases = [
        {
          'molecule': null,
          'route': null,
          'value': null,
          'expected': isNull,
        },
        {
          'molecule': KnownMolecules.estradiol,
          'route': AdministrationRoute.injection,
          'value': null,
          'expected': isNotNull,
        },
        {
          'molecule': KnownMolecules.estradiol,
          'route': AdministrationRoute.injection,
          'value': Ester.enanthate,
          'expected': isNull,
        },
        {
          'molecule': KnownMolecules.estradiol,
          'route': AdministrationRoute.oral,
          'value': Ester.enanthate,
          'expected': isNull,
        },
        {
          'molecule': KnownMolecules.estradiol,
          'route': AdministrationRoute.oral,
          'value': null,
          'expected': isNull,
        },
      ];

      final results = cases.map((c) {
        final validator = MedicationSchedule.esterValidator(
          c['molecule'] as Molecule?,
          c['route'] as AdministrationRoute?,
        );
        return validator(c['value'] as Ester?);
      }).toList();
      final expected = cases.map((c) => c['expected'] as Matcher).toList();

      expect(results, expected);
    });
  });
}
