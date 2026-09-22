import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:material_symbols_icons/symbols.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/delivery_form.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/medication_intake.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/i18n/helpers/administration_route_l10n.dart';
import 'package:mona/i18n/helpers/delivery_form_l10n.dart';
import 'package:mona/i18n/helpers/molecule_l10n.dart';
import 'package:mona/i18n/translations.g.dart';
import 'package:mona/ui/widgets/forms/form_text_field.dart';
import 'package:mona/util/regex_patterns.dart';
import 'package:mona/util/string_parsing.dart';

class DoseAmountField extends StatefulWidget {
  final TextEditingController controller;
  final Decimal? unitDose;
  final DeliveryForm? deliveryForm;
  final AdministrationRoute route;
  final Molecule molecule;
  final DosingBasis dosingBasis;
  final String label;
  final VoidCallback onChanged;

  const DoseAmountField({
    super.key,
    required this.controller,
    required this.unitDose,
    this.deliveryForm,
    required this.route,
    required this.molecule,
    this.dosingBasis = DosingBasis.mass,
    required this.label,
    required this.onChanged,
  });

  @override
  State<DoseAmountField> createState() => _DoseAmountFieldState();
}

class _DoseAmountFieldState extends State<DoseAmountField> {
  final _quantityController = TextEditingController();
  bool _editingQuantity = false;

  Decimal? get _strength => widget.unitDose;

  String _unit(num count) =>
      widget.deliveryForm?.localizedUnit(count) ??
      widget.route.localizedUnit(count);

  void _syncQuantity() {
    if (_editingQuantity || _strength == null) return;
    final dose = widget.controller.text.toDecimalOrNull;
    _quantityController.text = dose == null
        ? ''
        : (dose.toRational() / _strength!.toRational())
            .toDecimal(scaleOnInfinitePrecision: 6)
            .toString();
  }

  void _quantityChanged() {
    final quantity = _quantityController.text.toDecimalOrNull;
    _editingQuantity = true;
    widget.controller.text =
        quantity == null ? '' : (quantity * _strength!).toString();
    _editingQuantity = false;
    widget.onChanged();
    setState(() {});
  }

  void _step(Decimal delta) {
    final dose = widget.controller.text.toDecimalOrNull ?? Decimal.zero;
    widget.controller.text = (dose + delta * _strength!).toString();
    widget.onChanged();
    setState(() {});
  }

  @override
  void initState() {
    super.initState();
    _syncQuantity();
    widget.controller.addListener(_syncQuantity);
  }

  @override
  void didUpdateWidget(DoseAmountField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_syncQuantity);
      widget.controller.addListener(_syncQuantity);
    }
    if (oldWidget.unitDose != widget.unitDose ||
        oldWidget.controller != widget.controller) {
      _syncQuantity();
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_syncQuantity);
    _quantityController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final error = MedicationIntake.validateDose(widget.controller.text);
    if (_strength == null) {
      return FormTextField(
        controller: widget.controller,
        fieldKey: const ValueKey('doseAmount'),
        label: widget.label,
        onChanged: widget.onChanged,
        inputType: const TextInputType.numberWithOptions(decimal: true),
        suffixText: widget.molecule.localizedUnit(widget.dosingBasis),
        errorText: error,
        regexFormatter: RegexPatterns.floatNumber,
      );
    }

    final quantity = _quantityController.text.toDecimalOrNull;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            IconButton.filledTonal(
              key: const ValueKey('decreaseDoseQuantity'),
              tooltip: t.decreaseQuantity,
              onPressed: quantity != null && quantity > Decimal.one
                  ? () => _step(-Decimal.one)
                  : null,
              icon: const Icon(Symbols.remove_rounded),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: FormTextField(
                controller: _quantityController,
                fieldKey: const ValueKey('doseQuantity'),
                label: t.unitQuantity,
                suffixText: _unit(quantity?.toDouble() ?? 1),
                onChanged: _quantityChanged,
                inputType: const TextInputType.numberWithOptions(decimal: true),
                errorText: error,
                regexFormatter: RegexPatterns.floatNumber,
              ),
            ),
            const SizedBox(width: 12),
            IconButton.filledTonal(
              key: const ValueKey('increaseDoseQuantity'),
              tooltip: t.increaseQuantity,
              onPressed: () => _step(Decimal.one),
              icon: const Icon(Symbols.add_rounded),
            ),
          ],
        ),
        Text(
            '${widget.label}: ${widget.controller.text} ${widget.molecule.localizedUnit(widget.dosingBasis)}'),
        Text(
            '${_strength!} ${widget.molecule.localizedUnit(widget.dosingBasis)}/${_unit(1)}'),
      ],
    );
  }
}
