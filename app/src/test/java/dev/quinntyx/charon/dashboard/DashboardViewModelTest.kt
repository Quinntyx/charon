package dev.quinntyx.charon.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private lateinit var dispatcher: StandardTestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun changingPeriodRequestsMatchingSnapshot() = runTest(dispatcher) {
        val requestedPeriods = mutableListOf<DashboardPeriod>()
        val repository = object : DashboardRepository {
            override fun observeDashboard(period: DashboardPeriod): Flow<DashboardSnapshot> {
                requestedPeriods += period
                return flowOf(DashboardSnapshot.empty(period))
            }
        }
        val viewModel = DashboardViewModel(repository)
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        advanceUntilIdle()
        viewModel.selectPeriod(DashboardPeriod.NINETY_DAYS)
        advanceUntilIdle()

        assertEquals(
            listOf(DashboardPeriod.THIRTY_DAYS, DashboardPeriod.NINETY_DAYS),
            requestedPeriods,
        )
        assertEquals(DashboardPeriod.NINETY_DAYS, viewModel.uiState.value.period)
        collection.cancel()
    }
}
