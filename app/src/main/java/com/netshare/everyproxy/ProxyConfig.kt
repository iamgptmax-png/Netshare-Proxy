package com.netshare.everyproxy

data class ProxyConfig(
    val bindAddress: String = "0.0.0.0",
    val httpEnabled: Boolean = true,
    val httpPort: Int = 8080,
    val socksEnabled: Boolean = true,
    val socksPort: Int = 1080,
    val authEnabled: Boolean = false,
    val username: String = "",
    val password: String = ""
)
