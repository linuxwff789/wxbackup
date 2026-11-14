package com.nous.wxnotes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nous.wxnotes.ui.AppRoot
import com.nous.wxnotes.ui.WxNotesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialNoteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        setContent {
            WxNotesTheme {
                RequestNotificationPermission()
                AppRoot(initialNoteId)
            }
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
