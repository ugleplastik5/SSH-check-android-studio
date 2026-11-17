package com.example.sshcheck.data.ssh

import com.example.sshcheck.domain.model.Server
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.Base64

sealed class SshFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(cause: Throwable? = null) : SshFailure("Превышено время ожидания подключения", cause)
    class Authentication(cause: Throwable? = null) : SshFailure("Не удалось войти. Проверьте логин, ключ и passphrase.", cause)
    class Unreachable(cause: Throwable? = null) : SshFailure("Сервер недоступен. Проверьте адрес, порт и сеть.", cause)
    class HostKeyConfirmation(val fingerprint: String, val encodedKey: String) :
        SshFailure("Подтвердите fingerprint сервера: $fingerprint")
    class HostKeyChanged(val fingerprint: String, val encodedKey: String) :
        SshFailure("Обнаружен другой host key: $fingerprint")
    class Command(message: String) : SshFailure(message)
}

/** SSH-доступ с проверкой и фиксацией ключа сервера после явного подтверждения пользователем. */
class SshManager {
    suspend fun execute(server: Server, command: String, timeoutMs: Long = 12_000): String =
        withTimeout(timeoutMs + 2_000) {
            withContext(Dispatchers.IO) {
                val jsch = JSch()
                jsch.addIdentity("server-private-key", server.privateKey, null,
                    server.passphrase?.toByteArray(Charsets.UTF_8))
                val pinRepository = PinnedHostKeyRepository(jsch, server.pinnedHostKey, server.host)
                jsch.hostKeyRepository = pinRepository
                val session = try {
                    jsch.getSession(server.username, server.host, server.port).apply {
                        setConfig("StrictHostKeyChecking", "yes")
                        connect(timeoutMs.toInt())
                    }
                } catch (e: JSchException) {
                    pinRepository.changedKey?.let { candidate ->
                        throw SshFailure.HostKeyChanged(candidate.fingerprint, candidate.encoded)
                    }
                    pinRepository.untrustedKey?.let { candidate ->
                        throw SshFailure.HostKeyConfirmation(candidate.fingerprint, candidate.encoded)
                    }
                    val message = e.message.orEmpty()
                    when {
                        message.contains("auth fail", true) || message.contains("decrypt", true) ->
                            throw SshFailure.Authentication(e)
                        message.contains("timeout", true) || e.cause is SocketTimeoutException -> throw SshFailure.Timeout(e)
                        e.cause is ConnectException || message.contains("connect", true) -> throw SshFailure.Unreachable(e)
                        else -> throw SshFailure.Unreachable(e)
                    }
                }
                try {
                    runCommand(session, command, timeoutMs)
                } finally {
                    session.disconnect()
                }
            }
        }

    suspend fun testConnection(server: Server): String = execute(server, "printf 'SSH_OK'")

    suspend fun collect(server: Server): ServerMetrics {
        val uptime = optionalCommand(server, "uptime")
        val memory = optionalCommand(server, "free -m")
        val disks = optionalCommand(server, "df -P -h")
        val processes = optionalCommand(server, "ps -eo pid,comm,%cpu,%mem --sort=-%cpu | head -n 6")
        val cpu = optionalCommand(server, "top -bn1 | head -n 5")
        val network = optionalCommand(server, "cat /proc/net/dev")
        return MetricParser.parse(uptime, memory, disks, processes, cpu, network)
    }

    private suspend fun optionalCommand(server: Server, command: String): String =
        try {
            execute(server, command)
        } catch (_: SshFailure.Command) {
            // Отсутствие одной системной утилиты не должно скрывать остальные метрики.
            ""
        }

    private fun runCommand(session: Session, command: String, timeoutMs: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val output = channel.inputStream
        channel.connect(timeoutMs.toInt())
        val result = output.readBytes().toString(Charsets.UTF_8).trim()
        val status = channel.exitStatus
        channel.disconnect()
        if (status != 0) throw SshFailure.Command("Команда завершилась с кодом $status")
        return result
    }

    private data class Candidate(val fingerprint: String, val encoded: String, val hostKey: HostKey)

