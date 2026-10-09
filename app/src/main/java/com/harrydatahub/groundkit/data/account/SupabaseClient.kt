package com.harrydatahub.groundkit.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The parts of Supabase Auth and its REST API that GroundKit accounts use, over
 * HttpURLConnection (no SDK). The project URL and publishable key are build config
 * (SUPABASE_URL, SUPABASE_KEY); without them accounts are hidden.
 */
data class SupabaseConfig(val url: String, val key: String) {
    companion object {
        /** Where provider sign-in returns to the app (MainActivity's intent filter). */
        const val CALLBACK_URL = "groundkit://auth-callback"
        /** Email links (confirm address, reset password) open the web dashboard, which handles them. */
        const val WEB_URL = "https://motherduck-aviation-data-analysis.vercel.app/"

        fun of(url: String, key: String): SupabaseConfig? =
            if (url.startsWith("http") && key.isNotBlank()) SupabaseConfig(url.trimEnd('/'), key.trim()) else null
    }
}

data class AuthUser(val id: String, val email: String?, val providers: List<String>)

data class AuthSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val user: AuthUser) {
    /** Refresh a minute early so a request does not race the expiry. */
    fun needsRefresh(now: Long = System.currentTimeMillis()) = expiresAt - now < 60_000

    fun toJson(): JSONObject = JSONObject()
        .put("access_token", accessToken).put("refresh_token", refreshToken).put("expires_at", expiresAt / 1000.0)
        .put("user", JSONObject().put("id", user.id).put("email", user.email ?: JSONObject.NULL)
            .put("identities", JSONArray(user.providers.map { JSONObject().put("provider", it) })))
}

class SupabaseException(val status: Int, val code: String?, message: String) : IOException(message) {
    /** The session is no longer valid (deleted account, revoked refresh token). */
    val isSignedOut: Boolean
        get() = status == 401 || code in setOf("refresh_token_not_found", "refresh_token_already_used", "user_not_found", "session_not_found")
}

