package com.timetwister.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.timetwister.app.ui.SettingsScreen
import com.timetwister.app.ui.TimeTwisterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TimeTwisterTheme {
                SettingsScreen()
            }
        }
    }
}