    private class PinnedHostKeyRepository(
        private val jsch: JSch,
        private val pin: String?,
        private val host: String
    ) : HostKeyRepository {
        var untrustedKey: Candidate? = null
            private set
        var changedKey: Candidate? = null
            private set

        override fun check(host: String, key: ByteArray): Int {
            val encoded = Base64.getEncoder().encodeToString(key)
            val hostKey = HostKey(host, key)
            val candidate = Candidate(hostKey.getFingerPrint(jsch), encoded, hostKey)
            val trustedPins = pin.orEmpty().split(',').filter(String::isNotBlank).toSet()
            return when {
                trustedPins.isEmpty() -> {
                    untrustedKey = candidate
                    HostKeyRepository.NOT_INCLUDED
                }
                encoded in trustedPins -> HostKeyRepository.OK
                else -> {
                    changedKey = candidate
                    HostKeyRepository.CHANGED
                }
            }
        }

        override fun add(hostkey: HostKey, userinfo: com.jcraft.jsch.UserInfo?) = Unit
        override fun remove(host: String, type: String?) = Unit
        override fun remove(host: String, type: String, key: ByteArray) = Unit
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
        override fun getKnownHostsRepositoryID(): String = "Android app pin for $host"
    }
}

data class DiskMetric(val mount: String, val usedPercent: Int, val size: String)
data class ProcessMetric(val pid: String, val name: String, val cpu: Double, val memory: Double)
data class ServerMetrics(
    val uptime: String = "—",
    val load: String = "—",
    val cpuPercent: Float = 0f,
    val ramUsedMb: Long = 0,
    val ramTotalMb: Long = 0,
    val disks: List<DiskMetric> = emptyList(),
    val processes: List<ProcessMetric> = emptyList(),
    val networkReceivedBytes: Long = 0,
    val networkSentBytes: Long = 0,
    val networkReceiveBytesPerSecond: Double = 0.0,
    val networkSendBytesPerSecond: Double = 0.0
)

object MetricParser {
    fun parse(
        uptime: String,
        free: String,
        df: String,
        ps: String,
        top: String,
        network: String = ""
    ): ServerMetrics {
        val loads = Regex("""load average[s]?:\s*([0-9.,]+,?\s*[0-9.,]*,?\s*[0-9.,]*)""")
            .find(uptime)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val memory = free.lines().firstOrNull { it.trimStart().startsWith("Mem:") }
            ?.trim()?.split(Regex("\\s+"))
        val total = memory?.getOrNull(1)?.toLongOrNull() ?: 0
        val used = memory?.getOrNull(2)?.toLongOrNull() ?: 0
        val disks = df.lines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 6) null else DiskMetric(c.last(), c[4].trimEnd('%').toIntOrNull() ?: 0, c[1])
        }
        val processes = ps.lines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size < 4) null else ProcessMetric(c[0], c[1], c[2].toDoubleOrNull() ?: 0.0, c[3].toDoubleOrNull() ?: 0.0)
        }.take(5)
        val cpu = Regex("""(\d+(?:[.,]\d+)?)\s*id""").find(top)?.groupValues?.get(1)
            ?.replace(',', '.')?.toFloatOrNull()?.let { 100f - it } ?: 0f
        val counters = network.lineSequence().drop(2).mapNotNull { line ->
            val columns = line.split(':', limit = 2)
            val iface = columns.getOrNull(0)?.trim().orEmpty()
            val values = columns.getOrNull(1)?.trim()?.split(Regex("\\s+")).orEmpty()
            if (iface.isBlank() || iface == "lo" || values.size < 9) null
            else values[0].toLongOrNull() to values[8].toLongOrNull()
        }
        val received = counters.mapNotNull { it.first }.sum()
        val sent = counters.mapNotNull { it.second }.sum()
        return ServerMetrics(
            uptime = uptime.substringBefore("load average").trim(),
            load = loads,
            cpuPercent = cpu.coerceIn(0f, 100f),
            ramUsedMb = used,
            ramTotalMb = total,
            disks = disks,
            processes = processes,
            networkReceivedBytes = received,
            networkSentBytes = sent
        )
    }
}
