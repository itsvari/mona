import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mona/data/model/administration_route.dart';
import 'package:mona/data/model/delivery_form.dart';
import 'package:mona/data/model/molecule.dart';
import 'package:mona/ui/widgets/forms/dose_amount_field.dart';

void main() {
  Future<void> mount(
    WidgetTester tester,
    TextEditingController controller, {
    Decimal? strength,
    DeliveryForm? form,
    AdministrationRoute route = AdministrationRoute.oral,
  }) =>
      tester.pumpWidget(MaterialApp(
          home: Scaffold(
              body: DoseAmountField(
        controller: controller,
        unitDose: strength,
        deliveryForm: form,
        route: route,
        molecule: KnownMolecules.estradiol,
        label: 'Taken amount',
        onChanged: () {},
      ))));

  testWidgets(
      'pills prefill, increment, decrement and accept fractional quantities',
      (tester) async {
    final controller = TextEditingController(text: '2');
    await mount(tester, controller, strength: Decimal.fromInt(2));
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('doseQuantity')))
            .controller!
            .text,
        '1');
    expect(
        tester
            .widget<IconButton>(
                find.byKey(const ValueKey('decreaseDoseQuantity')))
            .onPressed,
        isNull);
    await tester.tap(find.byKey(const ValueKey('increaseDoseQuantity')));
    await tester.pump();
    expect(controller.text, '4');
    await tester.tap(find.byKey(const ValueKey('decreaseDoseQuantity')));
    await tester.pump();
    expect(controller.text, '2');
    await tester.enterText(find.byKey(const ValueKey('doseQuantity')), '0.5');
    expect(controller.text, '1');
    await tester.enterText(find.byKey(const ValueKey('doseQuantity')), '');
    await tester.pump();
    expect(controller.text, '');
    await tester.tap(find.byKey(const ValueKey('increaseDoseQuantity')));
    await tester.pump();
    expect(controller.text, '2');
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('patch quantities use the configured strength', (tester) async {
    final controller = TextEditingController(text: '0.1');
    await mount(tester, controller,
        strength: Decimal.parse('0.05'), route: AdministrationRoute.patch);
    expect(
        tester
            .widget<TextField>(find.byKey(const ValueKey('doseQuantity')))
            .controller!
            .text,
        '2');
    await tester.tap(find.byKey(const ValueKey('increaseDoseQuantity')));
    expect(controller.text, '0.15');
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('gel supply quantities retain the delivery form', (tester) async {
    final controller = TextEditingController(text: '3');
    await mount(tester, controller,
        strength: Decimal.parse('1.5'),
        route: AdministrationRoute.gel,
        form: DeliveryForm.sachet);
    final field =
        tester.widget<TextField>(find.byKey(const ValueKey('doseQuantity')));
    expect(field.decoration!.suffixText, 'sachets');
    await tester.tap(find.byKey(const ValueKey('increaseDoseQuantity')));
    expect(controller.text, '4.5');
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('stepping preserves exact dose when the quantity repeats',
      (tester) async {
    final controller = TextEditingController(text: '1');
    await mount(tester, controller, strength: Decimal.fromInt(3));
    await tester.tap(find.byKey(const ValueKey('increaseDoseQuantity')));
    expect(controller.text, '4');
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });

  testWidgets('existing doses without unit strength keep numeric entry',
      (tester) async {
    final controller = TextEditingController(text: '6');
    await mount(tester, controller);
    expect(find.byKey(const ValueKey('doseQuantity')), findsNothing);
    await tester.enterText(find.byKey(const ValueKey('doseAmount')), '4');
    expect(controller.text, '4');
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
  });
}
