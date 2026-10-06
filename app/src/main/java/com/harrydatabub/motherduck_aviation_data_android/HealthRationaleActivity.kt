package com.harrydatabub.motherduck_aviation_data_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harrydatabub.motherduck_aviation_data_android.ui.theme.RampTheme

/**
 * The privacy rationale Health Connect shows from its permission screen. It must say what
 * the app reads and writes and why.
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RampTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("Ramp Ops and your health data", style = MaterialTheme.typography.headlineSmall)
                        Para(
                            "During a shift, the Shift tab reads your steps, distance, active energy, heart rate " +
                                "and water from Health Connect, counted from when you started the shift, to show how " +
                                "the shift is going and when to drink.",
                        )
                        Para("When you log a drink with the water buttons, Ramp Ops saves it to Health Connect.")
                        Para(
                            "Health data stays on this device. Ramp Ops does not send it anywhere, does not share it " +
                                "and does not use it for anything else. You can change or remove access at any time " +
                                "in Health Connect.",
                        )
                        Para("The guidance in the app is not medical advice.")
                        Button(onClick = ::finish, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Close") }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Para(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)
