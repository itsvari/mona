import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/scheduling_strategy.dart';
import 'package:mona/i18n/build_context_extensions.dart';
import 'package:mona/i18n/helpers/administration_route_l10n.dart';
import 'package:mona/i18n/helpers/molecule_l10n.dart';
import 'package:mona/i18n/translations.g.dart';

extension MedicationScheduleL10n on MedicationSchedule {
  String get localizedSummary => '${localizedDoseAt(null)} • '
      '${molecule.localizedNameWithEster(ester)} • '
      '${administrationRoute.localizedName}';

  String localizedDoseAt(TimeOfDay? time) {
    final amount = doseAt(time);
    final strength = unitDose;
    final doseLabel = '$amount ${molecule.localizedUnit(dosingBasis)}';
    if (strength == null) return doseLabel;
    final quantity = (amount.toRational() / strength.toRational())
        .toDecimal(scaleOnInfinitePrecision: 3);
    return '$quantity ${administrationRoute.localizedUnit(quantity.toDouble())} ($doseLabel)';
  }

  String get localizedFrequency {
    return switch (scheduling) {
      IntervalDaysSchedule(intervalDays: final n) =>
        t.scheduleFrequencyEveryNDays(count: n),
      DynamicIntervalSchedule(intervalDays: final n) =>
        t.scheduleFrequencyEveryNDays(count: n),
      DailySchedule _ => t.scheduleFrequencyEveryNDays(count: 1),
      WeeklySchedule s => () {
          if (s.daysOfWeek.length == 7) {
            return t.scheduleFrequencyEveryNDays(count: 1);
          }
          final formatter = DateFormat.E(
              intlSafeLanguageTag(LocaleSettings.currentLocale.languageTag));
          final days = s.daysOfWeek
              .map((d) => formatter.format(DateTime(2024, 1, d)))
              .join(', ');
          return days[0].toUpperCase() + days.substring(1);
        }(),
      MonthlySchedule(:final dayOfMonth, :final intervalMonths) =>
        t.scheduleFrequencyOnDayEveryNMonths(
            count: intervalMonths, day: dayOfMonth),
      AsNeededSchedule _ => t.scheduleFrequencyAsNeeded,
    };
  }

  String get localizedSummaryWithFrequency =>
      '$localizedSummary\n$localizedFrequency';
}
