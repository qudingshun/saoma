package com.zhuanggui.tsvreader

import android.util.Log
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import com.zhuanggui.tsvreader.ui.TsvReaderApp

class MainActivity : ComponentActivity() {

    private val viewModel: TsvReaderViewModel by viewModels()
    private var pendingFileUri: Uri? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.open(uri)
        }
    }

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val uri = pendingFileUri
            pendingFileUri = null
            if (granted && uri != null) {
                viewModel.open(uri)
            } else {
                viewModel.showError(
                    "没有存储读取权限，无法打开文件。可在 App 内点「选择文件」，或到系统设置授予本应用存储权限。"
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            TsvReaderApp(
                viewModel = viewModel,
                onOpenFile = { openDocument.launch(arrayOf("*/*")) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        Log.i("TsvReader", "open uri=$uri")
        if (uri.scheme == "file" &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingFileUri = uri
            requestReadPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            viewModel.open(uri)
        }
    }
}
