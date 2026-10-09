package dev.quinntyx.charon.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class DashboardViewModel(
    private val repository: DashboardRepository,
) : ViewModel() {
    private val period = MutableStateFlow(DashboardPeriod.THIRTY_DAYS)
    private val requestedCurrencyCode = MutableStateFlow<String?>(null)
    private val requestedDate = MutableStateFlow<LocalDate?>(null)
    private val requestedTransactionId = MutableStateFlow<String?>(null)

    private val loadState = period.flatMapLatest { selectedPeriod ->
        flow {
            emit(DashboardLoadState.Loading(selectedPeriod))
            emitAll(
                repository.observeDashboard(selectedPeriod)
                    .map<DashboardSnapshot, DashboardLoadState> { DashboardLoadState.Data(it) }
                    .catch { error ->
                        emit(
                            DashboardLoadState.Error(
                                period = selectedPeriod,
                                message = error.message ?: "Dashboard data could not be loaded",
                            ),
                        )
                    },
            )
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        loadState,
        requestedCurrencyCode,
        requestedDate,
        requestedTransactionId,
    ) { load, currencyCode, date, transactionId ->
        when (load) {
            is DashboardLoadState.Loading -> DashboardUiState(
                period = load.period,
                isLoading = true,
            )

            is DashboardLoadState.Error -> DashboardUiState(
                period = load.period,
                errorMessage = load.message,
            )

            is DashboardLoadState.Data -> resolveDashboardState(
                snapshot = load.snapshot,
                requestedCurrencyCode = currencyCode,
                requestedDate = date,
                requestedTransactionId = transactionId,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(period = DashboardPeriod.THIRTY_DAYS, isLoading = true),
    )

    fun selectPeriod(value: DashboardPeriod) {
        if (period.value != value) {
            period.value = value
            requestedDate.value = null
            requestedTransactionId.value = null
        }
    }

    fun selectCurrency(currencyCode: String) {
        requestedCurrencyCode.value = currencyCode
        requestedDate.value = null
        requestedTransactionId.value = null
    }

    fun selectDate(date: LocalDate) {
        requestedDate.value = date
    }

    fun selectTransaction(transactionId: String) {
        requestedTransactionId.value = transactionId
    }

    fun dismissTransaction() {
        requestedTransactionId.value = null
    }

    class Factory(
        private val repository: DashboardRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DashboardViewModel::class.java))
            return DashboardViewModel(repository) as T
        }
    }
}

data class DashboardUiState(
    val period: DashboardPeriod,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val snapshot: DashboardSnapshot? = null,
    val selectedCurrencyCode: String? = null,
    val selectedDate: LocalDate? = null,
    val selectedTransaction: DashboardTransaction? = null,
) {
    val currencyCodes: List<String>
        get() = snapshot?.availableCurrencyCodes().orEmpty()

    val selectedOverview: CurrencyOverview?
        get() = snapshot?.currencies?.firstOrNull { it.currencyCode == selectedCurrencyCode }

    val upcomingPayments: List<UpcomingPayment>
        get() = snapshot?.upcomingPayments
            .orEmpty()
            .filter { it.currencyCode == selectedCurrencyCode }
            .sortedBy { it.dueDate }

    val recentTransactions: List<DashboardTransaction>
        get() = snapshot?.recentTransactions
            .orEmpty()
            .filter { it.currencyCode == selectedCurrencyCode }
            .sortedByDescending { it.occurredOn }
}

internal fun resolveDashboardState(
    snapshot: DashboardSnapshot,
    requestedCurrencyCode: String?,
    requestedDate: LocalDate?,
    requestedTransactionId: String?,
): DashboardUiState {
    val currencyCodes = snapshot.availableCurrencyCodes()
    val selectedCurrencyCode = requestedCurrencyCode?.takeIf(currencyCodes::contains)
        ?: currencyCodes.firstOrNull()
    val selectedOverview = snapshot.currencies
        .firstOrNull { it.currencyCode == selectedCurrencyCode }
    val dates = selectedOverview?.dailyActivity.orEmpty().mapTo(mutableSetOf()) { it.date }
    val selectedDate = requestedDate?.takeIf(dates::contains)
        ?: selectedOverview?.dailyActivity?.maxByOrNull { it.date }?.date
    val selectedTransaction = snapshot.recentTransactions
        .firstOrNull {
            it.id == requestedTransactionId && it.currencyCode == selectedCurrencyCode
        }

    return DashboardUiState(
        period = snapshot.period,
        snapshot = snapshot,
        selectedCurrencyCode = selectedCurrencyCode,
        selectedDate = selectedDate,
        selectedTransaction = selectedTransaction,
    )
}

private fun DashboardSnapshot.availableCurrencyCodes(): List<String> = buildList {
    currencies.forEach { add(it.currencyCode) }
    upcomingPayments.forEach { if (it.currencyCode !in this) add(it.currencyCode) }
    recentTransactions.forEach { if (it.currencyCode !in this) add(it.currencyCode) }
}

private sealed interface DashboardLoadState {
    val period: DashboardPeriod

    data class Loading(override val period: DashboardPeriod) : DashboardLoadState

    data class Error(
        override val period: DashboardPeriod,
        val message: String,
    ) : DashboardLoadState

    data class Data(val snapshot: DashboardSnapshot) : DashboardLoadState {
        override val period: DashboardPeriod = snapshot.period
    }
}
