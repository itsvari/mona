package com.deliacheminot.mona.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.onehandedgesture.*
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MainActivity : ComponentActivity() {
    private var launchMedication by mutableStateOf<String?>(null)
    private var launchMinute by mutableIntStateOf(-1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readLaunch(intent)
        setContent { MonaWatch(launchMedication, launchMinute) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readLaunch(intent) }
    private fun readLaunch(intent: Intent) {
        launchMedication = intent.getStringExtra("medicationId")
        launchMinute = intent.getIntExtra("minute", -1)
    }
    override fun onResume() {
        super.onResume()
        WatchSyncWorker.enqueue(this, requestRefresh = true)
        ReminderScheduler(this).reschedule()
    }
}

@Composable
internal fun MonaWatch(initialMedication: String? = null, initialMinute: Int = -1) {
    val context = LocalContext.current
    val store = remember { WatchStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val nav = rememberSwipeDismissableNavController()
    var draft by remember { mutableStateOf(MedicationDraft.restore(store.draft())) }
    fun updateDraft(value: MedicationDraft) { draft = value; store.saveDraft(value.payload().toString()) }
    val colors = dynamicColorScheme(context) ?: ColorScheme(
        primary = Color(0xFFD7E8B0), onPrimary = Color(0xFF233019),
        primaryContainer = Color(0xFF37442C), onPrimaryContainer = Color(0xFFE2F4BA),
        secondary = Color(0xFFE7BED0), background = Color(0xFF0D110D), onBackground = Color(0xFFF2F3E8))
    MaterialTheme(colorScheme = colors) {
        AppScaffold(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            LaunchedEffect(initialMedication, state.snapshot != null) {
                if (initialMedication != null && state.snapshot != null) nav.navigate("dose/$initialMedication/$initialMinute")
            }
            SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                composable("home") { HomeScreen(state, onMedication = { id, minute -> nav.navigate("dose/$id/$minute") },
                    onAdd = { nav.navigate("add") }, onHistory = { nav.navigate("history") }, onSettings = { nav.navigate("settings") }) }
                composable("dose/{id}/{minute}") { entry ->
                    val m = state.snapshot?.schedules?.find { it.id == entry.arguments?.getString("id") }
                    if (m == null) InfoScreen("Medication unavailable", "Sync with Mona to refresh your medications.")
                    else DoseScreen(m, entry.arguments?.getString("minute")?.toIntOrNull()?.takeIf { it >= 0 }, state, store) { nav.popBackStack() }
                }
                composable("history") { HistoryScreen(state, store) }
                composable("settings") { SettingsScreen(state, store) }
                composable("add") { AddMedicationScreen(draft, ::updateDraft, state, store, onEdit = { nav.navigate("edit/$it") }) { nav.popBackStack() } }
                composable("edit/{field}") { entry ->
                    DraftFieldScreen(entry.arguments?.getString("field") ?: "", draft, ::updateDraft, state) { nav.popBackStack() }
                }
            }
        }
    }
}

@Composable
internal fun ListScreen(title: String, subtitle: String? = null, edgeLabel: String? = null,
    edgeEnabled: Boolean = true, onEdge: () -> Unit = {}, content: TransformingLazyColumnScope.() -> Unit) {
    val listState = key(title) { rememberTransformingLazyColumnState() }
    val body: @Composable BoxScope.(PaddingValues) -> Unit = { padding ->
        TransformingLazyColumn(state = listState, contentPadding = padding,
            modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            } }
            content()
        }
    }
    if (edgeLabel == null) ScreenScaffold(scrollState = listState, content = body)
    else ScreenScaffold(scrollState = listState, edgeButton = {
        GestureEdgeButton(edgeLabel, edgeEnabled, onEdge)
    }, content = body)
}

internal fun TransformingLazyColumnScope.note(text: String) {
    item { Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center) }
}

