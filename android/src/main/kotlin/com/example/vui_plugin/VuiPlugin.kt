package com.example.vui_plugin

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.EventChannel
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/** VuiPlugin */
class VuiPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var channel : MethodChannel
    private lateinit var eventChannel : EventChannel
    private var eventSink: EventChannel.EventSink? = null
    private var eventListener: ((String) -> Unit)? = null
    private var applicationContext: Context? = null
    
    companion object {
        private const val TAG = "VuiPlugin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "vui_plugin")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "vui_plugin/events")
        eventChannel.setStreamHandler(this)
        
        applicationContext = flutterPluginBinding.applicationContext
        
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
            "isAccessibilityEnabled" -> {
                val enabled = isAccessibilityServiceEnabled()
                Log.d(TAG, "Accessibility service enabled: $enabled")
                result.success(enabled)
            }
            "openAccessibilitySettings" -> {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext?.startActivity(intent)
                    result.success(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open accessibility settings: ${e.message}")
                    result.success(false)
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val context = applicationContext ?: return false
        val serviceName = "${context.packageName}/com.example.vui_plugin.VuiAccessibilityService"
        
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            
            if (enabledServices.isNullOrEmpty()) return false
            
            return TextUtils.SimpleStringSplitter(':').let { splitter ->
                splitter.setString(enabledServices)
                splitter.any { it.equals(serviceName, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility: ${e.message}")
            return false
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
