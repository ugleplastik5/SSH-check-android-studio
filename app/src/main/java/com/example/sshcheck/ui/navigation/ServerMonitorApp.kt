@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sshcheck.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sshcheck.ui.dashboard.DashboardViewModel
import com.example.sshcheck.ui.dashboard.MetricSample
import com.example.sshcheck.ui.screens.AddServerScreen
import com.example.sshcheck.ui.screens.DashboardScreen
import com.example.sshcheck.ui.screens.EmptyDashboard
import com.example.sshcheck.ui.screens.ServerListScreen
import com.example.sshcheck.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

private enum class Screen { SERVERS, ADD, DASHBOARD, SETTINGS }

@Composable
fun ServerMonitorApp(vm: DashboardViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.SERVERS) }
    val servers by vm.servers.collectAsState()
    val selected by vm.selected.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val history by vm.history.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val error by vm.error.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message!!)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(screen == Screen.SERVERS, { screen = Screen.SERVERS }, icon = {}, label = { Text("Серверы") })
                NavigationBarItem(screen == Screen.SETTINGS, { screen = Screen.SETTINGS }, icon = {}, label = { Text("Настройки") })
            }
        }
    ) { padding ->
        when (screen) {
            Screen.SERVERS -> ServerListScreen(servers, Modifier.padding(padding),
                onOpen = { vm.select(it); screen = Screen.DASHBOARD },
                onAdd = { screen = Screen.ADD }, onDelete = vm::remove)
            Screen.ADD -> AddServerScreen(Modifier.padding(padding), onBack = { screen = Screen.SERVERS },
                onSave = { name, host, port, user, key, pass ->
                    vm.addServer(name, host, port, user, key, pass)
                    screen = Screen.DASHBOARD
                })
            Screen.DASHBOARD -> selected?.let {
                DashboardScreen(it, metrics, history, busy, error, Modifier.padding(padding), vm::refresh,
                    onBack = { screen = Screen.SERVERS })
            } ?: EmptyDashboard(Modifier.padding(padding), onBack = { screen = Screen.SERVERS })
            Screen.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }

    hostKeyPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = vm::dismissHostKey,
            title = { Text(if (prompt.changed) "Сервер предъявил другой ключ" else "Проверьте ключ сервера") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Fingerprint сервера:\n${prompt.fingerprint}")
                    Text(
                        if (prompt.changed) {
                            "\nЭтот сервер уже имеет сохранённый ключ другого типа. Сверьте новый fingerprint с администратором или через доверенный компьютер перед добавлением."
                        } else {
                            "\nСверьте fingerprint с администратором или через доверенный компьютер. Для SHA256 используйте: ssh-keygen -E sha256 -lf /etc/ssh/ssh_host_ed25519_key.pub. При несовпадении не принимайте ключ."
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = vm::acceptHostKey) {
                    Text(if (prompt.changed) "Сверил, добавить ключ" else "Я сверил, доверять")
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissHostKey) { Text("Отмена") } }
        )
    }
}
