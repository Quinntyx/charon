package dev.quinntyx.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import dev.quinntyx.charon.recurrence.InMemoryRecurrenceRepository
import dev.quinntyx.charon.recurrence.RecurrenceManagementRoute
import dev.quinntyx.charon.recurrence.RecurrenceService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val service = remember {
                        RecurrenceService(InMemoryRecurrenceRepository())
                    }
                    RecurrenceManagementRoute(service)
                }
            }
        }
    }
}
