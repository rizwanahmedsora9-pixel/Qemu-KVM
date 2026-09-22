package com.example.routeremu

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QemuStatus {
    IDLE,
    PREPARING_ASSETS,
    BOOTING,
    RUNNING,
    STOPPED,
    ERROR
}

/**
 * Simple in-process pub/sub so QemuService (running on its own lifecycle,
 * possibly with no Activity attached) can stream log lines and status
 * changes to whatever UI happens to be listening — without needing a
 * Binder/Messenger round trip for something this lightweight.
 */
object QemuLogBus {

    // replay = 400: a UI that (re)attaches after the service has already
    // been logging for a while (e.g. after rotation, or Activity
    // recreation) still gets recent scrollback instead of a blank screen.
    private val _logLines = MutableSharedFlow<String>(
        replay = 400,
        extraBufferCapacity = 64
    )
    val logLines = _logLines.asSharedFlow()

    private val _status = MutableStateFlow(QemuStatus.IDLE)
    val status = _status.asStateFlow()

    // Set once the hostfwd port is confirmed open and the WebView should load it.
    private val _webPortReady = MutableStateFlow(false)
    val webPortReady = _webPortReady.asStateFlow()

    fun log(line: String) {
        _logLines.tryEmit(line)
    }

    fun setStatus(newStatus: QemuStatus) {
        _status.value = newStatus
        if (newStatus != QemuStatus.RUNNING) {
            _webPortReady.value = false
        }
    }

    fun setWebPortReady(ready: Boolean) {
        _webPortReady.value = ready
    }

    fun reset() {
        _status.value = QemuStatus.IDLE
        _webPortReady.value = false
    }
}
