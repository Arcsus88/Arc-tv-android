package com.arcsus.arcdisney

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arcsus.arcdisney.admin.Kiosk
import com.arcsus.arcdisney.ui.App
import com.arcsus.arcdisney.ui.DisneyViewModel
import com.arcsus.arcdisney.ui.theme.ArcDisneyTheme

class MainActivity : ComponentActivity() {

    private val vm: DisneyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The system splash is plain night blue; the app's own Disney splash follows it.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // As device owner: home screen, pinned, and the Wi-Fi screen's permissions.
        Kiosk.setUp(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ArcDisneyTheme { App(vm) }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as ArcDisneyApp).graph.warmUp()
    }

    override fun onResume() {
        super.onResume()
        Kiosk.lock(this)
    }

    override fun onStop() {
        (application as ArcDisneyApp).graph.coolDown()
        super.onStop()
    }
}
