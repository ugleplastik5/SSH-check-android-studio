@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.sshcheck.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sshcheck.data.ssh.ServerMetrics
import com.example.sshcheck.ui.dashboard.MetricSample
import com.example.sshcheck.domain.model.Server

@Composable
fun ServerListScreen(
    servers: List<Server>,
    modifier: Modifier = Modifier,
    onOpen: (Server) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Server) -> Unit
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("SSH-серверы") }) },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Text("+") } }) { p ->
        if (servers.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(p).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Пока нет серверов", style = MaterialTheme.typography.titleLarge)
                Text("Добавьте адрес, SSH-пользователя и приватный ключ.")
                Button(onClick = onAdd) { Text("Добавить сервер") }
            }
        } else {
            LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(servers, key = { it.id }) { server ->
                    ElevatedCard(onClick = { onOpen(server) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleMedium)
                                Text("${server.username}@${server.host}:${server.port}")
                                Text(if (server.pinnedHostKey == null) "Нужно подтвердить ключ сервера" else "Ключ сервера закреплён",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDelete(server) }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddServerScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSave: (String, String, Int, String, ByteArray, String?) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("Мой сервер") }
    var host by remember { mutableStateOf("31.77.251.158") }
    var username by remember { mutableStateOf("xinalikai") }
    var port by remember { mutableStateOf("22") }
    var passphrase by remember { mutableStateOf("") }
    var keyData by remember { mutableStateOf<ByteArray?>(null) }
    var keyLabel by remember { mutableStateOf("Файл ключа не выбран") }
    var localError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Не удалось прочитать выбранный файл")
            }.onSuccess { bytes ->
                keyData = bytes
                keyLabel = uri.lastPathSegment?.substringAfterLast('/') ?: "Ключ выбран"
                localError = null
            }.onFailure { localError = it.message }
        }
    }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Добавить сервер") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }) }) { p ->
        LazyColumn(Modifier.padding(p).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Заполнены данные из команды ssh, которую вы прислали. Путь ~/.ssh/id_ed25519 нужно выбрать на телефоне или планшете.") }
            item { OutlinedTextField(name, { name = it }, label = { Text("Название сервера") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(host, { host = it }, label = { Text("IP или Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(port, { port = it }, label = { Text("Порт SSH") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedTextField(username, { username = it }, label = { Text("SSH-пользователь") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать приватный ключ")
                }
            }
            item { Text(keyLabel, style = MaterialTheme.typography.bodySmall) }
            item { OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase ключа (если есть)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation()) }
            item { Text("Пароль сервера не нужен, если SSH настроен на вход по ключу. Приватный ключ шифруется на устройстве.") }
            localError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Button(onClick = {
                    val parsedPort = port.toIntOrNull()
                    if (parsedPort == null) localError = "Порт должен быть числом"
                    else if (keyData == null) localError = "Сначала выберите файл приватного ключа"
                    else onSave(name, host, parsedPort, username, keyData!!, passphrase.ifBlank { null })
                }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить и проверить SSH") }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    server: Server,
    metrics: ServerMetrics,
    history: List<MetricSample>,
    busy: Boolean,
    error: String?,
    modifier: Modifier,
    refresh: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(server.name) }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Серверы") }
    }, actions = { TextButton(onClick = refresh) { Text("Обновить") } }) }) { p ->
        LazyColumn(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("${server.username}@${server.host}:${server.port}") }
            error?.let { message ->
                item {
                    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item { Text("Uptime: ${metrics.uptime}\nLoad average: ${metrics.load}") }
            item {
                MetricHistoryCard(
                    title = "Процессор (CPU)",
                    currentPercent = metrics.cpuPercent,
                    samples = history.map { it.cpuPercent },
                    lineColor = Color(0xFF67B5FF),
                    detail = "Текущая загрузка процессора"
                )
            }
            item {
                val ramPercent = if (metrics.ramTotalMb == 0L) 0f
                    else metrics.ramUsedMb * 100f / metrics.ramTotalMb
                MetricHistoryCard(
                    title = "Память (RAM)",
                    currentPercent = ramPercent,
                    samples = history.map { it.ramPercent },
                    lineColor = Color(0xFFB49AFF),
                    detail = "${metrics.ramUsedMb} / ${metrics.ramTotalMb} MB"
                )
            }
            item {
                Text(
                    "Сеть · ↓ ${formatRate(metrics.networkReceiveBytesPerSecond)} · ↑ ${formatRate(metrics.networkSendBytesPerSecond)}",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            items(metrics.disks) { MetricCard("Диск ${it.mount}", it.usedPercent.toFloat(), "${it.usedPercent}% · ${it.size}") }
            item { Text("Топ процессов", style = MaterialTheme.typography.titleLarge) }
            items(metrics.processes) { Text("${it.name} · CPU ${it.cpu}% · RAM ${it.memory}%") }
            if (metrics.processes.isEmpty()) item { Text("Метрики появятся после подключения к серверу.") }
        }
    }
}

@Composable
private fun MetricHistoryCard(
    title: String,
    currentPercent: Float,
    samples: List<Float>,
    lineColor: Color,
    detail: String
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF111923)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, color = Color(0xFFE7EDF5), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(
                    "${currentPercent.coerceIn(0f, 100f).toInt()}%",
                    color = lineColor,
                    fontSize = 34.sp,
                    lineHeight = 38.sp
                )
                Spacer(Modifier.width(12.dp))
                Text(detail, color = Color(0xFFAAB6C5), modifier = Modifier.padding(bottom = 5.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(132.dp)) {
                val gridColor = Color(0xFF2A3542)
                for (row in 0..4) {
                    val y = size.height * row / 4f
                    drawLine(gridColor, androidx.compose.ui.geometry.Offset(0f, y),
                        androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }

                if (samples.isNotEmpty()) {
                    val values = samples.takeLast(60)
                    fun xAt(index: Int) = if (values.size == 1) size.width
                        else size.width * index / (values.lastIndex.toFloat())
                    fun yAt(value: Float) = size.height * (1f - value.coerceIn(0f, 100f) / 100f)

                    val graph = Path()
                    values.forEachIndexed { index, value ->
                        val x = xAt(index)
                        val y = yAt(value)
                        if (index == 0) graph.moveTo(x, y) else graph.lineTo(x, y)
                    }

                    val fill = Path().apply {
                        moveTo(xAt(0), size.height)
                        values.forEachIndexed { index, value -> lineTo(xAt(index), yAt(value)) }
                        lineTo(xAt(values.lastIndex), size.height)
                        close()
                    }
                    drawPath(fill, lineColor.copy(alpha = 0.16f))
                    drawPath(graph, lineColor, style = Stroke(width = 2.5.dp.toPx()))
                    if (values.size == 1) {
                        drawCircle(lineColor, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(xAt(0), yAt(values[0])))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("30 мин назад", color = Color(0xFF91A0B1), style = MaterialTheme.typography.labelSmall)
                Text("Сейчас", color = Color(0xFF91A0B1), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: Float, caption: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { (value / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(caption)
        }
    }
}

private fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond >= 1024 * 1024 -> "${"%.2f".format(bytesPerSecond / (1024 * 1024))} MB/s"
    bytesPerSecond >= 1024 -> "${"%.1f".format(bytesPerSecond / 1024)} KB/s"
    else -> "${bytesPerSecond.toLong()} B/s"
}

@Composable
fun EmptyDashboard(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.padding(24.dp)) {
        Text("Сервер не выбран")
        TextButton(onClick = onBack) { Text("К списку серверов") }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Text("Автообновление дашборда: каждые 30 секунд")
        Text("SSH-ключ и закреплённые host key зашифрованы Android Keystore.")
    }
}
