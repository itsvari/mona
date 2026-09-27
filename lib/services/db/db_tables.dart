const String createSupplyItemsTable = '''
    CREATE TABLE supply_items(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      type TEXT NOT NULL,
      name TEXT NOT NULL,
      totalDose TEXT,
      usedDose TEXT,
      dosePerUnit TEXT,
      molecule TEXT,
      administrationRoute TEXT,
      ester TEXT,
      amount INTEGER,
      genericSupplyType TEXT,
      deliveryForm TEXT,
      dosingBasis TEXT
    )
    ''';

const String createMedicationIntakesTable = '''
    CREATE TABLE medication_intakes(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      scheduledTime TEXT,
      takenDateTime TEXT,
      takenTimeZone TEXT,
      takenDose TEXT NOT NULL,
      unitDose TEXT,
      deliveryForm TEXT,
      wastedAmount TEXT,
      deadSpace TEXT,
      scheduleId INTEGER,
      molecule TEXT NOT NULL,
      administrationRoute TEXT NOT NULL,
      ester TEXT,
      medicationSupplyItemId INTEGER,
      genericSupplyItemIds TEXT NOT NULL,
      notes TEXT,
      placements TEXT NOT NULL,
      dosingBasis TEXT NOT NULL,
      FOREIGN KEY (medicationSupplyItemId) REFERENCES supply_items(id) ON DELETE SET NULL,
      FOREIGN KEY (scheduleId) REFERENCES medication_schedules(id) ON DELETE SET NULL
    )
    ''';

const String createMedicationSchedulesTable = '''
    CREATE TABLE medication_schedules(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      dose TEXT NOT NULL,
      unitDose TEXT,
      doseOverrides TEXT NOT NULL DEFAULT '[]',
      startDate TEXT NOT NULL,
      molecule TEXT NOT NULL,
      administrationRoute TEXT NOT NULL,
      ester TEXT,
      scheduling TEXT NOT NULL,
      dosingBasis TEXT NOT NULL
    )
    ''';

const String createBloodTestsTable = '''
    CREATE TABLE blood_tests(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      dateTime TEXT NOT NULL,
      timeZone TEXT NOT NULL,
      estradiolLevels TEXT,
      testosteroneLevels TEXT,
      notes TEXT
    )
    ''';

const String createWearStateTable = '''
    CREATE TABLE wear_state(
      singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
      datasetId TEXT NOT NULL,
      revision INTEGER NOT NULL DEFAULT 0
    )
    ''';

const String createWearCommandsTable = '''
    CREATE TABLE wear_commands(
      datasetId TEXT NOT NULL,
      commandId TEXT NOT NULL,
      digest TEXT NOT NULL,
      receipt TEXT NOT NULL,
      PRIMARY KEY(datasetId, commandId)
    )
    ''';
