package com.deliacheminot.mona.wear

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun AddMedicationScreen(draft: MedicationDraft, update: (MedicationDraft) -> Unit,
    state: WatchState, store: WatchStore, onEdit: (String) -> Unit, onDone: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var review by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val input = rememberTextInput { field, value ->
        when (field) {
            "name" -> update(draft.copy(name = value))
            "unit" -> update(draft.copy(unit = value))
            "unitDose" -> update(draft.copy(unitDose = normalizeNumber(value)))
        }
    }
    val recurrence = when (draft.type) {
        "daily" -> "Every day"
        "intervalDays" -> "Every ${draft.intervalDays} days"
        "dynamicInterval" -> "${draft.intervalDays} days after last dose"
        "weekly" -> draft.weekdays.sorted().joinToString { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        "monthly" -> "Day ${draft.dayOfMonth}, every ${draft.intervalMonths} months"
        else -> "As needed"
    }
    ListScreen(if (review) "Review medication" else "Add medication", edgeLabel = if (review) "Save" else "Review",
        edgeEnabled = !saved && state.snapshot != null, onEdge = {
            val errors = draft.errors()
            if (errors.isNotEmpty()) { error = errors.first(); review = false }
            else if (!review) { review = true; error = null }
            else {
                error = runCatching {
                    store.enqueue("createMedication", draft.payload())
                    store.saveDraft(null)
                    saved = true
                }.exceptionOrNull()?.message
            }
        }) {
        if (review) {
            note("${draft.name}\n${draft.dose} ${draft.unit} · ${readable(draft.route)}")
            note("${readable(draft.moleculeName)}${draft.ester?.let { " · ${readable(it)}" } ?: ""}")
            note("$recurrence${if (draft.type != "asNeeded") "\n${draft.times.sorted().joinToString { formatMinute(it) }}" else ""}")
            note("Starts ${draft.startDate}")
            if (!draft.notify && draft.type != "asNeeded") note("No reminders for this medication")
            if (draft.unitDose.isNotBlank()) note("${draft.unitDose} ${draft.unit} per unit")
            action("Edit details") { review = false }
            note("This creates a medication schedule in Mona. Inventory is managed on your phone.")
        } else {
            action("Name", draft.name.ifBlank { "Voice or keyboard" }) { input("name", "Medication name") }
            action("Molecule", readable(draft.moleculeName)) { onEdit("molecule") }
            action("Dose", if (draft.dose.isBlank()) "Choose amount" else "${draft.dose} ${draft.unit}") { onEdit("dose") }
            action("Dose unit", draft.unit) { input("unit", "Dose unit, for example mg") }
            action("Route", readable(draft.route)) { onEdit("route") }
            if (draft.route == "injection" && draft.moleculeName.equals("estradiol", true)) {
                action("Ester", draft.ester?.let(::readable) ?: "Choose an ester") { onEdit("ester") }
            }
            action("Unit strength", draft.unitDose.ifBlank { "Optional, e.g. mg per pill" }) { input("unitDose", "Strength per unit, or leave blank") }
            action("Schedule", recurrence) { onEdit("recurrence") }
            when (draft.type) {
                "intervalDays", "dynamicInterval" -> action("Interval", "${draft.intervalDays} days") { onEdit("intervalDays") }
                "weekly" -> action("Weekdays", recurrence) { onEdit("weekdays") }
                "monthly" -> {
                    action("Day of month", draft.dayOfMonth.toString()) { onEdit("dayOfMonth") }
                    action("Repeat every", "${draft.intervalMonths} months") { onEdit("intervalMonths") }
                }
            }
            if (draft.type != "asNeeded") {
                action("Times", draft.times.sorted().joinToString { formatMinute(it) }.ifBlank { "Add a time" }) { onEdit("times") }
                action("Remind me", if (draft.notify) "On" else "Off") { update(draft.copy(notify = !draft.notify)) }
            }
            action("Start date", draft.startDate) { onEdit("startDate") }
        }
        error?.let { note(it) }
        if (state.snapshot?.notificationsEnabled != true && draft.notify) note("Medication notifications are off in Mona. Enable them on your phone for reminders.")
    }
    androidx.activity.compose.BackHandler(review) { review = false }
    ConfirmationDialog(visible = saved, onDismissRequest = { update(MedicationDraft()); store.saveDraft(null); onDone() },
        text = { Text("Saved on watch\nWaiting for Mona") }) { Text("✓", style = MaterialTheme.typography.displayMedium) }
}

@Composable
internal fun DraftFieldScreen(field: String, draft: MedicationDraft, update: (MedicationDraft) -> Unit,
    state: WatchState, onDone: () -> Unit) {
    val input = rememberTextInput { key, value ->
        when (key) {
            "molecule" -> { update(draft.copy(moleculeName = value.lowercase(), name = draft.name.ifBlank { value })); onDone() }
            "dose" -> { update(draft.copy(dose = normalizeNumber(value))); onDone() }
        }
    }
    when (field) {
        "dose" -> AmountPicker(draft.dose, draft.unit, onTyped = { input("dose", "Dose in ${draft.unit}") },
            onDone = { update(draft.copy(dose = it)); onDone() })
        "startDate" -> DatePicker(initialDate = LocalDate.parse(draft.startDate), onDatePicked = {
            update(draft.copy(startDate = it.toString())); onDone() })
        "molecule" -> ListScreen("Molecule") {
            for (molecule in state.snapshot?.molecules.orEmpty()) action(readable(molecule.name), molecule.unit) {
                update(draft.copy(moleculeName = molecule.name, unit = molecule.unit,
                    name = draft.name.ifBlank { readable(molecule.name) }, ester = null)); onDone()
            }
            action("Custom molecule", "Voice or keyboard") { input("molecule", "Molecule name") }
        }
        "route" -> ListScreen("Route") {
            for (route in listOf("oral", "sublingual", "injection", "patch", "gel", "implant", "suppository", "transdermalSpray", "transdermalDrops")) {
                action(readable(route)) { update(draft.copy(route = route, ester = null)); onDone() }
            }
        }
        "ester" -> ListScreen("Estradiol ester") {
            for (ester in listOf("enanthate", "valerate", "cypionate", "undecylate", "benzoate", "cypionateSuspension")) {
                action(readable(ester)) { update(draft.copy(ester = ester)); onDone() }
            }
        }
        "recurrence" -> ListScreen("Repeat") {
            for ((kind, label) in listOf("daily" to "Every day", "intervalDays" to "Every N days",
                "dynamicInterval" to "After last dose", "weekly" to "Weekly", "monthly" to "Monthly", "asNeeded" to "As needed")) {
                action(label) { update(draft.copy(type = kind)); onDone() }
            }
        }
        "weekdays" -> ListScreen("Weekdays", "Tap to select", edgeLabel = "Done", edgeEnabled = draft.weekdays.isNotEmpty(), onEdge = onDone) {
            for (day in 1..7) action(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                if (day in draft.weekdays) "Selected" else null, primary = day in draft.weekdays) {
                update(draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day))
            }
        }
        "times" -> {
            var editing by rememberSaveable { mutableStateOf(false) }
            if (editing) TimePicker(initialTime = LocalTime.of(9, 0), onTimePicked = {
                update(draft.copy(times = (draft.times + (it.hour * 60 + it.minute)).distinct().sorted())); editing = false
            }) else ListScreen("Reminder times", "Tap a time to remove it", edgeLabel = "Done", edgeEnabled = draft.times.isNotEmpty(), onEdge = onDone) {
                for (minute in draft.times) action(formatMinute(minute), "Remove time") { update(draft.copy(times = draft.times - minute)) }
                action("Add time", enabled = draft.times.size < 12) { editing = true }
            }
            androidx.activity.compose.BackHandler(editing) { editing = false }
        }
        "intervalDays", "dayOfMonth", "intervalMonths" -> {
            val max = when (field) { "intervalDays" -> 3650; "dayOfMonth" -> 28; else -> 120 }
            val value = when (field) { "intervalDays" -> draft.intervalDays; "dayOfMonth" -> draft.dayOfMonth; else -> draft.intervalMonths }
            val picker = rememberPickerState(max, value - 1, shouldRepeatOptions = false)
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(top = 33.dp, bottom = 45.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (field) { "intervalDays" -> "Days between doses"; "dayOfMonth" -> "Day of month"; else -> "Months between doses" }, style = MaterialTheme.typography.labelMedium)
                    Picker(state = picker, contentDescription = { "${picker.selectedOptionIndex + 1}" }, modifier = Modifier.weight(1f)) {
                        Text("${it + 1}", style = MaterialTheme.typography.displayMedium)
                    }
                }
                EdgeButton(onClick = {
                    val n = picker.selectedOptionIndex + 1
                    update(when (field) { "intervalDays" -> draft.copy(intervalDays = n); "dayOfMonth" -> draft.copy(dayOfMonth = n); else -> draft.copy(intervalMonths = n) })
                    onDone()
                }, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Done") }
            }
        }
    }
}
