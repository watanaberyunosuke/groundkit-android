package com.harrydatahub.groundkit

import android.content.Intent
import android.net.http.HttpResponseCache
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.harrydatahub.groundkit.data.account.AccountManager
import com.harrydatahub.groundkit.ui.AppRoot
import com.harrydatahub.groundkit.ui.AppViewModel
import com.harrydatahub.groundkit.ui.MapViewModel
import com.harrydatahub.groundkit.ui.ShiftViewModel
import com.harrydatahub.groundkit.ui.TurnaroundViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val shiftVm: ShiftViewModel by viewModels()
    private val mapVm: MapViewModel by viewModels()
    private val turnVm: TurnaroundViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disk cache for map tiles (the API client opts out; it keeps its own copies).
        if (HttpResponseCache.getInstalled() == null) {
            runCatching { HttpResponseCache.install(File(cacheDir, "http"), 50L * 1024 * 1024) }
        }
        AccountManager.init(this)
        enableEdgeToEdge()
        setContent { AppRoot(vm, shiftVm, mapVm, turnVm) }
        handleAuthCallback(intent)
    }

    // Provider sign-in returns to groundkit://auth-callback (launchMode singleTask, so this
    // activity gets it and the Custom Tab closes).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthCallback(intent)
    }

    private fun handleAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "groundkit" || uri.host != "auth-callback") return
        intent.data = null // not again on rotation
        AccountManager.finishProvider(uri.toString()) { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }
}
