package org.dandeliondaily.sundial

import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.dandeliondaily.sundial.ui.SundialApp

/** The one activity. Screens are Compose; navigation is the ViewModel's state. */
class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Until the real Now card (step 5), clear any leftover step 0 spike card.
        getSystemService(NotificationManager::class.java).cancelAll()
        setContent { SundialApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
