package dev.quinntyx.charon

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.quinntyx.charon.dashboard.DashboardPeriod
import dev.quinntyx.charon.dashboard.DashboardRepository
import dev.quinntyx.charon.dashboard.DashboardRoute
import dev.quinntyx.charon.dashboard.DashboardSnapshot
import dev.quinntyx.charon.dashboard.DashboardViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val dashboardViewModel: DashboardViewModel = viewModel(
                        factory = DashboardViewModel.Factory(EmptyDashboardRepository),
                    )
                    DashboardRoute(
                        viewModel = dashboardViewModel,
                        onAddTransaction = {
                            Toast.makeText(
                                this,
                                "Transaction entry is not connected in this subsystem build.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
        }
    }
}

/** Replaced by the persistence/analytics adapter when feature branches are integrated. */
private object EmptyDashboardRepository : DashboardRepository {
    override fun observeDashboard(period: DashboardPeriod): Flow<DashboardSnapshot> =
        flowOf(DashboardSnapshot.empty(period))
}
