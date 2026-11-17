package com.example.sshcheck.domain.model

data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val privateKey: ByteArray,
    val passphrase: String?,
    val pinnedHostKey: String? = null
)
