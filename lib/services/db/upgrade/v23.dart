import 'package:mona/services/db/db_tables.dart';
import 'package:mona/services/db/upgrade/db_upgrade.dart';
import 'package:sqflite/sqlite_api.dart';

class DbUpgradeV23 implements DbUpgrade {
  @override
  Future<void> upgrade(Database db, int oldVersion, int newVersion) async {
    await db.execute(createWearStateTable);
    await db.execute(createWearCommandsTable);
  }
}
