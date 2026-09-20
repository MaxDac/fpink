package com.fpink.capture.ui.capture

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

internal class CameraDisplayRotation(
    private val view: View,
    private val lifecycle: Lifecycle,
    private val onRotation: (Int) -> Unit,
) : DefaultLifecycleObserver, DisplayManager.DisplayListener, View.OnAttachStateChangeListener, AutoCloseable {
    private val displayManager = requireNotNull(view.context.getSystemService(DisplayManager::class.java))
    private var listening = false

    init {
        view.addOnAttachStateChangeListener(this)
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) = start()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onViewAttachedToWindow(view: View) = start()
    override fun onViewDetachedFromWindow(view: View) = stop()

    private fun start() {
        if (!listening && view.isAttachedToWindow && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            displayManager.registerDisplayListener(this, Handler(Looper.getMainLooper()))
            listening = true
            updateRotation()
        }
    }

    fun updateRotation() {
        if (listening) view.display?.let { onRotation(it.rotation) }
    }

    override fun onDisplayChanged(displayId: Int) {
        if (view.display?.displayId == displayId) updateRotation()
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    private fun stop() {
        if (listening) {
            displayManager.unregisterDisplayListener(this)
            listening = false
        }
    }

    override fun close() {
        lifecycle.removeObserver(this)
        view.removeOnAttachStateChangeListener(this)
        stop()
    }
}
