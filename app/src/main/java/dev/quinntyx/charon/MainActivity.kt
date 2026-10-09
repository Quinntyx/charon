package dev.quinntyx.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.quinntyx.charon.organization.InMemoryOrganizationRepository
import dev.quinntyx.charon.organization.OrganizationScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val repository = remember { InMemoryOrganizationRepository() }
                    OrganizationScreen(repository = repository)
                }
            }
        }
    }
}
