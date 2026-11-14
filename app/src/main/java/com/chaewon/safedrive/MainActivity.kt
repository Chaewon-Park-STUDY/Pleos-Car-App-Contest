package com.chaewon.safedrive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.chaewon.safedrive.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as SafeDriveApp).controller
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFFFFB74D),
                    background = Color(0xFF101418),
                    surface = Color(0xFF1A2027),
                ),
            ) {
                AppRoot(controller)
            }
        }
    }
}