/** PKCE for provider sign-in: a random verifier and its S256 challenge. */
class Pkce(val verifier: String = random()) {
    val challenge: String = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    companion object {
        fun random(bytes: Int = 32): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let(::base64Url)
        private fun base64Url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

class SupabaseClient(private val config: SupabaseConfig) {

    // ---- Auth ------------------------------------------------------------------------------

    /** The session, or null when the address needs confirming first (the default). */
    suspend fun signUp(email: String, password: String, displayName: String?): AuthSession? {
        val body = JSONObject().put("email", email).put("password", password)
        if (!displayName.isNullOrBlank()) body.put("data", JSONObject().put("display_name", displayName))
        val res = send("POST", "auth/v1/signup", mapOf("redirect_to" to SupabaseConfig.WEB_URL), body = body)
        return if (res.has("access_token")) parseSession(res) else null
    }

    suspend fun signIn(email: String, password: String): AuthSession =
        parseSession(send("POST", "auth/v1/token", mapOf("grant_type" to "password"),
            body = JSONObject().put("email", email).put("password", password)))

    /** The page that starts a provider's sign-in; it ends at CALLBACK_URL?code=... */
    fun authorizeUrl(provider: String, pkce: Pkce, scopes: String? = null): String {
        val q = linkedMapOf(
            "provider" to provider,
            "redirect_to" to SupabaseConfig.CALLBACK_URL,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "s256",
        )
        if (scopes != null) q["scopes"] = scopes
        return "${config.url}/auth/v1/authorize?" + query(q)
    }

    suspend fun exchange(code: String, verifier: String): AuthSession =
        parseSession(send("POST", "auth/v1/token", mapOf("grant_type" to "pkce"),
            body = JSONObject().put("auth_code", code).put("code_verifier", verifier)))

    suspend fun refresh(refreshToken: String): AuthSession =
        parseSession(send("POST", "auth/v1/token", mapOf("grant_type" to "refresh_token"),
            body = JSONObject().put("refresh_token", refreshToken)))

    suspend fun resetPassword(email: String) {
        send("POST", "auth/v1/recover", mapOf("redirect_to" to SupabaseConfig.WEB_URL), body = JSONObject().put("email", email))
    }

    suspend fun signOut(token: String) {
        send("POST", "auth/v1/logout", token = token)
    }

    // ---- Data ------------------------------------------------------------------------------

    suspend fun mergeSettings(body: JSONObject, token: String): JSONObject =
        send("POST", "rest/v1/rpc/merge_settings", token = token, body = body,
            headers = mapOf("Accept" to "application/vnd.pgrst.object+json"))

    suspend fun displayName(userId: String, token: String): String? {
        val rows = sendRaw("GET", "rest/v1/profiles", mapOf("select" to "display_name", "id" to "eq.$userId"), token = token)
        return JSONArray(rows).optJSONObject(0)?.let { if (it.isNull("display_name")) null else it.optString("display_name") }
    }

    /** An RPC rather than a PATCH, which HttpURLConnection does not support. Empty clears it. */
    suspend fun setDisplayName(name: String, token: String) {
        send("POST", "rest/v1/rpc/set_display_name", token = token, body = JSONObject().put("name", name))
    }

    suspend fun deleteAccount(token: String) {
        send("POST", "rest/v1/rpc/delete_own_account", token = token, body = JSONObject())
    }

    // ---- Plumbing --------------------------------------------------------------------------

    private suspend fun send(
        method: String, path: String, query: Map<String, String> = emptyMap(), token: String? = null,
        body: JSONObject? = null, headers: Map<String, String> = emptyMap(),
    ): JSONObject = sendRaw(method, path, query, token, body, headers).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private suspend fun sendRaw(
        method: String, path: String, query: Map<String, String> = emptyMap(), token: String? = null,
        body: JSONObject? = null, headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val url = "${config.url}/$path" + if (query.isEmpty()) "" else "?" + query(query)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.useCaches = false
            // The publishable key identifies the project; it is not a bearer token.
            conn.setRequestProperty("apikey", config.key)
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().decodeToString() } ?: ""
            if (status !in 200..299) throw error(status, text)
            text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseSession(o: JSONObject, now: Long = System.currentTimeMillis()): AuthSession {
            val expiresAt = if (o.has("expires_at")) (o.getDouble("expires_at") * 1000).toLong()
            else now + o.optLong("expires_in", 3600) * 1000
            return AuthSession(o.getString("access_token"), o.getString("refresh_token"), expiresAt, parseUser(o.getJSONObject("user")))
        }

        fun parseUser(u: JSONObject): AuthUser {
            val providers = u.optJSONArray("identities")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("provider") } }
                ?: u.optJSONObject("app_metadata")?.optJSONArray("providers")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList()
            return AuthUser(u.getString("id"), u.optString("email").takeIf { it.isNotEmpty() && !u.isNull("email") }, providers.distinct())
        }

        /** The code from the provider callback, or the provider's error. */
        fun codeFromCallback(uri: String): String {
            val params = (uri.substringAfter('?', "").substringBefore('#') + "&" + uri.substringAfter('#', ""))
                .split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            params["code"]?.let { return it }
            throw SupabaseException(400, params["error"], params["error_description"] ?: "Sign-in did not finish.")
        }

        /** Auth errors are {"error_code", "msg"}; REST errors are {"code", "message"}. */
        fun error(status: Int, text: String): SupabaseException {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            fun str(vararg keys: String) = keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotEmpty() } }
            return SupabaseException(status, str("error_code", "code", "error"), friendly(str("msg", "message", "error_description", "error") ?: "HTTP $status"))
        }

        /** Supabase's messages are written for developers; a few read better reworded. */
        fun friendly(message: String): String {
            val m = message.lowercase()
            return when {
                "invalid login credentials" in m -> "That email and password do not match an account."
                "email not confirmed" in m -> "Confirm your email first: open the link we sent you."
                "user already registered" in m -> "That email already has an account. Sign in instead."
                else -> message
            }
        }

        private fun query(q: Map<String, String>) =
            q.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
