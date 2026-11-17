package com.example.sshcheck.domain.repository

import com.example.sshcheck.domain.model.Server
import kotlinx.coroutines.flow.StateFlow

interface ServerRepository {
    val servers: StateFlow<List<Server>>
    suspend fun load()
    suspend fun save(server: Server)
    suspend fun delete(id: String)
    suspend fun pinHostKey(id: String, encodedKey: String)
}
