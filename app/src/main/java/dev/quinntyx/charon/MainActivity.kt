package dev.quinntyx.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.quinntyx.charon.capture.ReceiptCaptureScreen
import dev.quinntyx.charon.organization.InMemoryOrganizationRepository
import dev.quinntyx.charon.organization.OrganizationScreen

private enum class AppSection(val label: String) {
    CAPTURE("Capture"),
    ORGANIZATION("Folders & tags"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CharonApp()
            }
        }
    }
}

@Composable
private fun CharonApp() {
    var selectedSectionIndex by rememberSaveable { mutableIntStateOf(AppSection.CAPTURE.ordinal) }
    val organizationRepository = remember { InMemoryOrganizationRepository() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedSectionIndex) {
            AppSection.entries.forEach { section ->
                Tab(
                    selected = selectedSectionIndex == section.ordinal,
                    onClick = { selectedSectionIndex = section.ordinal },
                    text = { Text(section.label) },
                )
            }
        }
        when (AppSection.entries[selectedSectionIndex]) {
            AppSection.CAPTURE -> ReceiptCaptureScreen(
                onReceiptReady = { /* Integration continues with review/OCR. */ },
                modifier = Modifier.weight(1f),
            )
            AppSection.ORGANIZATION -> OrganizationScreen(
                repository = organizationRepository,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
