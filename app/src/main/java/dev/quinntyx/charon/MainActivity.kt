package dev.quinntyx.charon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import dev.quinntyx.charon.capture.ReceiptCaptureScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ReceiptCaptureScreen(onReceiptReady = { /* Integration continues with review/OCR. */ })
            }
        }
    }
}
