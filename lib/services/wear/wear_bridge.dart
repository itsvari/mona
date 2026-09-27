import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:mona/controllers/notification_planner.dart';
import 'package:mona/controllers/notification_scheduler.dart';
import 'package:mona/data/model/date.dart';
import 'package:mona/data/providers/medication_intake_provider.dart';
import 'package:mona/data/providers/medication_schedule_provider.dart';
import 'package:mona/i18n/build_context_extensions.dart';
import 'package:mona/i18n/locale_provider.dart';
import 'package:mona/services/db/app_database.dart';
import 'package:mona/services/home_widget_service.dart';
import 'package:mona/services/notification_service.dart';
import 'package:mona/services/preferences_service.dart';
import 'package:mona/services/wear/wear_sync_service.dart';
import 'package:timezone/data/latest_all.dart' as tzdata;

class WearBridge {
  static const channel = MethodChannel('mona/wear');
  static bool _refreshing = false;

  static void listen(Future<void> Function() reload) {
    if (!Platform.isAndroid) return;
    channel.setMethodCallHandler((call) async {
      if (call.method != 'refresh') return;
      _refreshing = true;
      try {
        await reload();
      } finally {
        _refreshing = false;
      }
    });
  }

  static void dispose() {
    if (Platform.isAndroid) channel.setMethodCallHandler(null);
  }

  static void requestSync({bool dataChanged = false}) {
    if (!Platform.isAndroid || (_refreshing && !dataChanged)) return;
    unawaited(_requestSync());
  }

  static Future<void> _requestSync() async {
    try {
      await channel.invokeMethod<void>('requestSync');
    } on MissingPluginException {
      // The bridge is Android-only; host-side widget tests have no plugin.
    } on PlatformException {
      // Native persistent work retries when scheduling becomes available.
    }
  }

  static Future<void> beginImport() async {
    if (!Platform.isAndroid) return;
    await channel.invokeMethod<void>('beginImport');
  }

  static Future<void> endImport() async {
    if (!Platform.isAndroid) return;
    await channel.invokeMethod<void>('endImport');
  }
}

Future<void> runWearBackground() async {
  WidgetsFlutterBinding.ensureInitialized();
  tzdata.initializeTimeZones();
  final appDb = AppDatabase.getInstance(background: true);
  try {
    await initializeDateFormatting();
    final preferences = await PreferencesService.init(reload: true);
    logicalDayStartMinutes = preferences.logicalDayStartMinutesRaw;
    final db = await appDb.database;
    final service = WearSyncService(db, preferences);
    final commands =
        await WearBridge.channel.invokeListMethod<dynamic>('loadCommands') ??
            [];
    final results = <Map<String, String>>[];
    final processed = <String>[];
    for (final entry in commands) {
      final item = Map<String, dynamic>.from(entry as Map);
      final path = item['path'] as String;
      final command =
          Map<String, dynamic>.from(jsonDecode(item['json'] as String));
      final receipt = await service.apply(command);
      results.add({
        'path':
            '/mona/v1/results/${command['installationId']}/${command['id']}',
        'json': jsonEncode(receipt)
      });
      processed.add(path);
    }
    final zone = (await FlutterTimezone.getLocalTimezone()).identifier;
    final snapshot = await service.snapshot(zone);
    for (final result in results) {
      final receipt = Map<String, dynamic>.from(jsonDecode(result['json']!));
      receipt['revision'] = snapshot['revision'];
      result['json'] = jsonEncode(receipt);
    }
    final notificationService = NotificationService();
    await notificationService.initialize();
    final intakes = MedicationIntakeProvider();
    final schedules = MedicationScheduleProvider(preferences: preferences);
    await Future.wait([intakes.ready, schedules.ready]);
    try {
      final locale = LocaleProvider(preferences);
      try {
        await NotificationScheduler(
                NotificationPlanner(intakes, schedules), preferences)
            .regenerateAll(locale.locale.intlLanguageTag);
        await HomeWidgetService().sync(intakes, locale);
      } finally {
        locale.dispose();
      }
    } finally {
      intakes.dispose();
      schedules.dispose();
      preferences.dispose();
    }
    await appDb.close();
    await WearBridge.channel.invokeMethod<void>('complete', {
      'snapshot': jsonEncode(snapshot),
      'results': results,
      'processedPaths': processed,
    });
  } catch (error, stackTrace) {
    if (kDebugMode) {
      debugPrint('Wear worker failed: ${error.runtimeType}\n$stackTrace');
    }
    await appDb.close();
    await WearBridge.channel.invokeMethod<void>(
        'failed', 'Mona could not finish syncing. It will retry.');
  }
}
