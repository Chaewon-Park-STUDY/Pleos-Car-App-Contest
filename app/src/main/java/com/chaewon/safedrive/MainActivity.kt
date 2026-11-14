package com.chaewon.safedrive

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.chaewon.safedrive.signal.PleosLocationSource
import com.chaewon.safedrive.ui.AppRoot

class MainActivity : ComponentActivity() {

    private val app get() = application as SafeDriveApp
    private var askedLocation = false

    /** 위치 권한은 dangerous 등급 → 사용자가 직접 허용 (사람이 결정) */
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) app.startLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = app

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFFFFB74D),
                    background = Color(0xFF101418),
                    surface = Color(0xFF1A2027),
                ),
            ) {
                AppRoot(app.controller, onMic = { app.stt.toggle() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.refreshSpeechAvailability()
        // Pleos 환경에서만 위치 권한 요청 (일반 폰 에뮬레이터에서는 데모 모드만 사용)
        if (!app.location.isRunning && app.location.isSupported()) {
            val p = PleosLocationSource.PERMISSION
            if (ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED) app.startLocation()
            else if (!askedLocation) { askedLocation = true; locationPermission.launch(p) }
        }
    }
}
