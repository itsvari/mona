import 'package:mona/services/db/upgrade/db_upgrade.dart';
import 'package:sqflite/sqlite_api.dart';

class DbUpgradeV22 implements DbUpgrade {
  @override
  Future<void> upgrade(Database db, int oldVersion, int newVersion) async {
    await db
        .execute('ALTER TABLE medication_schedules ADD COLUMN unitDose TEXT');
    await db.execute(
      "ALTER TABLE medication_schedules ADD COLUMN doseOverrides TEXT NOT NULL DEFAULT '[]'",
    );
    await db.execute('ALTER TABLE medication_intakes ADD COLUMN unitDose TEXT');
    await db
        .execute('ALTER TABLE medication_intakes ADD COLUMN deliveryForm TEXT');
  }
}