internal fun TransformingLazyColumnScope.action(label: String, detail: String? = null, enabled: Boolean = true,
    primary: Boolean = false, onClick: () -> Unit) {
    item {
        val spec = rememberTransformationSpec()
        Button(onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec),
            colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) {
            Column { Text(label, style = MaterialTheme.typography.labelLarge)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun GestureEdgeButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val gesture = rememberOneHandedGestureConfiguration(OneHandedGestureAction.Primary, gestureId = "mona.primary.$label")
    val indicator = remember { OneHandedGestureClickIndicatorState() }
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    val view = LocalView.current
    val gestureModifier = if (enabled && visible) Modifier.oneHandedGesture(gesture, onGestureLabel = label,
        onGestureAvailable = { scope.launch { indicator.showIndicator() } }, onGesture = { onClick() }) else Modifier
    EdgeButton(onClick = onClick, enabled = enabled, modifier = gestureModifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.height > 24 && bounds.top >= 0 && bounds.bottom <= view.height + 1
    }) {
        OneHandedGestureClickIndicator(gesture, indicator) { Text(label) }
    }
}

@Composable
private fun HomeScreen(state: WatchState, onMedication: (String, Int) -> Unit, onAdd: () -> Unit,
    onHistory: () -> Unit, onSettings: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    val snapshot = state.snapshot
    if (snapshot == null) {
        val context = LocalContext.current
        ListScreen("Mona", "Your medication companion", edgeLabel = "Try sync", onEdge = {
            WatchSyncWorker.enqueue(context, requestRefresh = true)
        }) {
            note("Open Mona on your paired Android phone to connect. Install the matching phone and watch builds.")
            state.error?.let { note(it) }
        }
        return
    }
    val day = SchedulePlanner.logicalDate(now, ZoneId.systemDefault(), snapshot.logicalDayStartMinutes)
    val history = state.history()
    ListScreen("Today", day.format(DateTimeFormatter.ofPattern("EEE, d MMM")), edgeLabel = "Add", onEdge = onAdd) {
        if (state.pending.isNotEmpty()) action("${state.pending.size} waiting to sync", "Saved on this watch", onClick = onHistory)
        if (state.actions.any { it.status == "rejected" }) action("Action needs review", "Open your activity", onClick = onHistory)
        if (snapshot.schedules.isEmpty()) note("Add your first medication here or in Mona on your phone.")
        for (medication in snapshot.schedules) {
            val occurrences = SchedulePlanner.occurrences(medication, history, now, ZoneId.systemDefault(), snapshot.logicalDayStartMinutes, 3662, 2)
            val today = occurrences.filter { it.date == day }
            val next = today.firstOrNull { !SchedulePlanner.taken(it, history) }
                ?: occurrences.firstOrNull { !SchedulePlanner.taken(it, history) }
            val takenToday = today.isNotEmpty() && today.all { SchedulePlanner.taken(it, history) }
            val detail = when {
                medication.recurrence.type == "asNeeded" -> "${medication.dose.display()} ${medication.unit} · As needed"
                takenToday -> "Logged today"
                next != null -> "${next.dose.display()} ${medication.unit} · ${if (next.date < day) "Overdue" else formatDue(next, day)}"
                else -> "${medication.dose.display()} ${medication.unit}"
            }
            action(medication.name, detail, primary = !takenToday && next?.date?.let { it <= day } == true) {
                onMedication(medication.id, (if (medication.hasSeparateDoses) next?.minute ?: today.firstOrNull()?.minute else null) ?: -1)
            }
        }
        action("Activity", "Dose history and sync status", onClick = onHistory)
        action("Reminders & sync", if (!state.remindersEnabled) "Watch reminders off" else null, onClick = onSettings)
        note("Updated ${snapshot.generatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}")
        state.error?.let { note(it) }
    }
}

private fun formatDue(o: Occurrence, today: LocalDate): String {
    val time = o.at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return if (o.date == today) time else "${o.date.format(DateTimeFormatter.ofPattern("d MMM"))}, $time"
}

@Composable
private fun DoseScreen(medication: Medication, initialMinute: Int?, state: WatchState, store: WatchStore, onDone: () -> Unit) {
    var minute by rememberSaveable(medication.id) { mutableStateOf(initialMinute ?: medication.recurrence.times.firstOrNull()) }
    var dose by rememberSaveable(medication.id) { mutableStateOf(medication.doseAt(minute).display()) }
    var at by rememberSaveable(medication.id) { mutableLongStateOf(System.currentTimeMillis()) }
    var supplyId by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by rememberSaveable { mutableStateOf(false) }
    val input = rememberTextInput { field, value ->
        when (field) { "dose" -> { dose = normalizeNumber(value); editor = null }; "notes" -> notes = value }
    }
    val picked = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val saveDose = {
        if (!saved) error = runCatching {
            store.recordDose(medication, dose.toBigDecimal(), Instant.ofEpochMilli(at),
                if (medication.hasSeparateDoses) minute else null, supplyId, notes)
            saved = true
        }.exceptionOrNull()?.message
    }
    when (editor) {
        "amount" -> AmountPicker(dose, medication.unit, onTyped = { input("dose", "Dose in ${medication.unit}") }, onDone = { dose = it; editor = null })
        "time" -> TimePicker(initialTime = picked.toLocalTime(), onTimePicked = { at = picked.with(it).toInstant().toEpochMilli(); editor = null })
        "date" -> DatePicker(initialDate = picked.toLocalDate(), maxValidDate = LocalDate.now(), onDatePicked = {
            at = it.atTime(picked.toLocalTime()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); editor = null })
        "supply" -> ListScreen("Inventory") {
            action("No inventory", "Record the dose only") { supplyId = null; editor = null }
            for (supply in state.snapshot?.supplies.orEmpty().filter { it.matches(medication) }) {
                action(supply.name, "${supply.remainingDose.display()} ${medication.unit} remaining") { supplyId = supply.id; editor = null }
            }
        }
        "slot" -> ListScreen("Scheduled dose") {
            for (time in medication.recurrence.times) action(formatMinute(time), "${medication.doseAt(time).display()} ${medication.unit}") {
                minute = time; dose = medication.doseAt(time).display(); editor = null
            }
        }
        else -> ListScreen(medication.name, "Review your dose", edgeLabel = "Log dose", edgeEnabled = !saved, onEdge = saveDose) {
            item { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dose, style = MaterialTheme.typography.displayMedium)
                Text(medication.unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            } }
            action("Log dose", enabled = !saved, primary = true, onClick = saveDose)
            if (medication.hasSeparateDoses) action("Scheduled time", minute?.let(::formatMinute) ?: "Choose a time") { editor = "slot" }
            action("Dose amount", "$dose ${medication.unit}") { editor = "amount" }
            action("Taken at", picked.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))) { editor = "time" }
            action("Date", picked.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) { editor = "date" }
            action("Inventory", state.snapshot?.supplies?.find { it.id == supplyId }?.name ?: "No inventory deduction") { editor = "supply" }
            action("Note", notes.takeIf { it.isNotEmpty() } ?: "Optional · voice or keyboard") { input("notes", "Dose note") }
            error?.let { note(it) }
            note("Saved on your watch first, then confirmed by Mona.")
        }
    }
    androidx.activity.compose.BackHandler(editor != null) { editor = null }
    ConfirmationDialog(visible = saved, onDismissRequest = onDone, text = { Text("Saved on watch\nWaiting for Mona") }) {
        Text("✓", style = MaterialTheme.typography.displayMedium)
    }
}

