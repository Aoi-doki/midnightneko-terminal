package dev.aoidoki.arise

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.aoidoki.arise.ui.AriseRoot
import dev.aoidoki.arise.ui.MainViewModel
import kotlinx.coroutines.launch

/** What the screens need to ask Android for, and whether they have it. */
class Perms(
    val request: (Perm) -> Unit,
) {
    var activity by mutableStateOf(false)
    var notifications by mutableStateOf(false)
    var camera by mutableStateOf(false)
    var health by mutableStateOf(false)
    var healthAvailable by mutableStateOf(false)
    var battery by mutableStateOf(false)

    fun granted(p: Perm): Boolean = when (p) {
        Perm.ACTIVITY -> activity
        Perm.NOTIFICATIONS -> notifications
        Perm.CAMERA -> camera
        Perm.HEALTH -> health
        Perm.BATTERY -> battery
    }
}

enum class Perm { ACTIVITY, NOTIFICATIONS, CAMERA, HEALTH, BATTERY }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var perms: Perms

    private val runtime = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPerms(); if (perms.activity) vm.startTracker() }
    private val healthLauncher by lazy { registerForActivityResult(graph.health.permissionContract()) { refreshPerms() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        perms = Perms(::ask)
        healthLauncher // must register before STARTED
        refreshPerms()
        setContent { AriseRoot(vm, perms) }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        vm.onResume()
    }

    @SuppressLint("BatteryLife")
    private fun ask(p: Perm) {
        when (p) {
            Perm.ACTIVITY -> runtime.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            Perm.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
            Perm.CAMERA -> runtime.launch(Manifest.permission.CAMERA)
            Perm.HEALTH -> {
                val hc = graph.health
                if (hc.available()) {
                    healthLauncher.launch(hc.permissions)
                } else {
                    // Not installed / needs an update: send the Player to the Play Store listing.
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
                                .setPackage("com.android.vending"),
                        )
                    }
                }
            }
            Perm.BATTERY -> runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
    }

    private fun refreshPerms() {
        fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        perms.activity = has(Manifest.permission.ACTIVITY_RECOGNITION)
        perms.notifications = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
        perms.camera = has(Manifest.permission.CAMERA)
        perms.battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        perms.healthAvailable = graph.health.available()
        lifecycleScope.launch { perms.health = graph.health.hasCore() }
    }
}
