import 'package:mona/services/db/app_database.dart';
import 'package:mona/services/wear/wear_bridge.dart';
import 'package:sqflite/sqflite.dart';

class Repository<T> {
  final DatabaseExecutor? _providedDb;
  Future<DatabaseExecutor> get _dbFuture async =>
      _providedDb ?? await AppDatabase.getInstance().database;
  final String tableName;
  final Map<String, Object?> Function(T) toMap;
  final T Function(Map<String, Object?>) fromMap;

  Repository({
    DatabaseExecutor? db,
    required this.tableName,
    required this.toMap,
    required this.fromMap,
  }) : _providedDb = db;

  Future<int> insert(T element) async {
    final db = await _dbFuture;
    final id = await db.insert(
      tableName,
      toMap(element),
      conflictAlgorithm: ConflictAlgorithm.abort,
    );
    _changed();
    return id;
  }

  void _changed() {
    if (_providedDb is! Transaction &&
        const {'medication_schedules', 'medication_intakes', 'supply_items'}
            .contains(tableName)) {
      WearBridge.requestSync(dataChanged: true);
    }
  }

  Future<List<T>> getAll() async {
    final db = await _dbFuture;
    final result = await db.query(tableName);
    return result.map(fromMap).toList();
  }

  Future<void> update(T element, int id) async {
    final db = await _dbFuture;
    await db.update(
      tableName,
      toMap(element),
      where: 'id = ?',
      whereArgs: [id],
    );
    _changed();
  }

  Future<bool> updateFromCurrent(
      int id, T? Function(T current) transform) async {
    final db = await _dbFuture;
    Future<bool> update(DatabaseExecutor executor) async {
      final rows =
          await executor.query(tableName, where: 'id = ?', whereArgs: [id]);
      if (rows.isEmpty) return false;
      final next = transform(fromMap(rows.single));
      if (next == null) return false;
      await executor
          .update(tableName, toMap(next), where: 'id = ?', whereArgs: [id]);
      return true;
    }

    final changed =
        db is Database ? await db.transaction(update) : await update(db);
    if (changed) _changed();
    return changed;
  }

  Future<void> delete(int id) async {
    final db = await _dbFuture;
    await db.delete(
      tableName,
      where: 'id = ?',
      whereArgs: [id],
    );
    _changed();
  }
}