@Composable
internal fun AmountPicker(initial: String, unit: String, onTyped: () -> Unit, onDone: (String) -> Unit) {
    val initialIndex = (initial.toBigDecimalOrNull() ?: BigDecimal.ONE).multiply(BigDecimal(100)).toInt().coerceIn(1, 100000) - 1
    val picker = rememberPickerState(100000, initialIndex, shouldRepeatOptions = false)
    fun selected() = BigDecimal(picker.selectedOptionIndex + 1).movePointLeft(2).display()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 30.dp, bottom = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Amount · $unit", style = MaterialTheme.typography.labelMedium)
            Picker(state = picker, contentDescription = { "${selected()} $unit" }, modifier = Modifier.weight(1f).fillMaxWidth()) { i ->
                Text(BigDecimal(i + 1).movePointLeft(2).display(), style = MaterialTheme.typography.displaySmall)
            }
            Button(onClick = onTyped, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(48.dp),
                colors = ButtonDefaults.filledTonalButtonColors()) {
                Text("Type or speak", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, maxLines = 1)
            }
        }
        EdgeButton(onClick = { onDone(selected()) }, modifier = Modifier.align(Alignment.BottomCenter)) { Text("Set") }
    }
}

@Composable
private fun HistoryScreen(state: WatchState, store: WatchStore) {
    ListScreen("Activity") {
        for (action in state.actions) {
            val name = when (action.kind) {
                "createMedication" -> action.payload.optString("name", "New medication")
                else -> state.snapshot?.schedules?.find { it.id == action.payload.optString("scheduleId") }?.name ?: "Dose"
            }
            action(name, when (action.status) { "pending" -> "Saved on watch · waiting for Mona"
                "applied" -> "Confirmed in Mona"; else -> action.message ?: "Needs review" },
                enabled = action.status != "pending" && !action.awaitingSnapshot) { store.dismissResult(action.id) }
        }
        val history = state.snapshot?.history.orEmpty().sortedByDescending { it.at }.take(40)
        for (intake in history) {
            val name = state.snapshot?.schedules?.find { it.id == intake.scheduleId }?.name ?: "Medication"
            note("$name · ${intake.dose.display()} ${intake.unit}\n${intake.at.atZone(ZoneId.of(intake.zoneId)).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}")
        }
        if (history.isEmpty() && state.actions.isEmpty()) note("Your logged doses will appear here.")
    }
}

