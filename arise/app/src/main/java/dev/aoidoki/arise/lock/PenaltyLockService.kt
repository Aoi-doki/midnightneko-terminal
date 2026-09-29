package dev.aoidoki.arise.lock

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.aoidoki.arise.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Watches which app is in front (window-state changes only; it never reads screen contents) and,
 * while the Penalty Lock is engaged, covers anything that isn't allowed with the lock screen.
 */
class PenaltyLockService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private val foreground = MutableStateFlow<String?>(null)
    private val keyguard = MutableStateFlow(false)
    private var overlay: LockOverlayView? = null
    private var engagedBefore = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkKeyguard()
    }

    override fun onServiceConnected() {
        _running.value = true
        val g = application.graph
        registerReceiver(
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        checkKeyguard()
        val s = MainScope().also { scope = it }
        s.launch {
            combine(g.lock.state, foreground, keyguard, g.settings.flow.map { it.lock.allow }.distinctUntilChanged()) { st, fg, locked, allow ->
                // Never draw over the keyguard: unlocking and emergency calls there must always work.
                val cover = st.engaged && !locked && (fg == null || !LockPolicy.isAllowed(fg, packageName, extras(), allow, st.test))
                st to cover
            }.collect { (st, cover) ->
                if (st.engaged && !st.test && !engagedBefore) g.voice.say("The penalty lock is engaged. Walk.", g.settings.current().voice)
                engagedBefore = st.engaged
                if (cover) show() else hide()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        checkKeyguard()
        if (pkg in LockPolicy.TRANSIENT || pkg == imePackage()) return
        // Our own overlay and dialogs aren't a change of app; only SYSTEM's activities are.
        if (pkg == packageName && event.className?.startsWith("$packageName.") != true) return
        if (pkg == packageName && event.className == LockOverlayView::class.java.name) return
        foreground.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        _running.value = false
        runCatching { unregisterReceiver(screen) }
        hide()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun checkKeyguard() {
        keyguard.value = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    private fun imePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    /** The phone's own dialer, SMS app and keyboard, whatever they are on this device. */
    private fun extras(): Set<String> = buildSet {
        runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(this@PenaltyLockService) }.getOrNull()?.let(::add)
        imePackage()?.let(::add)
    }

    private fun show() {
        if (overlay != null) return
        val g = application.graph
        val view = LockOverlayView(this)
        view.setContent {
            val st by g.lock.state.collectAsState()
            val allow by remember { g.settings.flow.map { it.lock.allow } }.collectAsState(emptySet())
            val apps = remember(allow) { appsFor(allow) }
            LockScreen(st, apps, actions, clock = g.time::now)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess {
                view.resume()
                overlay = view
                _covering.value = true
            }
    }

    private fun hide() {
        val v = overlay ?: return
        overlay = null
        _covering.value = false
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        v.destroy()
    }

    private fun appsFor(allow: Set<String>): List<LockApp> {
        val pm = packageManager
        return allow.mapNotNull { pkg ->
            runCatching { LockApp(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
        }.sortedBy { it.label }
    }

    private fun launch(intent: Intent?) {
        intent ?: return
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val actions = object : LockActions {
        override fun phone() = launch(Intent(Intent.ACTION_DIAL))
        override fun messages() = launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING))
        override fun openSystem() = launch(packageManager.getLaunchIntentForPackage(packageName))
        override fun open(pkg: String) = launch(packageManager.getLaunchIntentForPackage(pkg))
        override suspend fun override(input: String) = application.graph.lock.override(input)
    }

    companion object {
        private val _running = MutableStateFlow(false)
        /** True while Android has the service bound. */
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _covering = MutableStateFlow(false)
        /** True while the lock screen is drawn over another app. */
        val covering: StateFlow<Boolean> = _covering.asStateFlow()
    }
}
