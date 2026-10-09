package com.harrydatahub.groundkit.data.account

import com.harrydatahub.groundkit.data.Settings
import com.harrydatahub.groundkit.data.ThemeMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SyncedSettingsTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")

    @Test fun changeStampsAndQueuesTheKey() {
        val s = SyncedSettings()
            .change(SettingKey.KEEP_AWAKE, true, t0)
            .change(SettingKey.KEEP_AWAKE, false, t0.plusSeconds(60))
        assertEquals(false, s[SettingKey.KEEP_AWAKE])
        assertEquals(listOf("keepAwake"), s.pending)
        assertEquals("2026-10-09T10:01:00Z", s.stamps["keepAwake"])
    }

    @Test fun patchSendsPendingKeysAndNullForRemoved() {
        val s = SyncedSettings().change(SettingKey.AIRPORT, "YSSY", t0).change(SettingKey.DASHBOARD_LAYOUT, null, t0)
        val body = s.patch()
        assertEquals("YSSY", body.getJSONObject("patch").getString("airport"))
        assertTrue(body.getJSONObject("patch").isNull("dashboardLayout"))
        assertEquals("2026-10-09T10:00:00Z", body.getJSONObject("patch_stamps").getString("airport"))
    }

    @Test fun adoptTakesTheServerButKeepsKeysChangedInFlight() {
        var s = SyncedSettings().change(SettingKey.AIRPORT, "YSSY", t0).change(SettingKey.KEEP_AWAKE, true, t0)
        val sent = s.sentStamps()
        s = s.change(SettingKey.KEEP_AWAKE, false, t0.plusSeconds(5)) // while the request was out
        val server = JSONObject("""{"settings": {"airport": "VHHH", "keepAwake": true, "gloveMode": true, "futureKey": "x", "windCautionKt": 500},
            "stamps": {"airport": "2026-10-09T11:00:00+00:00", "keepAwake": "2026-10-09T10:00:00+00:00"}}""")
        val next = s.adopting(server, sent)
        assertEquals("VHHH", next[SettingKey.AIRPORT])
        assertEquals(true, next[SettingKey.GLOVE_MODE])
        assertEquals(false, next[SettingKey.KEEP_AWAKE])
        assertEquals(listOf("keepAwake"), next.pending)
        assertNull(next.values["futureKey"])
        assertNull(next[SettingKey.WIND_CAUTION_KT])
    }

    @Test fun roundTripsThroughJson() {
        val s = SyncedSettings().change(SettingKey.WIND_CAUTION_KT, 30, t0).change(SettingKey.GLOVE_MODE, true, t0)
        assertEquals(s, SyncedSettings.fromJson(JSONObject(s.toJson().toString())))
    }
}

class SettingsBridgeTest {
    @Test fun readsAndAppliesTheSharedKeys() {
        val s = Settings(airport = "SYD", theme = ThemeMode.SUNSET, keepScreenOn = true, gustCautionKt = 30, highWindKt = 45)
        val read = SettingsBridge.read(s)
        assertEquals("YSSY", read[SettingKey.AIRPORT])
        assertEquals("sunset", read[SettingKey.APPEARANCE])
        assertEquals(true, read[SettingKey.KEEP_AWAKE])
        assertEquals(30, read[SettingKey.WIND_CAUTION_KT])

        val synced = SyncedSettings(values = mapOf("airport" to "EHAM", "appearance" to "dark", "windCautionKt" to 50, "windWarningKt" to 40, "gloveMode" to true))
        val next = SettingsBridge.apply(s, synced)
        assertEquals("AMS", next.airport)
        assertEquals(ThemeMode.DARK, next.theme)
        assertEquals(50, next.gustCautionKt)
        assertEquals(50, next.highWindKt) // never below the caution limit
        assertEquals(true, next.keepScreenOn) // not in the account: unchanged
    }

    @Test fun defaultsAreNotOffered() {
        assertEquals(SettingsBridge.read(Settings()), SettingsBridge.defaults())
        assertEquals("VHHH", SettingsBridge.defaults()[SettingKey.AIRPORT])
    }
}

class SupabaseClientTest {
    @Test fun pkceChallengeIsS256OfTheVerifier() {
        // Computed independently: base64url(sha256("groundkit-test-verifier-0123456789")).
        assertEquals("GyP7CMiaHVef476T4-n7UMZoJIW3AoyTFgku4Gc-vew", Pkce("groundkit-test-verifier-0123456789").challenge)
        assertEquals(43, Pkce.random().length)
    }

