package com.example.sshcheck.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.sshcheck.domain.model.Server
import com.example.sshcheck.domain.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Хранит серверы и приватные ключи в зашифрованном виде с ключом из Android Keystore. */
class EncryptedServerRepository(context: Context) : ServerRepository {
    private val preferences = context.getSharedPreferences("encrypted_servers", Context.MODE_PRIVATE)
    private val state = MutableStateFlow<List<Server>>(emptyList())
    override val servers: StateFlow<List<Server>> = state.asStateFlow()

    override suspend fun load() = withContext(Dispatchers.IO) {
        val packed = preferences.getString(DATA_KEY, null) ?: return@withContext
        runCatching {
            val raw = decrypt(Base64.decode(packed, Base64.NO_WRAP))
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val o = array.getJSONObject(index)
                Server(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"),
                    o.getString("username"), Base64.decode(o.getString("key"), Base64.NO_WRAP),
                    o.optString("passphrase").takeIf(String::isNotEmpty),
                    o.optString("pinned").takeIf(String::isNotEmpty))
            }
        }.onSuccess { state.value = it }
    }

    override suspend fun save(server: Server) = mutate { current ->
        current.filterNot { it.id == server.id } + server
    }

    override suspend fun delete(id: String) = mutate { current -> current.filterNot { it.id == id } }

    override suspend fun pinHostKey(id: String, encodedKey: String) = mutate { current ->
        current.map { server ->
            if (server.id != id) server else {
                val pins = server.pinnedHostKey.orEmpty().split(',').filter(String::isNotBlank).toMutableSet()
                pins += encodedKey
                server.copy(pinnedHostKey = pins.joinToString(","))
            }
        }
    }

    private suspend fun mutate(transform: (List<Server>) -> List<Server>) = withContext(Dispatchers.IO) {
        val next = transform(state.value)
        val array = JSONArray()
        next.forEach { server ->
            array.put(JSONObject().apply {
                put("id", server.id)
                put("name", server.name)
                put("host", server.host)
                put("port", server.port)
                put("username", server.username)
                put("key", Base64.encodeToString(server.privateKey, Base64.NO_WRAP))
                put("passphrase", server.passphrase.orEmpty())
                put("pinned", server.pinnedHostKey.orEmpty())
            })
        }
        val encrypted = encrypt(array.toString().toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(DATA_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Не удалось сохранить зашифрованные данные"
        }
        state.value = next
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(packed: ByteArray): String {
        require(packed.size > IV_SIZE) { "Повреждённые данные" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE)))
        return cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val DATA_KEY = "servers"
        const val KEY_ALIAS = "server-monitor-storage-key"
        const val IV_SIZE = 12
    }
}
