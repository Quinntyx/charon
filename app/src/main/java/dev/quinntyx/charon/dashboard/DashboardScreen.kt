package dev.quinntyx.charon.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel,
    onAddTransaction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        state = state,
        onPeriodSelected = viewModel::selectPeriod,
        onCurrencySelected = viewModel::selectCurrency,
        onDateSelected = viewModel::selectDate,
        onTransactionSelected = viewModel::selectTransaction,
        onDismissTransaction = viewModel::dismissTransaction,
        onAddTransaction = onAddTransaction,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onPeriodSelected: (DashboardPeriod) -> Unit,
    onCurrencySelected: (String) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onTransactionSelected: (String) -> Unit,
    onDismissTransaction: () -> Unit,
    onAddTransaction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Charon") }) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DashboardHeader(onAddTransaction = onAddTransaction)
            }
            item {
                PeriodSelector(
                    selected = state.period,
                    onSelected = onPeriodSelected,
                )
            }
            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Loading dashboard"
                            },
                        )
                    }
                }
            } else {
                state.errorMessage?.let { message ->
                    item { ErrorCard(message) }
                }
                item {
                    CurrencySelector(
                        currencyCodes = state.currencyCodes,
                        selectedCurrencyCode = state.selectedCurrencyCode,
                        onSelected = onCurrencySelected,
                    )
                }
                item {
                    IncomeExpenseCard(state.selectedOverview)
                }
                item {
                    SpendingGraphCard(
                        overview = state.selectedOverview,
                        selectedDate = state.selectedDate,
                        onDateSelected = onDateSelected,
                    )
                }
                item {
                    TagBreakdownCard(state.selectedOverview)
                }
                item {
                    UpcomingPaymentsCard(state.upcomingPayments)
                }
                item {
                    SectionHeading("Recent transactions")
                }
                if (state.recentTransactions.isEmpty()) {
                    item {
                        EmptyCard("No recorded transactions in this currency yet.")
                    }
                } else {
                    items(
                        items = state.recentTransactions,
                        key = { it.id },
                    ) { transaction ->
                        TransactionRow(
                            transaction = transaction,
                            onClick = { onTransactionSelected(transaction.id) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    state.selectedTransaction?.let { transaction ->
        TransactionDialog(
            transaction = transaction,
            onDismiss = onDismissTransaction,
        )
    }
}

@Composable
private fun DashboardHeader(onAddTransaction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Spending, income, and upcoming payments",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onAddTransaction) {
            Icon(Icons.Default.ReceiptLong, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add")
        }
    }
}

@Composable
private fun PeriodSelector(
    selected: DashboardPeriod,
    onSelected: (DashboardPeriod) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionHeading("Reporting window", includePadding = false)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DashboardPeriod.entries.forEach { period ->
                FilterChip(
                    selected = selected == period,
                    onClick = { onSelected(period) },
                    label = { Text(period.label) },
                )
            }
        }
    }
}

@Composable
private fun CurrencySelector(
    currencyCodes: List<String>,
    selectedCurrencyCode: String?,
    onSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        SectionHeading("Currency", includePadding = false)
        if (currencyCodes.isEmpty()) {
            Text(
                text = "No currency totals yet. Currencies stay separate when transactions are added.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                currencyCodes.forEach { currencyCode ->
                    FilterChip(
                        selected = selectedCurrencyCode == currencyCode,
                        onClick = { onSelected(currencyCode) },
                        label = { Text(currencyCode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun IncomeExpenseCard(overview: CurrencyOverview?) {
    DashboardCard {
        SectionHeading("Income and expense", includePadding = false)
        if (overview == null) {
            Text("No recorded income or expenses for this window.")
            return@DashboardCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryValue(
                label = "Expenses",
                value = MoneyFormatter.format(overview.expenseTotalMinor, overview.currencyCode),
                modifier = Modifier.weight(1f),
            )
            SummaryValue(
                label = "Income",
                value = MoneyFormatter.format(overview.incomeTotalMinor, overview.currencyCode),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${overview.rollingAverageWindowDays}-day daily expense average: " +
                MoneyFormatter.format(overview.rollingExpenseAverageMinor, overview.currencyCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Includes zero-spend days. Transfers are excluded from income and expense totals.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SummaryValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SpendingGraphCard(
    overview: CurrencyOverview?,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
) {
    DashboardCard {
        SectionHeading("Daily spending", includePadding = false)
        val points = overview?.dailyActivity.orEmpty().sortedBy { it.date }
        if (overview == null || points.isEmpty()) {
            Text("No daily spending data for this window.")
            return@DashboardCard
        }
        SpendingGraph(
            points = points,
            currencyCode = overview.currencyCode,
            selectedDate = selectedDate,
            onDateSelected = onDateSelected,
        )
    }
}

@Composable
private fun SpendingGraph(
    points: List<DailyActivity>,
    currencyCode: String,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
) {
    val selectedIndex = points.indexOfFirst { it.date == selectedDate }
        .takeIf { it >= 0 }
        ?: points.lastIndex
    val selectedPoint = points[selectedIndex]
    val maxAmount = points.maxOf { it.expenseMinor }.coerceAtLeast(1L)
    val lineColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val selectedColor = MaterialTheme.colorScheme.tertiary
    val graphDescription = buildString {
        append("Daily expense graph from ")
        append(formatDate(points.first().date))
        append(" to ")
        append(formatDate(points.last().date))
        append(". Selected ")
        append(formatDate(selectedPoint.date))
        append(", ")
        append(MoneyFormatter.format(selectedPoint.expenseMinor, currencyCode))
    }

    Text(
        text = "${formatDate(selectedPoint.date)}: " +
            MoneyFormatter.format(selectedPoint.expenseMinor, currencyCode),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = "Tap the graph or use the day controls.",
        style = MaterialTheme.typography.bodySmall,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(vertical = 12.dp)
            .semantics { contentDescription = graphDescription }
            .pointerInput(points) {
                detectTapGestures { position ->
                    val fraction = if (size.width == 0) 0f else position.x / size.width
                    val index = (fraction * points.size)
                        .toInt()
                        .coerceIn(points.indices)
                    onDateSelected(points[index].date)
                }
            },
    ) {
        drawLine(
            color = guideColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 2f,
        )
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = if (points.size == 1) {
                size.width / 2f
            } else {
                size.width * index / points.lastIndex
            }
            val y = size.height * (1f - point.expenseMinor.toFloat() / maxAmount.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (index == selectedIndex) {
                drawLine(
                    color = guideColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                )
                drawCircle(color = selectedColor, radius = 8f, center = Offset(x, y))
            }
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 5f, cap = StrokeCap.Round),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(formatShortDate(points.first().date), style = MaterialTheme.typography.labelSmall)
        Text(formatShortDate(points.last().date), style = MaterialTheme.typography.labelSmall)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onDateSelected(points[selectedIndex - 1].date) },
            enabled = selectedIndex > 0,
        ) {
            Text("Previous day")
        }
        Text("${selectedIndex + 1} of ${points.size}")
        OutlinedButton(
            onClick = { onDateSelected(points[selectedIndex + 1].date) },
            enabled = selectedIndex < points.lastIndex,
        ) {
            Text("Next day")
        }
    }
}

@Composable
private fun TagBreakdownCard(overview: CurrencyOverview?) {
    DashboardCard {
        SectionHeading("Spending by tag", includePadding = false)
        val tags = overview?.tagSpending.orEmpty().filter { it.expenseMinor > 0 }
        if (overview == null || tags.isEmpty()) {
            Text("No tagged expenses for this window.")
            return@DashboardCard
        }
        val largest = tags.maxOf { it.expenseMinor }.coerceAtLeast(1L)
        tags.sortedByDescending { it.expenseMinor }.forEach { tag ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tag.tagName, modifier = Modifier.weight(1f))
                Text(MoneyFormatter.format(tag.expenseMinor, overview.currencyCode))
            }
            LinearProgressIndicator(
                progress = { tag.expenseMinor.toFloat() / largest.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun UpcomingPaymentsCard(payments: List<UpcomingPayment>) {
    DashboardCard {
        SectionHeading("Upcoming payments", includePadding = false)
        if (payments.isEmpty()) {
            Text("No upcoming payments in this currency.")
            Text(
                "Upcoming items stay separate from recorded expenses until they are logged.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@DashboardCard
        }
        payments.forEachIndexed { index, payment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        payment.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (payment.isOverdue) {
                            "Overdue · ${formatDate(payment.dueDate)}"
                        } else {
                            "Due ${formatDate(payment.dueDate)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(MoneyFormatter.format(payment.amountMinor, payment.currencyCode))
            }
            if (index != payments.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Upcoming items are forecasts, not recorded spending.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TransactionRow(
    transaction: DashboardTransaction,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.merchantName
                        ?: transaction.tagName
                        ?: transaction.kind.displayName(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatDate(transaction.occurredOn)} · ${transaction.folderName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = transaction.kind.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text(
                text = transaction.signedAmount(),
                fontWeight = FontWeight.SemiBold,
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open transaction")
        }
    }
}

@Composable
private fun TransactionDialog(
    transaction: DashboardTransaction,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transaction details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Type", transaction.kind.displayName())
                DetailRow("Amount", transaction.signedAmount())
                DetailRow("Date", formatDate(transaction.occurredOn))
                DetailRow("Folder", transaction.folderName)
                DetailRow("Merchant", transaction.merchantName ?: "None")
                DetailRow("Tag", transaction.tagName ?: "None")
                DetailRow("Note", transaction.note?.takeIf { it.isNotBlank() } ?: "None")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (transaction.hasReceipt) {
                            Icons.Default.AttachFile
                        } else {
                            Icons.Default.ReceiptLong
                        },
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (transaction.hasReceipt) "Receipt attached" else "No receipt attached")
                }
                if (transaction.kind == TransactionKind.TRANSFER) {
                    Text(
                        "Transfers move money between folders and do not count as spending.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(16.dp))
        Text(value)
    }
}

@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun EmptyCard(message: String) {
    DashboardCard { Text(message) }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Dashboard unavailable", fontWeight = FontWeight.Bold)
            Text(message)
        }
    }
}

@Composable
private fun SectionHeading(text: String, includePadding: Boolean = true) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .then(if (includePadding) Modifier.padding(horizontal = 16.dp) else Modifier)
            .semantics { heading() },
    )
}

private fun DashboardTransaction.signedAmount(): String {
    val formatted = MoneyFormatter.format(amountMinor, currencyCode)
    return when (kind) {
        TransactionKind.EXPENSE -> "−$formatted"
        TransactionKind.INCOME -> "+$formatted"
        TransactionKind.TRANSFER -> formatted
    }
}

private fun TransactionKind.displayName(): String = when (this) {
    TransactionKind.EXPENSE -> "Expense"
    TransactionKind.INCOME -> "Income"
    TransactionKind.TRANSFER -> "Transfer"
}

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun formatDate(date: LocalDate): String = date.format(dateFormatter)
private fun formatShortDate(date: LocalDate): String = date.format(shortDateFormatter)
