package com.example.vui_plugin

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import android.util.Log

/** VuiPlugin */
class VuiPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var channel : MethodChannel
    private lateinit var eventChannel : EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var eventListener: ((String) -> Unit)? = null
    
    companion object {
        private const val TAG = "VuiPlugin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "vui_plugin")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "vui_plugin/events")
        eventChannel.setStreamHandler(this)
        
        Log.d(TAG, "VuiPlugin attached to engine")
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "setCurrentScreen" -> {
                val screenName = call.argument<String>("screen")
                Log.d(TAG, "Setting current screen to: $screenName")
                NlpEngine.setContext(screenName)
                result.success(null)
            }
            "getAvailableCommands" -> {
                val nlpEngine = NlpEngine()
                val commands = nlpEngine.getAvailableCommands()
                Log.d(TAG, "Available commands for ${NlpEngine.getContext()}: $commands")
                result.success(commands)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        Log.d(TAG, "VuiPlugin detached from engine")
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        eventListener = { event ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                eventSink?.success(event)
            }
        }
        VuiEventBus.subscribe(eventListener!!)
        Log.d(TAG, "Event stream listening started")
    }

    override fun onCancel(arguments: Any?) {
        eventListener?.let { VuiEventBus.unsubscribe(it) }
        eventSink = null
        eventListener = null
        Log.d(TAG, "Event stream listening cancelled")
    }
}
