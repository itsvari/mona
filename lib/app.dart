import 'dart:io';

import 'package:clock/clock.dart';
import 'package:dynamic_system_colors/dynamic_system_colors.dart';
import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:mona/controllers/notification_planner.dart';
import 'package:mona/controllers/notification_scheduler.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/medication_schedule_provider.dart';
import 'package:mona/data/providers/supply_item_provider.dart';
import 'package:mona/i18n/build_context_extensions.dart';
import 'package:mona/i18n/locale_provider.dart';
import 'package:mona/i18n/tok_localizations.dart';
import 'package:mona/services/home_widget_service.dart';
import 'package:mona/services/notification_service.dart';
import 'package:mona/services/preferences_service.dart';
import 'package:mona/services/wear/wear_bridge.dart';
import 'package:mona/theme/app_theme_controller.dart';
import 'package:mona/theme/material_ui_theme.dart';
import 'package:provider/provider.dart';

import 'ui/views/main_page.dart';

class MonaApp extends StatefulWidget {
  const MonaApp({super.key});

  @override
  State<MonaApp> createState() => _MonaAppState();
}

class _MonaAppState extends State<MonaApp> with WidgetsBindingObserver {
  String? _lastTimeZone;
  late MedicationScheduleProvider _medicationScheduleProvider;
  late MedicationIntakeProvider _medicationIntakeProvider;
  late SupplyItemProvider _supplyItemProvider;
  late PreferencesService _preferencesService;
  late LocaleProvider _localeProvider;
  late NotificationScheduler _notificationScheduler;
  final HomeWidgetService _homeWidgetService = HomeWidgetService();
  bool _initialized = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _lastTimeZone = clock.now().timeZoneOffset.toString();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await NotificationService().initialize();
      if (!mounted) return;
      _medicationScheduleProvider = context.read<MedicationScheduleProvider>();
      _medicationIntakeProvider = context.read<MedicationIntakeProvider>();
      _supplyItemProvider = context.read<SupplyItemProvider>();
      _preferencesService = context.read<PreferencesService>();
      _localeProvider = context.read<LocaleProvider>();
      _notificationScheduler = NotificationScheduler(
        NotificationPlanner(
            _medicationIntakeProvider, _medicationScheduleProvider),
        _preferencesService,
      );
      _initialized = true;
      WearBridge.listen(() async {
        await Future.wait([
          _medicationScheduleProvider.fetchSchedules(),
          _medicationIntakeProvider.fetchIntakes(),
          _supplyItemProvider.fetchItems(),
        ]);
      });
      _supplyItemProvider.addListener(WearBridge.requestSync);

      _medicationScheduleProvider.addListener(_regenerateNotifications);
      _medicationIntakeProvider.addListener(_regenerateNotifications);
      _preferencesService.addListener(_preferencesChanged);
      _regenerateNotifications();

      _medicationIntakeProvider.addListener(_regenerateHomeWidget);
      _localeProvider.addListener(_regenerateHomeWidget);
      _regenerateHomeWidget();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    WearBridge.dispose();
    if (_initialized) {
      _supplyItemProvider.removeListener(WearBridge.requestSync);
      _medicationScheduleProvider.removeListener(_regenerateNotifications);
      _medicationIntakeProvider.removeListener(_regenerateNotifications);
      _preferencesService.removeListener(_preferencesChanged);
      _medicationIntakeProvider.removeListener(_regenerateHomeWidget);
      _localeProvider.removeListener(_regenerateHomeWidget);
    }
    super.dispose();
  }

  void _preferencesChanged() {
    if (Platform.isAndroid) {
      WearBridge.requestSync(dataChanged: true);
    } else {
      _regenerateNotifications();
    }
  }

  void _regenerateNotifications() {
    if (!_initialized || !mounted) return;

    if (Platform.isAndroid) {
      WearBridge.requestSync();
    } else {
      final locale = context.read<LocaleProvider>().locale;
      _notificationScheduler.regenerateAll(locale.intlLanguageTag);
    }
  }

  void _regenerateHomeWidget() {
    if (!_initialized || !mounted) return;

    _homeWidgetService.sync(
      _medicationIntakeProvider,
      _localeProvider,
    );
  }

  void _checkTimezoneChange() {
    final currentTimezone = clock.now().timeZoneOffset.toString();
    if (_lastTimeZone != currentTimezone) {
      _lastTimeZone = currentTimezone;
      _regenerateNotifications();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkTimezoneChange();
      WearBridge.requestSync();
    } else if (state == AppLifecycleState.paused) {
      _regenerateHomeWidget();
    }
  }

  @override
  Widget build(BuildContext context) {
    context.watch<AppThemeProvider>();
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        final themes = context.read<AppThemeProvider>().buildThemeData(
              systemLight: lightDynamic,
              systemDark: darkDynamic,
            );
        return MaterialApp(
          title: 'Mona',
          locale: context.watch<LocaleProvider>().locale,
          supportedLocales: context.watch<LocaleProvider>().supportedLocales,
          localizationsDelegates: const [
            TokMaterialLocalizationsDelegate(),
            TokCupertinoLocalizationsDelegate(),
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          theme: themes.theme,
          darkTheme: themes.darkTheme,
          themeMode: ThemeMode.system,
          builder: (context, child) => withMaterialUiTheme(
            scheme: Theme.of(context).colorScheme,
            child: child ?? const SizedBox.shrink(),
          ),
          home: const MainPage(),
        );
      },
    );
  }
}
