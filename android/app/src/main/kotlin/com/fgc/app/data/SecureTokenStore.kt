package com.fgc.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fgc.core.TokenStore
import com.fgc.core.Tokens

/** 토큰을 Android Keystore 기반 키로 암호화해 저장 (평문 저장 금지). */
class SecureTokenStore(context: Context) : TokenStore {
    private val prefs = EncryptedSharedPreferences.create(
        context, "fgc_tokens",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    override val accessToken: String? get() = prefs.getString("access", null)
    override val refreshToken: String? get() = prefs.getString("refresh", null)
    override fun save(tokens: Tokens) { prefs.edit().putString("access", tokens.accessToken).putString("refresh", tokens.refreshToken).apply() }
    override fun clear() { prefs.edit().clear().apply() }
}
