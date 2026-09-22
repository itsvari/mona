import 'package:decimal/decimal.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/i18n/helpers/administration_route_l10n.dart';
import 'package:mona/i18n/helpers/delivery_form_l10n.dart';
import 'package:mona/i18n/helpers/molecule_l10n.dart';
import 'package:mona/i18n/helpers/placement_l10n.dart';

extension MedicationIntakeL10n on MedicationIntake {
  String get localizedSummary {
    final quantity = unitDose == null
        ? null
        : (takenDose.toRational() / unitDose!.toRational())
            .toDecimal(scaleOnInfinitePrecision: 3);
    final units = quantity == null
        ? ''
        : '$quantity ${deliveryForm?.localizedUnit(quantity.toDouble()) ?? administrationRoute.localizedUnit(quantity.toDouble())} · ';
    final intakeString =
        '$units$takenDose ${molecule.localizedUnit(dosingBasis)} • '
        '${molecule.localizedNameWithEster(ester)} • '
        '${administrationRoute.localizedName}';
    if (placements.isEmpty) return intakeString;

    final placementsString = placements.map((p) => p.localizedName).join(', ');
    return '$intakeString • $placementsString';
  }
}
