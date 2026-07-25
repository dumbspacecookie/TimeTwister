package com.timetwister.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.timetwister.app.ui.FirstRunScreen
import com.timetwister.app.ui.SettingsScreen
import com.timetwister.app.ui.TimeTwisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Required behaviour at targetSdk 35: the system no longer letterboxes the app out
        // of the system bar areas, so we opt in explicitly and let Scaffold apply the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TimeTwisterTheme {
                // Surface paints the themed background under the edge-to-edge system bars.
                Surface(Modifier.fillMaxSize()) {
                    val prefs = remember { UserPreferences(applicationContext) }
                    // Read synchronously so we never flash settings before the tutorial;
                    // rememberSaveable keeps the decision stable across configuration change.
                    var showOnboarding by rememberSaveable {
                        mutableStateOf(!prefs.hasSeenOnboarding())
                    }

                    if (showOnboarding) {
                        FirstRunScreen(onDone = {
                            prefs.markOnboardingSeen()
                            showOnboarding = false
                        })
                    } else {
                        SettingsScreen()
                    }
                }
            }
        }
    }
}
