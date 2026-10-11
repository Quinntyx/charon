package dev.quinntyx.charon.recurrence

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class RecurrenceUiState(
    val payments: List<RecurringPayment> = emptyList(),
    val due: List<ScheduledOccurrence> = emptyList(),
    val upcoming: List<ScheduledOccurrence> = emptyList(),
    val logged: List<LoggedOccurrence> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val error: String? = null,
)

class RecurrenceViewModel(private val service: RecurrenceService) : ViewModel() {
    private val _state = MutableStateFlow(RecurrenceUiState(today = service.today()))
    val state: StateFlow<RecurrenceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                service.repository.observePayments(),
                service.repository.observeLoggedOccurrences(),
            ) { payments, logs -> buildState(payments, logs, _state.value.error) }
                .collect { _state.value = it }
        }
    }

    fun create(request: CreateRecurringPayment) = act { service.create(request) }

    fun pause(id: String) = act { service.pause(id) }

    fun resume(id: String) = act { service.resume(id) }

    fun cancel(id: String) = act { service.cancel(id) }

    fun skip(id: String, dueDate: LocalDate) = act { service.skip(id, dueDate) }

    fun log(id: String, dueDate: LocalDate) = act { service.logManually(id, dueDate) }

    fun changeAmount(id: String, minorUnits: Long, effectiveFrom: LocalDate) = act {
        service.changeAmount(id, minorUnits, effectiveFrom)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _state.value = _state.value.copy(error = null) }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Action failed") }
        }
    }

    private fun buildState(
        payments: List<RecurringPayment>,
        logs: List<LoggedOccurrence>,
        error: String?,
    ): RecurrenceUiState {
        val today = service.today()
        val loggedKeys = logs.mapTo(mutableSetOf()) { it.occurrence.key }
        val due = payments.flatMap { payment ->
            service.scheduledOccurrences(payment, payment.startsOn, today)
        }.filterNot { it.key in loggedKeys }
            .sortedWith(compareBy<ScheduledOccurrence> { it.key.dueDate }.thenBy { it.title })
        val upcoming = payments.flatMap { payment ->
            service.scheduledOccurrences(payment, today.plusDays(1), today.plusYears(1))
        }.filterNot { it.key in loggedKeys }
            .sortedWith(compareBy<ScheduledOccurrence> { it.key.dueDate }.thenBy { it.title })
        return RecurrenceUiState(
            payments = payments,
            due = due,
            upcoming = upcoming,
            logged = logs.sortedByDescending { it.occurrence.key.dueDate },
            today = today,
            error = error,
        )
    }
}

class RecurrenceViewModelFactory(private val service: RecurrenceService) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(RecurrenceViewModel::class.java))
        return RecurrenceViewModel(service) as T
    }
}

@Composable
fun RecurrenceManagementRoute(
    service: RecurrenceService,
    modifier: Modifier = Modifier,
) {
    val factory = remember(service) { RecurrenceViewModelFactory(service) }
    val viewModel: RecurrenceViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    RecurrenceManagementScreen(
        state = state,
        onCreate = viewModel::create,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onCancel = viewModel::cancel,
        onSkip = viewModel::skip,
        onLog = viewModel::log,
        onChangeAmount = viewModel::changeAmount,
        onDismissError = viewModel::clearError,
        modifier = modifier,
    )
}

private enum class RuleChoice { WEEKLY, MONTHLY, YEARLY, CUSTOM }

