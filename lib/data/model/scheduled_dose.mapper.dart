// coverage:ignore-file
// GENERATED CODE - DO NOT MODIFY BY HAND
// dart format off
// ignore_for_file: type=lint
// ignore_for_file: invalid_use_of_protected_member
// ignore_for_file: unused_element, unnecessary_cast, override_on_non_overriding_member
// ignore_for_file: strict_raw_type, inference_failure_on_untyped_parameter

part of 'scheduled_dose.dart';

class ScheduledDoseMapper extends ClassMapperBase<ScheduledDose> {
  ScheduledDoseMapper._();

  static ScheduledDoseMapper? _instance;
  static ScheduledDoseMapper ensureInitialized() {
    if (_instance == null) {
      MapperContainer.globals.use(_instance = ScheduledDoseMapper._());
      MapperContainer.globals.useAll([
        TimeOfDayMapper(),
        DecimalStringMapper(),
      ]);
    }
    return _instance!;
  }

  @override
  final String id = 'ScheduledDose';

  static TimeOfDay _$time(ScheduledDose v) => v.time;
  static const Field<ScheduledDose, TimeOfDay> _f$time = Field('time', _$time);
  static Decimal _$dose(ScheduledDose v) => v.dose;
  static const Field<ScheduledDose, Decimal> _f$dose = Field('dose', _$dose);

  @override
  final MappableFields<ScheduledDose> fields = const {
    #time: _f$time,
    #dose: _f$dose,
  };

  static ScheduledDose _instantiate(DecodingData data) {
    return ScheduledDose(time: data.dec(_f$time), dose: data.dec(_f$dose));
  }

  @override
  final Function instantiate = _instantiate;

  static ScheduledDose fromMap(Map<String, dynamic> map) {
    return ensureInitialized().decodeMap<ScheduledDose>(map);
  }

  static ScheduledDose fromJson(String json) {
    return ensureInitialized().decodeJson<ScheduledDose>(json);
  }
}

mixin ScheduledDoseMappable {
  String toJson() {
    return ScheduledDoseMapper.ensureInitialized().encodeJson<ScheduledDose>(
      this as ScheduledDose,
    );
  }

  Map<String, dynamic> toMap() {
    return ScheduledDoseMapper.ensureInitialized().encodeMap<ScheduledDose>(
      this as ScheduledDose,
    );
  }

  ScheduledDoseCopyWith<ScheduledDose, ScheduledDose, ScheduledDose>
      get copyWith => _ScheduledDoseCopyWithImpl<ScheduledDose, ScheduledDose>(
            this as ScheduledDose,
            $identity,
            $identity,
          );
  @override
  String toString() {
    return ScheduledDoseMapper.ensureInitialized().stringifyValue(
      this as ScheduledDose,
    );
  }

  @override
  bool operator ==(Object other) {
    return ScheduledDoseMapper.ensureInitialized().equalsValue(
      this as ScheduledDose,
      other,
    );
  }

  @override
  int get hashCode {
    return ScheduledDoseMapper.ensureInitialized().hashValue(
      this as ScheduledDose,
    );
  }
}

extension ScheduledDoseValueCopy<$R, $Out>
    on ObjectCopyWith<$R, ScheduledDose, $Out> {
  ScheduledDoseCopyWith<$R, ScheduledDose, $Out> get $asScheduledDose =>
      $base.as((v, t, t2) => _ScheduledDoseCopyWithImpl<$R, $Out>(v, t, t2));
}

abstract class ScheduledDoseCopyWith<$R, $In extends ScheduledDose, $Out>
    implements ClassCopyWith<$R, $In, $Out> {
  $R call({TimeOfDay? time, Decimal? dose});
  ScheduledDoseCopyWith<$R2, $In, $Out2> $chain<$R2, $Out2>(Then<$Out2, $R2> t);
}

class _ScheduledDoseCopyWithImpl<$R, $Out>
    extends ClassCopyWithBase<$R, ScheduledDose, $Out>
    implements ScheduledDoseCopyWith<$R, ScheduledDose, $Out> {
  _ScheduledDoseCopyWithImpl(super.value, super.then, super.then2);

  @override
  late final ClassMapperBase<ScheduledDose> $mapper =
      ScheduledDoseMapper.ensureInitialized();
  @override
  $R call({TimeOfDay? time, Decimal? dose}) => $apply(
        FieldCopyWithData({
          if (time != null) #time: time,
          if (dose != null) #dose: dose,
        }),
      );
  @override
  ScheduledDose $make(CopyWithData data) => ScheduledDose(
        time: data.get(#time, or: $value.time),
        dose: data.get(#dose, or: $value.dose),
      );

  @override
  ScheduledDoseCopyWith<$R2, ScheduledDose, $Out2> $chain<$R2, $Out2>(
    Then<$Out2, $R2> t,
  ) =>
      _ScheduledDoseCopyWithImpl<$R2, $Out2>($value, $cast, t);
}
