package com.zhuanggui.tsvreader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TsvUiState(
    val loading: Boolean = false,
    val document: TsvDocument? = null,
    val fileName: String = "",
    val error: String? = null,
    val query: String = ""
)

class TsvReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(TsvUiState())
    val state: StateFlow<TsvUiState> = _state.asStateFlow()

    fun open(uri: Uri) {
        _state.update {
            it.copy(loading = true, error = null, document = null, fileName = "", query = "")
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val name = queryDisplayName(uri)
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        TsvParser.readLimited(input)
                    } ?: throw IllegalStateException("无法打开文件流（文件可能已被移动或删除）")

                    val document = TsvParser.parse(TsvParser.decode(bytes))
                    if (document.header.isEmpty() && document.rows.isEmpty()) {
                        throw IllegalArgumentException("文件是空的，没有可显示的内容")
                    }
                    name to document
                }
            }
            result.onSuccess { (name, document) ->
                _state.update {
                    it.copy(loading = false, document = document, fileName = name, error = null)
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(loading = false, error = friendlyError(t))
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun showError(message: String) {
        _state.update { it.copy(loading = false, error = message) }
    }

    private fun friendlyError(t: Throwable): String = when (t) {
        is SecurityException ->
            "没有读取该文件的权限。请在文件管理器中重新选择「打开方式 → TSV 阅读器」，或在 App 内点「选择文件」。"
        is IllegalArgumentException -> t.message ?: "文件内容无法解析"
        else -> "打开失败：${t.message ?: t.javaClass.simpleName}"
    }

    private fun queryDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "TSV 文件"
        }
        runCatching {
            getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "TSV 文件"
    }
}
