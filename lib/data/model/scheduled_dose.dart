import 'package:dart_mappable/dart_mappable.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter/material.dart';
import 'package:mona/data/model/custom_mappers.dart';

part 'scheduled_dose.mapper.dart';

@MappableClass(includeCustomMappers: [TimeOfDayMapper(), DecimalStringMapper()])
class ScheduledDose with ScheduledDoseMappable {
  final TimeOfDay time;
  final Decimal dose;

  const ScheduledDose({required this.time, required this.dose});
}
