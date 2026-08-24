package com.john.assistant.integrations.huggingface

import com.john.assistant.integrations.SecureTokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HuggingFaceAuth @Inject constructor(
    private val tokenStore: SecureTokenStore,
) {
    fun token(): String? = tokenStore.get(TOKEN_KEY)?.takeIf { it.isNotBlank() }

    fun setToken(token: String) {
        val value = token.trim()
        if (value.isBlank()) tokenStore.remove(TOKEN_KEY) else tokenStore.put(TOKEN_KEY, value)
    }

    companion object {
        const val TOKEN_KEY = "huggingface_api_token"
    }
}
