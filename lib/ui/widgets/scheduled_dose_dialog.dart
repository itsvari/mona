import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/dosing_basis.dart';
import 'package:mona/data/model/medication_schedule.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/i18n/translations.g.dart';
import 'package:mona/ui/widgets/forms/dose_amount_field.dart';
import 'package:mona/util/string_parsing.dart';

class ScheduledDoseDialog extends StatefulWidget {
  final TimeOfDay time;
  final Decimal dose;
  final Decimal? unitDose;
  final AdministrationRoute route;
  final Molecule molecule;
  final DosingBasis dosingBasis;

  const ScheduledDoseDialog({
    super.key,
    required this.time,
    required this.dose,
    required this.unitDose,
    required this.route,
    required this.molecule,
    this.dosingBasis = DosingBasis.mass,
  });

  @override
  State<ScheduledDoseDialog> createState() => _ScheduledDoseDialogState();
}

class _ScheduledDoseDialogState extends State<ScheduledDoseDialog> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.dose.toString());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('${t.scheduledDose} · ${widget.time.format(context)}'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DoseAmountField(
              controller: _controller,
              unitDose: widget.unitDose,
              route: widget.route,
              molecule: widget.molecule,
              dosingBasis: widget.dosingBasis,
              label: t.scheduledDose,
              onChanged: () => setState(() {}),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: Text(t.cancel)),
        TextButton(
          key: const ValueKey('saveScheduledDose'),
          onPressed: MedicationSchedule.validateDose(_controller.text) == null
              ? () => Navigator.pop(context, _controller.text.toDecimal)
              : null,
          child: Text(t.save),
        ),
      ],
    );
  }
}
