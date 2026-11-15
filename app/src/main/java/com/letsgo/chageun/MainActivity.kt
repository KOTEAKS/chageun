package com.letsgo.chageun

import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.letsgo.chageun.ui.ChageunTheme
import com.letsgo.chageun.ui.Root

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Vehicle speed is a runtime permission on AAOS. Without it the parking gate stays closed (fail-closed). */
    private val askSpeed = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onSpeedPermissionResult(granted, canAskAgain = shouldShowRequestPermissionRationale(Car.PERMISSION_SPEED))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readDemoExtras(intent)
        lifecycleScope.launch {
            vm.actions.collect { action ->
                when (action) {
                    PlatformAction.ASK_SPEED_PERMISSION -> askSpeed.launch(Car.PERMISSION_SPEED)
                    PlatformAction.OPEN_APP_SETTINGS -> runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }.onFailure { Log.w("Chageun", "app settings not available", it) }
                }
            }
        }
        setContent {
            val state by vm.ui.collectAsStateWithLifecycle()
            // Ask only after the first-run screen has explained why speed is read (the system dialog calls it "location").
            LaunchedEffect(state.settings.firstRunDone) {
                if (state.settings.firstRunDone && checkSelfPermission(Car.PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
                    askSpeed.launch(Car.PERMISSION_SPEED)
                }
            }
            ChageunTheme(largeText = state.settings.largeText) { Root(state, vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDemoExtras(intent)
    }

    /** Debug builds only: `am start ... --ei dayOffset 1` shows tomorrow's review on the emulator. Never in a release. */
    private fun readDemoExtras(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.hasExtra("dayOffset") == true) vm.setDayOffset(intent.getIntExtra("dayOffset", 0))
    }

    override fun onStart() {
        super.onStart()
        // Back from the system settings, the speed permission may have been granted: pick it up without a restart.
        vm.onSpeedPermissionChecked(checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED)
        vm.onAppShown()
    }

    /** The app leaving the screen (another app, Suspend after ignition off) stops the session. It never resumes on its own. */
    override fun onStop() {
        super.onStop()
        vm.onAppHidden()
    }
}
