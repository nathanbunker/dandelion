package org.dandeliondaily.sundial.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.dandeliondaily.sundial.AppViewModel
import org.dandeliondaily.sundial.BuildConfig
import org.dandeliondaily.sundial.Screen
import org.dandeliondaily.sundial.api.DandelionApi

@Composable
fun SundialApp(vm: AppViewModel) {
    SundialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) {
                val screen by vm.screen.collectAsState()
                when (val s = screen) {
                    Screen.Starting -> Centered { CircularProgressIndicator() }
                    is Screen.SignIn -> SignInScreen(s, vm)
                    is Screen.Pending -> PendingScreen(s, vm)
                    is Screen.NeedsUpdate -> Centered {
                        Text("Dandelion needs an update", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("This version of Sundial needs API level ${AppViewModel.MIN_API_LEVEL}; the server has ${s.serverLevel}.")
                    }
                    is Screen.Ready -> {
                        val detail by vm.detail.collectAsState()
                        val d = detail
                        if (d != null) {
                            BackHandler { vm.closeAction() }
                            ActionDetailScreen(d, vm)
                        } else {
                            TodayScreen(s.me, vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun SignInScreen(s: Screen.SignIn, vm: AppViewModel) {
    var server by rememberSaveable { mutableStateOf(s.server) }
    var username by rememberSaveable { mutableStateOf(s.username) }
    var password by remember { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(vm.defaultDeviceName()) }
    val submit = { if (!s.busy) vm.signIn(server, username, password, deviceName) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sundial", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("Sign in with your Dandelion account. You'll approve this phone on your computer next.")

        if (BuildConfig.ALLOW_LOCAL) {
            Text("Server", style = MaterialTheme.typography.labelLarge)
            ServerChoice("Local (this PC, over USB)", DandelionApi.LOCAL, server) { server = it }
            ServerChoice("Production (dandelion-daily.org)", DandelionApi.PRODUCTION, server) { server = it }
        }

        OutlinedTextField(
            value = username, onValueChange = { username = it }, label = { Text("Username") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        OutlinedTextField(
            value = deviceName, onValueChange = { deviceName = it }, label = { Text("Name for this phone") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Shown on Dandelion's Devices page.") },
        )
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = submit, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.busy) "Signing in…" else "Sign in")
        }
    }
}

@Composable
private fun ServerChoice(label: String, url: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = url == selected, onClick = { onSelect(url) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = url == selected, onClick = { onSelect(url) })
        Text(label)
    }
}

@Composable
private fun PendingScreen(s: Screen.Pending, vm: AppViewModel) {
    Centered {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("On your computer, open Dandelion, go to Settings → Devices, and approve “${s.deviceName}”.")
        Spacer(Modifier.height(8.dp))
        Text(
            DandelionApi.apiRoot(s.server).removeSuffix("api/") + "DevicesServlet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        Text("This screen moves on by itself once you do.", style = MaterialTheme.typography.bodySmall)
        if (s.offline) {
            Spacer(Modifier.height(12.dp))
            Text("Can't reach Dandelion · retrying", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { vm.signOut() }) { Text("Start over") }
    }
}