@Composable
fun RecurrenceManagementScreen(
    state: RecurrenceUiState,
    onCreate: (CreateRecurringPayment) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSkip: (String, LocalDate) -> Unit,
    onLog: (String, LocalDate) -> Unit,
    onChangeAmount: (String, Long, LocalDate) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var folderId by remember { mutableStateOf("") }
    var startDate by remember(state.today) { mutableStateOf(state.today.toString()) }
    var interval by remember { mutableStateOf("1") }
    var ruleChoice by remember { mutableStateOf(RuleChoice.MONTHLY) }
    var policy by remember { mutableStateOf(LoggingPolicy.MANUAL_CONFIRMATION) }

    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Recurring payments", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Upcoming items are plans, not recorded spending. Automatic logging uses " +
                    "best-effort background catch-up; Android does not guarantee an exact run time.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.error?.let { message ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismissError) { Text("Dismiss") }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Add payment", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit) },
                        label = { Text("Amount (minor units)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = currency,
                            onValueChange = { currency = it.uppercase().take(3) },
                            label = { Text("Currency") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = folderId,
                            onValueChange = { folderId = it },
                            label = { Text("Folder ID") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                    }
                    OutlinedTextField(
                        value = startDate,
                        onValueChange = { startDate = it },
                        label = { Text("First due date (YYYY-MM-DD)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Frequency", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RuleChoice.entries.forEach { choice ->
                            FilterChip(
                                selected = ruleChoice == choice,
                                onClick = { ruleChoice = choice },
                                label = { Text(choice.name.lowercase().replaceFirstChar(Char::uppercase)) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter(Char::isDigit) },
                        label = {
                            Text(
                                when (ruleChoice) {
                                    RuleChoice.WEEKLY -> "Every N weeks"
                                    RuleChoice.MONTHLY -> "Every N months"
                                    RuleChoice.YEARLY -> "Every N years"
                                    RuleChoice.CUSTOM -> "Every N days"
                                },
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Logging policy", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = policy == LoggingPolicy.MANUAL_CONFIRMATION,
                            onClick = { policy = LoggingPolicy.MANUAL_CONFIRMATION },
                            label = { Text("Confirm manually") },
                        )
                        FilterChip(
                            selected = policy == LoggingPolicy.AUTOMATIC_CATCH_UP,
                            onClick = { policy = LoggingPolicy.AUTOMATIC_CATCH_UP },
                            label = { Text("Automatic catch-up") },
                        )
                    }
                    Button(
                        onClick = {
                            val parsedInterval = interval.toIntOrNull()
                            val parsedAmount = amount.toLongOrNull()
                            val parsedDate = runCatching { LocalDate.parse(startDate) }.getOrNull()
                            if (parsedInterval != null && parsedAmount != null && parsedDate != null) {
                                val rule = when (ruleChoice) {
                                    RuleChoice.WEEKLY -> RecurrenceRule.Weekly(parsedInterval)
                                    RuleChoice.MONTHLY -> RecurrenceRule.Monthly(parsedInterval)
                                    RuleChoice.YEARLY -> RecurrenceRule.Yearly(parsedInterval)
                                    RuleChoice.CUSTOM -> RecurrenceRule.CustomDays(parsedInterval)
                                }
                                onCreate(
                                    CreateRecurringPayment(
                                        title = title,
                                        amountMinorUnits = parsedAmount,
                                        currency = currency,
                                        folderId = folderId,
                                        startsOn = parsedDate,
                                        rule = rule,
                                        loggingPolicy = policy,
                                    ),
                                )
                            }
                        },
                        enabled = title.isNotBlank() && amount.isNotBlank() && folderId.isNotBlank() &&
                            interval.toIntOrNull()?.let { it > 0 } == true &&
                            runCatching { LocalDate.parse(startDate) }.isSuccess,
                    ) { Text("Add recurring payment") }
                }
            }
        }
        item { SectionTitle("Schedules (${state.payments.size})") }
        if (state.payments.isEmpty()) {
            item { Text("No recurring payments yet.") }
        }
        state.payments.forEach { payment ->
            item(key = "payment-${payment.id}") {
                PaymentCard(
                    payment = payment,
                    today = state.today,
                    upcoming = state.due + state.upcoming,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onSkip = onSkip,
                    onChangeAmount = onChangeAmount,
                )
            }
        }
        item { SectionTitle("Due, not logged (${state.due.size})") }
        if (state.due.isEmpty()) item { Text("Nothing needs logging.") }
        state.due.forEach { occurrence ->
            item(key = "due-${occurrence.key.recurringPaymentId}-${occurrence.key.dueDate}") {
                OccurrenceCard(occurrence, actionLabel = "Log now") {
                    onLog(occurrence.key.recurringPaymentId, occurrence.key.dueDate)
                }
            }
        }
        item { SectionTitle("Upcoming, not logged (${state.upcoming.size})") }
        if (state.upcoming.isEmpty()) item { Text("Nothing scheduled in the next year.") }
        state.upcoming.forEach { occurrence ->
            item(key = "upcoming-${occurrence.key.recurringPaymentId}-${occurrence.key.dueDate}") {
                OccurrenceCard(occurrence, actionLabel = "Skip") {
                    onSkip(occurrence.key.recurringPaymentId, occurrence.key.dueDate)
                }
            }
        }
        item { SectionTitle("Logged (${state.logged.size})") }
        if (state.logged.isEmpty()) item { Text("No recurring occurrences logged yet.") }
        state.logged.forEach { log ->
            item(key = "logged-${log.occurrence.key.recurringPaymentId}-${log.occurrence.key.dueDate}") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(log.occurrence.title, style = MaterialTheme.typography.titleMedium)
                        Text("${log.occurrence.key.dueDate} · ${log.occurrence.amountMinorUnits} ${log.occurrence.currency}")
                        Text(
                            if (log.origin == LoggingOrigin.AUTOMATIC_CATCH_UP) "Automatic catch-up" else "Confirmed manually",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PaymentCard(
    payment: RecurringPayment,
    today: LocalDate,
    upcoming: List<ScheduledOccurrence>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSkip: (String, LocalDate) -> Unit,
    onChangeAmount: (String, Long, LocalDate) -> Unit,
) {
    var newAmount by remember(payment.id, payment.amountRevisions) {
        mutableStateOf(payment.amountMinorUnitsOn(today.coerceAtLeast(payment.startsOn)).toString())
    }
    var effectiveDate by remember(payment.id, today) { mutableStateOf(today.toString()) }
    val status = payment.status(today)
    val next = upcoming.firstOrNull { it.key.recurringPaymentId == payment.id }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(payment.title, style = MaterialTheme.typography.titleMedium)
            Text("${payment.rule.description()} · ${payment.loggingPolicy.description()}")
            Text("Status: ${status.name.lowercase()} · Folder: ${payment.folderId}")
            Text(next?.let { "Next: ${it.key.dueDate} · ${it.amountMinorUnits} ${it.currency}" } ?: "No next occurrence in view")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (status) {
                    PaymentStatus.ACTIVE -> TextButton(onClick = { onPause(payment.id) }) { Text("Pause") }
                    PaymentStatus.PAUSED -> TextButton(onClick = { onResume(payment.id) }) { Text("Resume") }
                    PaymentStatus.CANCELLED -> Unit
                }
                if (next != null && status != PaymentStatus.CANCELLED) {
                    TextButton(onClick = { onSkip(payment.id, next.key.dueDate) }) { Text("Skip next") }
                }
                if (status != PaymentStatus.CANCELLED) {
                    TextButton(onClick = { onCancel(payment.id) }) { Text("Cancel") }
                }
            }
            if (status != PaymentStatus.CANCELLED) {
                HorizontalDivider()
                Text("Change future amount", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAmount,
                        onValueChange = { newAmount = it.filter(Char::isDigit) },
                        label = { Text("Minor units") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = effectiveDate,
                        onValueChange = { effectiveDate = it },
                        label = { Text("Effective date") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                Button(
                    onClick = {
                        onChangeAmount(
                            payment.id,
                            requireNotNull(newAmount.toLongOrNull()),
                            LocalDate.parse(effectiveDate),
                        )
                    },
                    enabled = newAmount.toLongOrNull()?.let { it > 0 } == true &&
                        runCatching { LocalDate.parse(effectiveDate) }.isSuccess,
                ) { Text("Apply amount change") }
            }
        }
    }
}

@Composable
private fun OccurrenceCard(
    occurrence: ScheduledOccurrence,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(occurrence.title, style = MaterialTheme.typography.titleMedium)
                Text("${occurrence.key.dueDate} · ${occurrence.amountMinorUnits} ${occurrence.currency}")
                Text("Folder: ${occurrence.folderId}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

private fun RecurrenceRule.description(): String = when (this) {
    is RecurrenceRule.Weekly -> if (everyWeeks == 1) "Weekly" else "Every $everyWeeks weeks"
    is RecurrenceRule.Monthly -> if (everyMonths == 1) "Monthly" else "Every $everyMonths months"
    is RecurrenceRule.Yearly -> if (everyYears == 1) "Yearly" else "Every $everyYears years"
    is RecurrenceRule.CustomDays -> "Every $everyDays days"
}

private fun LoggingPolicy.description(): String = when (this) {
    LoggingPolicy.MANUAL_CONFIRMATION -> "Manual confirmation"
    LoggingPolicy.AUTOMATIC_CATCH_UP -> "Automatic catch-up"
}
