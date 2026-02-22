package com.example.vui_plugin

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Event bus for publishing VUI commands between components.
 * Sends JSON-serialized VuiCommand objects to Flutter.
 */
object VuiEventBus {
    private const val TAG = "VuiEventBus"
    private val listeners = mutableListOf<(String) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    fun subscribe(listener: (String) -> Unit) {
        listeners.add(listener)
        Log.d(TAG, "Listener subscribed, total: ${listeners.size}")
    }

    fun unsubscribe(listener: (String) -> Unit) {
        listeners.remove(listener)
        Log.d(TAG, "Listener unsubscribed, total: ${listeners.size}")
    }
    
    /**
     * Publish a VuiCommand (serialized as JSON)
     */
    fun publish(command: VuiCommand) {
        val json = command.toJson()
        Log.d(TAG, "Publishing command: $json")
        handler.post {
            listeners.forEach { it.invoke(json) }
        }
    }
    
    /**
     * Legacy: Publish a simple string command (backward compatible)
     */
    fun publishSimple(event: String) {
        val command = VuiCommand.simple(event)
        publish(command)
    }
}
