package dev.quinntyx.charon

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.quinntyx.charon.backup.BackupDocumentPanel
import dev.quinntyx.charon.dashboard.MoneyFormatter
import dev.quinntyx.charon.domain.Account
import dev.quinntyx.charon.domain.DeleteResult
import dev.quinntyx.charon.domain.LedgerRepository
import dev.quinntyx.charon.domain.Tag
import dev.quinntyx.charon.entry.AmountParseResult
import dev.quinntyx.charon.entry.MinorUnitAmountParser
import dev.quinntyx.charon.entry.RoomTransactionEntryAdapter
import dev.quinntyx.charon.entry.TransactionEntryConfig
import dev.quinntyx.charon.entry.TransactionEntryDraft
import dev.quinntyx.charon.entry.TransactionEntryScreen
import dev.quinntyx.charon.recurrence.CreateRecurringPayment
import dev.quinntyx.charon.recurrence.LoggingPolicy
import dev.quinntyx.charon.recurrence.PaymentStatus
import dev.quinntyx.charon.recurrence.RecurrenceRule
import dev.quinntyx.charon.recurrence.RecurrenceService
import dev.quinntyx.charon.recurrence.RecurringPayment
import java.time.LocalDate
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TransactionEntryHost(
    adapter: RoomTransactionEntryAdapter,
    transactionId: Long?,
    initialDraft: TransactionEntryDraft? = null,
    onFinished: () -> Unit,
) {
    var reload by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val config by produceState<TransactionEntryConfig?>(null, transactionId, initialDraft, reload) {
        value = runCatching {
            val draft = when {
                initialDraft != null -> initialDraft
                transactionId != null -> adapter.draftFor(transactionId)
                else -> TransactionEntryDraft(date = LocalDate.now().toString())
            }
            adapter.config(draft)
        }.onFailure { status = it.message ?: "Could not load transaction entry" }.getOrNull()
    }
    Column(Modifier.fillMaxSize()) {
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        config?.let { loaded ->
            TransactionEntryScreen(
                config = loaded,
                onCancel = onFinished,
                onSave = { entry ->
                    scope.launch {
                        try {
                            adapter.save(entry)
                            onFinished()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            status = "Save failed: ${error.message ?: "unknown error"}"
                            reload++
                        }
                    }
                },
            )
        } ?: Text("Loading…", modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun OrganizationScreen(repository: LedgerRepository) {
    val accounts by repository.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by repository.observeTags().collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by repository.observeTransactions().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var accountName by rememberSaveable { mutableStateOf("") }
    var accountCurrency by rememberSaveable { mutableStateOf("") }
    var tagName by rememberSaveable { mutableStateOf("") }
    var renameAccountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameTagId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("Folders are accounts; tags classify transactions.") }

    fun runAction(action: suspend () -> String) {
        scope.launch {
            status = try { action() } catch (error: Exception) { "Action failed: ${error.message ?: "unknown error"}" }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Folders and tags", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Text("Create folder", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(accountName, { accountName = it }, label = { Text("Folder name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(accountCurrency, { accountCurrency = it }, label = { Text("Currency code") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                runAction {
                    val created = repository.createAccount(accountName, accountCurrency)
                    accountName = ""; accountCurrency = ""
                    "Created ${created.name}"
                }
            },
            enabled = accountName.isNotBlank() && accountCurrency.isNotBlank(),
        ) { Text("Create folder") }

        Text("Folders and balances", style = MaterialTheme.typography.titleMedium)
        if (accounts.isEmpty()) Text("No folders yet. Create one to record transactions.")
        accounts.forEach { account ->
            AccountCard(
                account = account,
                repository = repository,
                refreshKey = transactions.hashCode(),
                onRename = { renameAccountId = account.id; renameTagId = null; renameValue = account.name },
                onArchive = { runAction { repository.setAccountArchived(account.id, !account.archived); "Updated ${account.name}" } },
                onDelete = {
                    runAction {
                        when (val result = repository.deleteAccountSafely(account.id)) {
                            DeleteResult.Deleted -> "Deleted ${account.name}"
                            is DeleteResult.Blocked -> "Cannot delete ${account.name}: ${result.reason}"
                        }
                    }
                },
            )
        }

        HorizontalDivider()
        Text("Create tag", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(tagName, { tagName = it }, label = { Text("Tag name") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { runAction { val created = repository.createTag(tagName); tagName = ""; "Created ${created.name}" } }, enabled = tagName.isNotBlank()) {
            Text("Create tag")
        }
        if (tags.isEmpty()) Text("No tags yet.")
        tags.forEach { tag ->
            TagCard(
                tag = tag,
                onRename = { renameTagId = tag.id; renameAccountId = null; renameValue = tag.name },
                onArchive = { runAction { repository.setTagArchived(tag.id, !tag.archived); "Updated ${tag.name}" } },
                onDelete = {
                    runAction {
                        when (val result = repository.deleteTagSafely(tag.id)) {
                            DeleteResult.Deleted -> "Deleted ${tag.name}"
                            is DeleteResult.Blocked -> "Cannot delete ${tag.name}: ${result.reason}"
                        }
                    }
                },
            )
        }

        if (renameAccountId != null || renameTagId != null) {
            HorizontalDivider()
            Text("Rename", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(renameValue, { renameValue = it }, label = { Text("New name") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runAction {
                        renameAccountId?.let { repository.renameAccount(it, renameValue) }
                        renameTagId?.let { repository.renameTag(it, renameValue) }
                        renameAccountId = null; renameTagId = null
                        "Renamed"
                    }
                }) { Text("Save rename") }
                TextButton(onClick = { renameAccountId = null; renameTagId = null }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AccountCard(
    account: Account,
    repository: LedgerRepository,
    refreshKey: Int,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val balance by produceState<Long?>(null, account.id, refreshKey) { value = repository.getAccountBalance(account.id) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${account.name}${if (account.archived) " (archived)" else ""}", style = MaterialTheme.typography.titleMedium)
            Text("Balance: ${balance?.let { MoneyFormatter.format(it, account.currencyCode) } ?: "…"}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onArchive) { Text(if (account.archived) "Unarchive" else "Archive") }
                TextButton(onClick = onDelete) { Text("Delete safely") }
            }
        }
    }
}

@Composable
private fun TagCard(tag: Tag, onRename: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("${tag.name}${if (tag.archived) " (archived)" else ""}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onRename) { Text("Rename") }
                TextButton(onClick = onArchive) { Text(if (tag.archived) "Unarchive" else "Archive") }
                TextButton(onClick = onDelete) { Text("Delete safely") }
            }
        }
    }
}

@Composable
internal fun RecurrenceScreen(service: RecurrenceService, repository: LedgerRepository) {
    val payments by service.repository.observePayments().collectAsStateWithLifecycle(initialValue = emptyList())
    val logs by service.repository.observeLoggedOccurrences().collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by repository.observeAccounts().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val pending by produceState(initialValue = emptyList(), payments, logs) {
        val from = payments.minOfOrNull { it.startsOn } ?: today
        value = service.pendingOccurrences(from, today.plusDays(90))
    }
    var title by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf(today.toString()) }
    var frequency by rememberSaveable { mutableStateOf("MONTHLY") }
    var automatic by rememberSaveable { mutableStateOf(false) }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var editAmount by rememberSaveable { mutableStateOf("") }
    var editDate by rememberSaveable { mutableStateOf(today.toString()) }
    var status by remember { mutableStateOf("Upcoming items are forecasts until explicitly or automatically logged.") }

    fun runAction(action: suspend () -> String) {
        scope.launch { status = try { action() } catch (error: Exception) { "Action failed: ${error.message ?: "unknown error"}" } }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Recurring payments", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Text("Create recurring expense", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currency, { currency = it }, label = { Text("Currency") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(startDate, { startDate = it }, label = { Text("Starts YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("WEEKLY", "MONTHLY", "YEARLY").forEach { option ->
                FilterChip(selected = frequency == option, onClick = { frequency = option }, label = { Text(option.lowercase().replaceFirstChar(Char::uppercase)) })
            }
        }
        FilterChip(selected = automatic, onClick = { automatic = !automatic }, label = { Text(if (automatic) "Automatic catch-up" else "Manual logging") })
        Text("Source folder")
        accounts.filterNot(Account::archived).forEach { account ->
            FilterChip(
                selected = selectedFolder == account.id.toString(),
                onClick = { selectedFolder = account.id.toString(); currency = account.currencyCode },
                label = { Text("${account.name} · ${account.currencyCode}") },
            )
        }
        Button(
            enabled = title.isNotBlank() && amount.isNotBlank() && currency.isNotBlank() && selectedFolder != null,
            onClick = {
                runAction {
                    val code = Currency.getInstance(currency.trim().uppercase(Locale.ROOT)).currencyCode
                    val minor = (MinorUnitAmountParser.parse(amount, code) as? AmountParseResult.Valid)?.minorUnits
                        ?: error("Enter a valid positive amount")
                    val rule = when (frequency) {
                        "WEEKLY" -> RecurrenceRule.Weekly()
                        "YEARLY" -> RecurrenceRule.Yearly()
                        else -> RecurrenceRule.Monthly()
                    }
                    service.create(
                        CreateRecurringPayment(
                            title = title, amountMinorUnits = minor, currency = code,
                            folderId = requireNotNull(selectedFolder), startsOn = LocalDate.parse(startDate), rule = rule,
                            loggingPolicy = if (automatic) LoggingPolicy.AUTOMATIC_CATCH_UP else LoggingPolicy.MANUAL_CONFIRMATION,
                        ),
                    )
                    title = ""; amount = ""
                    "Recurring payment created"
                }
            },
        ) { Text("Create recurring payment") }

        HorizontalDivider()
        Text("Plans", style = MaterialTheme.typography.titleMedium)
        if (payments.isEmpty()) Text("No recurring payments yet.")
        payments.forEach { payment ->
            RecurringPaymentCard(
                payment = payment,
                today = today,
                editAmount = editAmount,
                editDate = editDate,
                onEditAmount = { editAmount = it },
                onEditDate = { editDate = it },
                onAction = { action -> runAction { action(service, payment); "Updated ${payment.title}" } },
            )
        }

        HorizontalDivider()
        Text("Due and upcoming (not spending yet)", style = MaterialTheme.typography.titleMedium)
        if (pending.isEmpty()) Text("No pending occurrences through the next 90 days.")
        pending.forEach { occurrence ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${occurrence.title} · ${occurrence.key.dueDate}")
                    Text(MoneyFormatter.format(occurrence.amountMinorUnits, occurrence.currency))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (occurrence.key.dueDate <= today) {
                            Button(onClick = {
                                runAction {
                                    val inserted = service.logManually(occurrence.key.recurringPaymentId, occurrence.key.dueDate)
                                    if (inserted) "Occurrence logged once" else "Occurrence was already logged; no duplicate created"
                                }
                            }) { Text("Log") }
                        }
                        OutlinedButton(onClick = { runAction { service.skip(occurrence.key.recurringPaymentId, occurrence.key.dueDate); "Occurrence skipped" } }) {
                            Text("Skip")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Logged transactions", style = MaterialTheme.typography.titleMedium)
        if (logs.isEmpty()) Text("No recurring occurrences have been logged.")
        logs.forEach { log ->
            Text("${log.occurrence.title} · ${log.occurrence.key.dueDate} · ${MoneyFormatter.format(log.occurrence.amountMinorUnits, log.occurrence.currency)} · ${log.origin}")
        }
    }
}

private typealias RecurrenceAction = suspend (RecurrenceService, RecurringPayment) -> Unit

@Composable
private fun RecurringPaymentCard(
    payment: RecurringPayment,
    today: LocalDate,
    editAmount: String,
    editDate: String,
    onEditAmount: (String) -> Unit,
    onEditDate: (String) -> Unit,
    onAction: (RecurrenceAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(payment.title, style = MaterialTheme.typography.titleMedium)
            Text("${payment.currency} · starts ${payment.startsOn} · ${payment.status(today)} · ${payment.loggingPolicy}")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (payment.status(today)) {
                    PaymentStatus.ACTIVE -> TextButton(onClick = { onAction { service, plan -> service.pause(plan.id) } }) { Text("Pause") }
                    PaymentStatus.PAUSED -> TextButton(onClick = { onAction { service, plan -> service.resume(plan.id) } }) { Text("Resume") }
                    PaymentStatus.CANCELLED -> Unit
                }
                if (payment.status(today) != PaymentStatus.CANCELLED) {
                    TextButton(onClick = { onAction { service, plan -> service.cancel(plan.id) } }) { Text("Cancel") }
                }
            }
            OutlinedTextField(editAmount, onEditAmount, label = { Text("New amount") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(editDate, onEditDate, label = { Text("Effective YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                onAction { service, plan ->
                    val parsed = MinorUnitAmountParser.parse(editAmount, plan.currency) as? AmountParseResult.Valid
                        ?: error("Enter a valid amount")
                    service.changeAmount(plan.id, parsed.minorUnits, LocalDate.parse(editDate))
                }
            }) { Text("Apply amount change") }
        }
    }
}

@Composable
internal fun BackupScreen(application: CharonApplication) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Backup is manual and user-controlled through Android's document picker. Receipt images stay inside the selected archive.",
            modifier = Modifier.padding(16.dp),
        )
        BackupDocumentPanel(
            snapshotProvider = application.backupAdapter::snapshot,
            restoreTarget = application.backupAdapter,
        )
    }
}
