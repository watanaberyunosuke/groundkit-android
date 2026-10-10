package com.harrydatahub.groundkit.data.account

import android.content.Context
import com.harrydatahub.groundkit.BuildConfig
import com.harrydatahub.groundkit.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant

data class AccountState(
    val configured: Boolean = false,
    val user: AuthUser? = null,
    val displayName: String? = null,
    val syncing: Boolean = false,
    val syncError: String? = null,
)

// Apple and Microsoft are off for now: Sign in with Apple needs a paid Apple Developer
// Program membership.
enum class AuthProvider(val id: String, val label: String, val scopes: String? = null) {
    GOOGLE("google", "Continue with Google"),
}

/**
 * The optional GroundKit account for the whole app: sign-in state and settings sync.
 * Signed out, the app works as before. Signed in, the settings in [SettingsBridge] sync
 * with the iOS app and the web dashboard.
 */
object AccountManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AccountState())
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private var client: SupabaseClient? = null
    private lateinit var store: SessionStore
    private lateinit var settingsStore: SettingsStore
    private var session: AuthSession? = null
    private var synced = SyncedSettings()
    private var lastSeen: Map<SettingKey, Any> = emptyMap()
    private var applyingRemote = false
    private var syncJob: Job? = null
    private var lastSync = 0L
    private val syncLock = Mutex()
    private val refreshLock = Mutex()

    /** Call once from MainActivity; later calls do nothing. */
    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        store = SessionStore(app)
        settingsStore = SettingsStore.get(app)
        client = SupabaseConfig.of(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)?.let(::SupabaseClient)
        synced = store.settings
        session = if (client != null) store.session else null
        lastSeen = SettingsBridge.read(settingsStore.settings.value)
        _state.value = AccountState(configured = client != null, user = session?.user)
        scope.launch { settingsStore.settings.collect { onLocalSettings() } }
        if (session != null) scope.launch {
            loadProfile()
            sync()
        }
    }

    // ---- Sign in ----------------------------------------------------------------------------

    suspend fun signIn(email: String, password: String) {
        val c = client ?: return
        didSignIn(c.signIn(email.trim(), password))
    }

    /** True when signed in straight away; false when the address needs confirming first. */
    suspend fun signUp(email: String, password: String, displayName: String): Boolean {
        val c = client ?: return false
        val s = c.signUp(email.trim(), password, displayName.trim().ifEmpty { null }) ?: return false
        didSignIn(s)
        return true
    }

    /** The page to open in a Custom Tab; the verifier waits on disk for the callback. */
    fun providerUrl(provider: AuthProvider): String? {
        val c = client ?: return null
        val pkce = Pkce()
        store.verifier = pkce.verifier
        return c.authorizeUrl(provider.id, pkce, provider.scopes)
    }

    /** From MainActivity, for groundkit://auth-callback?code=... */
    fun finishProvider(callback: String, onError: (String) -> Unit) {
        val c = client ?: return
        val verifier = store.verifier ?: return onError("Sign-in expired. Try again.")
        store.verifier = null
        scope.launch {
            try {
                didSignIn(c.exchange(SupabaseClient.codeFromCallback(callback), verifier))
            } catch (e: IOException) {
                onError(e.message ?: "Sign-in did not finish.")
            }
        }
    }

    suspend fun resetPassword(email: String) {
        client?.resetPassword(email.trim())
    }

    suspend fun signOut() {
        val s = session
        clearSession()
        if (s != null) runCatching { client?.signOut(s.accessToken) }
    }

    /** Deletes the account, profile and synced settings. Settings on this device stay. */
    suspend fun deleteAccount() {
        val c = client ?: return
        c.deleteAccount(validToken())
        clearSession()
    }

    suspend fun saveDisplayName(name: String) {
        val c = client ?: return
        val value = name.trim().take(80)
        c.setDisplayName(value, validToken())
        _state.update { it.copy(displayName = value.ifEmpty { null }) }
    }

    // ---- Sync -------------------------------------------------------------------------------

    /** Sends pending changes (or none, which just fetches) and applies the merged result. */
    suspend fun sync(ifStale: Boolean = false) {
        val c = client ?: return
        if (session == null) return
        if (ifStale && System.currentTimeMillis() - lastSync < 60_000) return
        syncLock.withLock {
            _state.update { it.copy(syncing = true) }
            val body = synced.patch()
            val sent = synced.sentStamps()
            try {
                val res = c.mergeSettings(body, validToken())
                lastSync = System.currentTimeMillis()
                save(synced.adopting(res, sent))
                applyRemote()
                _state.update { it.copy(syncing = false, syncError = null) }
            } catch (e: SupabaseException) {
                if (e.isSignedOut) clearSession() else _state.update { it.copy(syncing = false, syncError = e.message) }
            } catch (e: IOException) {
                _state.update { it.copy(syncing = false, syncError = e.message ?: "No connection") }
            }
        }
        if (synced.pending.isNotEmpty() && _state.value.syncError == null && session != null) scheduleSync()
    }

    private fun scheduleSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(1_000)
            sync()
        }
    }

    /** Settings changed on this device become pending. */
    private fun onLocalSettings() {
        if (applyingRemote) return
        val now = SettingsBridge.read(settingsStore.settings.value)
        if (now == lastSeen) return
        var next = synced
        for (key in SettingsBridge.KEYS) if (now[key] != lastSeen[key]) next = next.change(key, now[key])
        lastSeen = now
        save(next)
        if (session != null) scheduleSync()
    }

    private fun applyRemote() {
        applyingRemote = true
        try {
            val current = settingsStore.settings.value
            val next = SettingsBridge.apply(current, synced)
            if (next != current) settingsStore.update { next }
            lastSeen = SettingsBridge.read(settingsStore.settings.value)
        } finally {
            applyingRemote = false
        }
    }

    private fun save(next: SyncedSettings) {
        synced = next
        store.settings = next
    }

    // ---- Session ----------------------------------------------------------------------------

    private suspend fun didSignIn(s: AuthSession) {
        session = s
        store.session = s
        _state.update { it.copy(user = s.user, syncError = null) }
        // Settings changed before this version had no stamps: offer them with the oldest
        // possible stamp, so the account's value wins where it has one.
        val defaults = SettingsBridge.defaults()
        var next = synced
        for ((key, value) in SettingsBridge.read(settingsStore.settings.value)) {
            if (next.stamps[key.id] == null && value != defaults[key]) next = next.change(key, value, Instant.EPOCH)
        }
        save(next)
        loadProfile()
        sync()
    }

    private suspend fun loadProfile() {
        val c = client ?: return
        val user = session?.user ?: return
        runCatching { c.displayName(user.id, validToken()) }.onSuccess { name -> _state.update { it.copy(displayName = name) } }
    }

    private fun clearSession() {
        syncJob?.cancel()
        session = null
        store.session = null
        save(synced.copy(pending = emptyList()))
        _state.update { AccountState(configured = it.configured) }
    }

    /** A current access token, refreshing it (once, however many callers wait) when due. */
    private suspend fun validToken(): String = refreshLock.withLock {
        val c = client ?: throw SupabaseException(401, null, "Not signed in.")
        val s = session ?: throw SupabaseException(401, null, "Not signed in.")
        if (!s.needsRefresh()) return@withLock s.accessToken
        try {
            val fresh = c.refresh(s.refreshToken)
            session = fresh
            store.session = fresh
            fresh.accessToken
        } catch (e: SupabaseException) {
            if (e.isSignedOut || e.status == 400) clearSession()
            throw e
        }
    }
}
