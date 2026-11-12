package org.dandeliondaily.sundial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.dandeliondaily.sundial.card.CardEngine
import org.dandeliondaily.sundial.card.CardStore
import org.dandeliondaily.sundial.ui.SundialApp

/** The one activity. Screens are Compose; navigation is the ViewModel's state. */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SundialApp(vm) }
        handle(intent)
        addCaptureShortcut()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
        // Opening the app brings the card back if it was swiped away, and refreshes it.
        CardStore(this).unhide()
        CardEngine.kick(this, "app opened")
    }

    /** The card needs notification permission (Android 13+). Asked once the phone is signed in. */
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CardEngine.kick(this, "notification permission")
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_CAPTURE) vm.requestCapture()
        val id = intent.getIntExtra(EXTRA_ACTION_ID, 0)
        if (id > 0) {
            intent.removeExtra(EXTRA_ACTION_ID)
            vm.openAction(id)
        }
    }

    /** Long-press the app icon → Capture (P-17). */
    private fun addCaptureShortcut() {
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val shortcut = ShortcutInfo.Builder(this, "capture")
            .setShortLabel("Capture")
            .setLongLabel("Capture an action")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
            .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_CAPTURE))
            .build()
        runCatching { sm.dynamicShortcuts = listOf(shortcut) }
    }

    companion object {
        const val ACTION_CAPTURE = "org.dandeliondaily.sundial.CAPTURE"
        const val EXTRA_ACTION_ID = "actionId"
    }
}
