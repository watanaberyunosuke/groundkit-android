package com.harrydatahub.groundkit.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.harrydatahub.groundkit.data.account.AccountManager
import com.harrydatahub.groundkit.data.account.AccountState
import com.harrydatahub.groundkit.data.account.AuthProvider
import com.harrydatahub.groundkit.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.io.IOException

private const val MIN_PASSWORD = 8
private val PROVIDER_NAMES = mapOf("email" to "Email", "apple" to "Apple", "google" to "Google", "azure" to "Microsoft")

/** The Account part of Settings: sign in, or the signed-in account. Hidden without Supabase keys. */
@Composable
fun AccountSection() {
    val account by AccountManager.state.collectAsState()
    if (!account.configured) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        val user = account.user
        if (user == null) {
            Text(
                "Optional. Signed in, your airport, theme, keep screen on and wind alerts sync with GroundKit on iPhone and the web. " +
                    "Airlines, the flight filter and age stay on this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Sign in or create an account")
            }
        } else {
            Text(account.displayName ?: user.email ?: "Your account", style = MaterialTheme.typography.bodyLarge)
            if (account.displayName != null) user.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(syncText(account), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Manage account") }
        }
    }
    if (open) {
        if (account.user == null) SignInSheet(onDismiss = { open = false }) else ManageAccountSheet(account, onDismiss = { open = false })
    }
}

private fun syncText(a: AccountState) = when {
    a.syncing -> "Syncing settings…"
    a.syncError != null -> "Settings not synced: ${a.syncError}"
    else -> "Settings synced"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInSheet(onDismiss: () -> Unit) {
    val account by AccountManager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var create by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account.user) { if (account.user != null) onDismiss() }

    fun run(work: suspend () -> Unit) {
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                work()
            } catch (e: IOException) {
                error = e.message ?: "No connection. Try again."
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (create) "Create your GroundKit account" else "Sign in to GroundKit", style = MaterialTheme.typography.titleLarge)
            AuthProvider.entries.forEach { p ->
                OutlinedButton(
                    onClick = {
                        val url = AccountManager.providerUrl(p) ?: return@OutlinedButton
                        try {
                            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                        } catch (e: ActivityNotFoundException) {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { error = "No browser to sign in with." }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(p.label) }
            }
            HorizontalDivider()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Sign in", true to "Create account").forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = create == value,
                        onClick = { create = value; error = null },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(label) }
                }
            }
            if (create) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (create) ({ Text("At least $MIN_PASSWORD characters.") }) else null,
            )
            error?.let {
                Text(it, color = LocalStatusColors.current.red, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Button(
                onClick = {
                    if (create && password.length < MIN_PASSWORD) {
                        error = "Use at least $MIN_PASSWORD characters for your password."
                        return@Button
                    }
                    run {
                        if (!create) AccountManager.signIn(email, password)
                        else if (!AccountManager.signUp(email, password, name)) {
                            create = false
                            notice = "We sent a link to $email. Open it to confirm your address, then sign in here."
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (create) "Create account" else "Sign in")
            }
            if (!create) {
                TextButton(
                    onClick = {
                        if (email.isBlank()) error = "Enter your email address first."
                        else run {
                            AccountManager.resetPassword(email)
                            notice = "If $email has an account, we sent it a link to choose a new password."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Forgot password?") }
            } else {
                Text(
                    "We send a link to confirm your address. See the privacy policy at groundkit-intro-website.vercel.app/privacy.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageAccountSheet(account: AccountState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val user = account.user ?: return
    var name by rememberSaveable { mutableStateOf(account.displayName ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun run(work: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                work()
            } catch (e: IOException) {
                error = e.message ?: "No connection. Try again."
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Your account", style = MaterialTheme.typography.titleLarge)
            Fact("Email", user.email ?: "Not shared by the provider")
            Fact("Signed in with", user.providers.joinToString(", ") { PROVIDER_NAMES[it] ?: it }.ifEmpty { "Email" })
            Fact("Settings", syncText(account))
            if (account.syncError != null) {
                TextButton(onClick = { scope.launch { AccountManager.sync() } }) { Text("Try again") }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (name.trim() != (account.displayName ?: "")) {
                OutlinedButton(onClick = { run { AccountManager.saveDisplayName(name) } }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Save name")
                }
            }
            error?.let { Text(it, color = LocalStatusColors.current.red, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { run { AccountManager.signOut(); onDismiss() } },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Sign out") }
            HorizontalDivider()
            Text(
                "Deleting your account removes your profile and synced settings for good. Settings on this device stay.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Delete account", color = LocalStatusColors.current.red)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your GroundKit account?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    run { AccountManager.deleteAccount(); onDismiss() }
                }) { Text("Delete account", color = LocalStatusColors.current.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}