    @Test fun authorizeUrlCarriesTheChallenge() {
        val url = SupabaseClient(SupabaseConfig("https://abc.supabase.co", "k"))
            .authorizeUrl("azure", Pkce("groundkit-test-verifier-0123456789"), "email")
        assertEquals(
            "https://abc.supabase.co/auth/v1/authorize?provider=azure&redirect_to=groundkit%3A%2F%2Fauth-callback" +
                "&code_challenge=GyP7CMiaHVef476T4-n7UMZoJIW3AoyTFgku4Gc-vew&code_challenge_method=s256&scopes=email",
            url,
        )
    }

    @Test fun readsTheCallback() {
        assertEquals("abc", SupabaseClient.codeFromCallback("groundkit://auth-callback?code=abc"))
        val e = runCatching { SupabaseClient.codeFromCallback("groundkit://auth-callback#error=access_denied&error_description=Denied%20by%20user") }
            .exceptionOrNull() as SupabaseException
        assertEquals("Denied by user", e.message)
        assertEquals("access_denied", e.code)
    }

    @Test fun parsesASession() {
        val s = SupabaseClient.parseSession(JSONObject("""{"access_token": "a", "refresh_token": "r", "expires_at": 1791000000,
            "user": {"id": "u1", "email": "ada@example.com", "identities": [{"provider": "google"}, {"provider": "google"}]}}"""))
        assertEquals(1_791_000_000_000L, s.expiresAt)
        assertEquals(listOf("google"), s.user.providers)
        assertTrue(s.needsRefresh(1_790_999_950_000L))
        assertFalse(s.needsRefresh(1_790_990_000_000L))
        assertEquals(s, SupabaseClient.parseSession(s.toJson()))
    }

    @Test fun mapsErrors() {
        val auth = SupabaseClient.error(400, """{"error_code": "invalid_credentials", "msg": "Invalid login credentials"}""")
        assertEquals("That email and password do not match an account.", auth.message)
        assertTrue(SupabaseClient.error(401, """{"code": "PT401", "message": "This account no longer exists"}""").isSignedOut)
        assertTrue(SupabaseClient.error(400, """{"error_code": "refresh_token_not_found", "msg": "x"}""").isSignedOut)
        assertFalse(SupabaseClient.error(503, "<html>").isSignedOut)
    }
}

/**
 * Against a real (test) Supabase project with the aviation repo's migrations, when
 * GK_SUPABASE_TEST_URL and GK_SUPABASE_TEST_KEY are set; skipped otherwise. Creates and
 * deletes a throwaway user, so the project must not require email confirmation.
 */
class SupabaseIntegrationTest {
    private val url = System.getenv("GK_SUPABASE_TEST_URL")
    private val key = System.getenv("GK_SUPABASE_TEST_KEY")

    @Test fun signUpSyncRenameDelete() = runBlocking {
        assumeTrue(!url.isNullOrBlank() && !key.isNullOrBlank())
        val c = SupabaseClient(SupabaseConfig(url!!, key!!))
        val email = "android-${UUID.randomUUID().toString().take(8)}@example.com"
        val s = c.signUp(email, "correct horse", "Android Tester")!!
        assertEquals("Android Tester", c.displayName(s.user.id, s.accessToken))
        val local = SyncedSettings().change(SettingKey.AIRPORT, "WSSS").change(SettingKey.WIND_CAUTION_KT, 30)
        val merged = local.adopting(c.mergeSettings(local.patch(), s.accessToken), local.sentStamps())
        assertTrue(merged.pending.isEmpty())
        assertEquals("WSSS", merged[SettingKey.AIRPORT])
        val older = SyncedSettings().change(SettingKey.AIRPORT, "EHAM", Instant.EPOCH)
        assertEquals("WSSS", c.mergeSettings(older.patch(), s.accessToken).getJSONObject("settings").getString("airport"))
        c.setDisplayName("Renamed", s.accessToken)
        assertEquals("Renamed", c.displayName(s.user.id, s.accessToken))
        val s2 = c.refresh(s.refreshToken)
        c.deleteAccount(s2.accessToken)
        val e = runCatching { c.mergeSettings(local.patch(), s2.accessToken) }.exceptionOrNull() as SupabaseException
        assertTrue(e.isSignedOut)
    }
}