@Composable
private fun SettingsScreen(state: WatchState, store: WatchStore) {
    val context = LocalContext.current
    val reminders = remember { ReminderScheduler(context) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++; reminders.reschedule() }
    ListScreen("Reminders & sync", edgeLabel = "Sync now", onEdge = { WatchSyncWorker.enqueue(context, requestRefresh = true) }) {
        action("Watch reminders", if (state.remindersEnabled) "On" else "Off") { store.setReminders(!state.remindersEnabled) }
        if (state.snapshot?.notificationsEnabled != true) note("Enable medication notifications in Mona on your phone to receive reminders here.")
        if (permissionRevision >= 0 && !reminders.hasPermission()) action("Allow notifications", "Required for medication reminders") {
            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!reminders.isExact()) action("Precise reminder times", "Currently reminders may arrive late") {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
        note("Reminders and saved doses work offline after the first sync. Phone changes arrive when devices reconnect.")
        state.error?.let { note(it) }
        state.snapshot?.let { note("Last synced ${it.generatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}") }
    }
}

@Composable
private fun InfoScreen(title: String, message: String) { ListScreen(title) { note(message) } }

@Composable
internal fun rememberTextInput(onResult: (String, String) -> Unit): (String, String) -> Unit {
    var activeField by rememberSaveable { mutableStateOf("") }
    val currentResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("value")?.toString() }
            ?.let { currentResult(activeField, it.take(200)) }
    }
    return { field, label ->
        activeField = field
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("value").setLabel(label).build()))
        RemoteInputIntentHelper.putTitleExtra(intent, label)
        launcher.launch(intent)
    }
}

internal fun formatMinute(minute: Int): String = LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
internal fun normalizeNumber(value: String): String = value.trim().replace(',', '.')
internal fun readable(value: String): String = value.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
