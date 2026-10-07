package com.harrydatahub.groundkit

import android.net.http.HttpResponseCache
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.harrydatahub.groundkit.ui.AppRoot
import com.harrydatahub.groundkit.ui.AppViewModel
import com.harrydatahub.groundkit.ui.MapViewModel
import com.harrydatahub.groundkit.ui.ShiftViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val shiftVm: ShiftViewModel by viewModels()
    private val mapVm: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disk cache for map tiles (the API client opts out; it keeps its own copies).
        if (HttpResponseCache.getInstalled() == null) {
            runCatching { HttpResponseCache.install(File(cacheDir, "http"), 50L * 1024 * 1024) }
        }
        enableEdgeToEdge()
        setContent { AppRoot(vm, shiftVm, mapVm) }
    }
}
