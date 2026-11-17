package com.example.sshcheck.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sshcheck.data.local.EncryptedServerRepository
import com.example.sshcheck.data.ssh.ServerMetrics
import com.example.sshcheck.data.ssh.SshFailure
import com.example.sshcheck.data.ssh.SshManager
import com.example.sshcheck.domain.model.Server
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class HostKeyPrompt(
    val serverId: String,
    val fingerprint: String,
    val encodedKey: String,
    val changed: Boolean
)

data class MetricSample(
    val capturedAt: Long,
    val cpuPercent: Float,
    val ramPercent: Float
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EncryptedServerRepository(application)
    private val sshManager = SshManager()

    val servers = repository.servers
    private val _selected = MutableStateFlow<Server?>(null)
    val selected = _selected.asStateFlow()
    private val _metrics = MutableStateFlow(ServerMetrics())
    val metrics = _metrics.asStateFlow()
    private val _history = MutableStateFlow<List<MetricSample>>(emptyList())
    val history = _history.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _hostKeyPrompt = MutableStateFlow<HostKeyPrompt?>(null)
    val hostKeyPrompt = _hostKeyPrompt.asStateFlow()
    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated = _lastUpdated.asStateFlow()
    private var previousMetricsAt: Long? = null

    init {
        viewModelScope.launch {
            repository.load()
            while (isActive) {
                delay(30_000)
                if (_selected.value != null) refresh()
            }
        }
    }

    fun addServer(name: String, host: String, port: Int, username: String, key: ByteArray, passphrase: String?) {
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank()) { "Введите название сервера" }
                require(host.isNotBlank()) { "Введите адрес или IP сервера" }
                require(port in 1..65535) { "Порт должен быть от 1 до 65535" }
                require(username.isNotBlank()) { "Введите SSH-пользователя" }
                require(key.isNotEmpty()) { "Выберите файл приватного ключа" }
                val server = Server(UUID.randomUUID().toString(), name.trim(), host.trim(), port, username.trim(),
                    key, passphrase?.takeIf(String::isNotBlank))
                repository.save(server)
                _selected.value = server
                testConnection(server)
            }.onFailure { _message.value = it.message }
        }
    }

    fun select(server: Server) {
        _selected.value = server
        _metrics.value = ServerMetrics()
        _history.value = emptyList()
        previousMetricsAt = null
        _lastUpdated.value = null
        _message.value = null
        _error.value = null
        refresh()
    }

    fun remove(server: Server) {
        viewModelScope.launch {
            repository.delete(server.id)
            if (_selected.value?.id == server.id) _selected.value = null
        }
    }

    fun refresh() {
        val server = _selected.value ?: return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { sshManager.collect(server) }
                .onSuccess { latest ->
                    val now = System.currentTimeMillis()
                    val previous = _metrics.value
                    val elapsedSeconds = previousMetricsAt?.let { (now - it) / 1_000.0 } ?: 0.0
                    _metrics.value = latest.copy(
                        networkReceiveBytesPerSecond = if (elapsedSeconds > 0) {
                            ((latest.networkReceivedBytes - previous.networkReceivedBytes).coerceAtLeast(0) / elapsedSeconds)
                        } else 0.0,
                        networkSendBytesPerSecond = if (elapsedSeconds > 0) {
                            ((latest.networkSentBytes - previous.networkSentBytes).coerceAtLeast(0) / elapsedSeconds)
                        } else 0.0
                    )
                    previousMetricsAt = now
                    _lastUpdated.value = now
                    _history.value = (_history.value + MetricSample(
                        capturedAt = now,
                        cpuPercent = latest.cpuPercent,
                        ramPercent = if (latest.ramTotalMb > 0) {
                            latest.ramUsedMb * 100f / latest.ramTotalMb
                        } else 0f
                    )).takeLast(60)
                    _error.value = null
                }
                .onFailure { handleFailure(server, it) }
            _busy.value = false
        }
    }

    fun testConnection() {
        _selected.value?.let(::testConnection)
    }

    private fun testConnection(server: Server) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            runCatching { sshManager.testConnection(server) }
                .onSuccess {
                    _error.value = null
                    _message.value = "SSH подключён. Загружаю доступные метрики…"
                    if (_selected.value?.id == server.id) refresh()
                }
                .onFailure { handleFailure(server, it) }
            _busy.value = false
        }
    }

    fun acceptHostKey() {
        val prompt = _hostKeyPrompt.value ?: return
        viewModelScope.launch {
            repository.pinHostKey(prompt.serverId, prompt.encodedKey)
            val updated = repository.servers.value.firstOrNull { it.id == prompt.serverId } ?: return@launch
            _selected.value = updated
            _hostKeyPrompt.value = null
            testConnection(updated)
        }
    }

    fun dismissHostKey() {
        _hostKeyPrompt.value = null
        _message.value = "Подключение отменено: ключ сервера не принят."
    }

    fun clearMessage() { _message.value = null }

    private fun handleFailure(server: Server, error: Throwable) {
        when (error) {
            is SshFailure.HostKeyConfirmation -> {
                _hostKeyPrompt.value = HostKeyPrompt(server.id, error.fingerprint, error.encodedKey, changed = false)
            }
            is SshFailure.HostKeyChanged -> {
                _hostKeyPrompt.value = HostKeyPrompt(server.id, error.fingerprint, error.encodedKey, changed = true)
            }
            else -> {
                val text = error.message ?: "Не удалось подключиться к серверу"
                _error.value = text
                _message.value = text
            }
        }
    }
}
