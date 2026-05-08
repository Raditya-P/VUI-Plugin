package com.example.vui_plugin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.view.accessibility.AccessibilityEvent
import android.view.KeyEvent
import android.util.Log
import android.content.Intent
import android.content.ComponentName
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest

class VuiAccessibilityService : AccessibilityService() {

    private val TAG = "VuiAccessibilityService"
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechIntent: Intent
    private val nlpEngine = NlpEngine()
    private var isListening = false
    private var retryCount = 0
    private val MAX_RETRIES = 2
    private var watchdogRunnable: Runnable? = null
    
    private var accessibilityButtonCallback: AccessibilityButtonCallback? = null
    private var isAccessibilityButtonAvailable = false
    
    // Engine fallback: try Google first, fall back to default if Google silently fails
    private var useGoogleEngine = true  // Start with Google, auto-switch on failure
    private var readyTimeoutRunnable: Runnable? = null
    private val READY_TIMEOUT_MS = 3000L // 3s: if no onReadyForSpeech, engine is dead
    
    // Volume-key double-press detection
    private var lastVolumeDownTime = 0L
    private val DOUBLE_PRESS_THRESHOLD_MS = 500L // 500ms window for double-press

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        
        // Register accessibility button callback (required for API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupAccessibilityButtonCallback()
        }
        
        // Initialize Speech Recognizer
        handler.post {
            try {
                setupSpeechRecognizer()
            } catch (e: Exception) {
                Log.e(TAG, "Error in setupSpeechRecognizer", e)
            }
        }
        
        // Send diagnostic info to Flutter
        sendDiagnosticInfo()
    }
    
    /**
     * Send diagnostic info about the device's capabilities to Flutter.
     * This helps troubleshoot issues on different devices.
     */
    private fun sendDiagnosticInfo() {
        val hasGoogleSpeech = isGoogleSpeechAvailable()
        val hasSpeechRecognition = SpeechRecognizer.isRecognitionAvailable(this)
        
        Log.d(TAG, "=== VUI Diagnostic Info ===")
        Log.d(TAG, "  Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        Log.d(TAG, "  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        Log.d(TAG, "  Speech Recognition available: $hasSpeechRecognition")
        Log.d(TAG, "  Google Speech Services available: $hasGoogleSpeech")
        Log.d(TAG, "  Accessibility button available: $isAccessibilityButtonAvailable")
        Log.d(TAG, "  Volume-key fallback: enabled (double-press volume down)")
        Log.d(TAG, "==========================")
        
        VuiEventBus.publish(VuiCommand(
            intent = "SPEECH_STATE",
            slots = mapOf(
                "state" to "DIAGNOSTIC",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "api_level" to Build.VERSION.SDK_INT.toString(),
                "speech_available" to hasSpeechRecognition.toString(),
                "google_speech" to hasGoogleSpeech.toString(),
                "accessibility_button" to isAccessibilityButtonAvailable.toString()
            ),
            rawText = ""
        ))
    }
    
    private fun setupAccessibilityButtonCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            
            // Check initial availability
            isAccessibilityButtonAvailable = controller.isAccessibilityButtonAvailable
            Log.d(TAG, "Accessibility button initially available: $isAccessibilityButtonAvailable")
            
            if (!isAccessibilityButtonAvailable) {
                Log.w(TAG, "Accessibility button NOT available (likely gesture navigation). Volume-key fallback is active.")
            }
            
            accessibilityButtonCallback = object : AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.d(TAG, "Accessibility Button Clicked (via callback)")
                    handleAccessibilityButtonClick()
                }
                
                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.d(TAG, "Accessibility Button availability changed: $available")
                    isAccessibilityButtonAvailable = available
                    if (!available) {
                        Log.w(TAG, "Accessibility button became unavailable. Volume-key fallback remains active.")
                    }
                }
            }
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
            Log.d(TAG, "Accessibility button callback registered")
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Check if Google Speech Services (com.google.android.googlequicksearchbox) is installed.
     */
    private fun isGoogleSpeechAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo("com.google.android.googlequicksearchbox", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech Recognition not available on this device")
            VuiEventBus.publish(VuiCommand(
                intent = "SPEECH_STATE",
                slots = mapOf("state" to "ERROR", "error_code" to "SPEECH_NOT_AVAILABLE"),
                rawText = ""
            ))
            return
        }
        
        try {
            if (useGoogleEngine && isGoogleSpeechAvailable()) {
                // Try Google Speech engine first for reliable Indonesian recognition.
                // Samsung/Xiaomi/Oppo may default to their own engine that doesn't support id-ID.
                val googleSpeechComponent = ComponentName(
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
                )
                Log.d(TAG, "Creating SpeechRecognizer with Google Speech engine")
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, googleSpeechComponent)
            } else {
                Log.d(TAG, "Creating SpeechRecognizer with default engine")
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            }
            
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID") // Indonesian
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            
            speechRecognizer.setRecognitionListener(createRecognitionListener())
        } catch (e: Exception) {
            if (useGoogleEngine) {
                Log.e(TAG, "Google engine threw exception, switching to default engine", e)
                useGoogleEngine = false
                setupSpeechRecognizer() // Retry with default
            } else {
                Log.e(TAG, "Failed to create SpeechRecognizer entirely", e)
            }
        }
    }
    
    /**
     * Create a RecognitionListener with all the callback handlers.
     * Extracted to avoid code duplication between primary and fallback engine setup.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { 
                Log.d(TAG, "Ready for speech - mic should be active now (engine: ${if (useGoogleEngine) "Google" else "Default"})")
                // Cancel the ready-timeout since engine responded successfully
                cancelReadyTimeout()
                // Notify Flutter that listening has started
                VuiEventBus.publish(VuiCommand(
                    intent = "SPEECH_STATE",
                    slots = mapOf("state" to "LISTENING_START"),
                    rawText = ""
                ))
            }
            override fun onBeginningOfSpeech() { 
                Log.d(TAG, "Beginning of speech - audio input detected!") 
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { 
                Log.d(TAG, "End of speech") 
                cancelWatchdog()
                isListening = false
                // Notify Flutter that listening has ended
                VuiEventBus.publish(VuiCommand(
                    intent = "SPEECH_STATE",
                    slots = mapOf("state" to "LISTENING_END"),
                    rawText = ""
                ))
            }
            override fun onError(error: Int) {
                val errorName = when(error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    else -> "UNKNOWN($error)"
                }
                Log.e(TAG, "Speech Error: $error ($errorName) - retry $retryCount/$MAX_RETRIES")
                cancelWatchdog()
                isListening = false
                
                // Handle permission revocation (error 9)
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    Log.e(TAG, "RECORD_AUDIO permission was revoked by OS!")
                    retryCount = 0
                    // Notify Flutter to re-request permission from Activity context
                    VuiEventBus.publish(VuiCommand(
                        intent = "SPEECH_STATE",
                        slots = mapOf("state" to "PERMISSION_LOST"),
                        rawText = ""
                    ))
                    return
                }
                
                // Classify error type
                val isTransient = error in listOf(
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                )
                
                if (isTransient && retryCount < MAX_RETRIES) {
                    retryCount++
                    Log.d(TAG, "Transient error $error, auto-retrying ($retryCount/$MAX_RETRIES)...")
                    
                    // Notify Flutter we're retrying
                    VuiEventBus.publish(VuiCommand(
                        intent = "SPEECH_STATE",
                        slots = mapOf("state" to "RETRYING", "retry_count" to retryCount.toString()),
                        rawText = ""
                    ))
                    
                    // Recreate and auto-restart listening
                    handler.postDelayed({
                        try {
                            speechRecognizer.destroy()
                            setupSpeechRecognizer()
                            isListening = true
                            startWatchdog()
                            speechRecognizer.startListening(speechIntent)
                            Log.d(TAG, "Auto-retry: restarted listening")
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-retry failed", e)
                            isListening = false
                            retryCount = 0
                        }
                    }, 500)
                    return // Don't send error to Flutter yet
                }
                
                // Max retries exceeded or non-transient error
                retryCount = 0
                
                // Recreate recognizer to ensure clean state for next press
                handler.postDelayed({
                    try {
                        speechRecognizer.destroy()
                        setupSpeechRecognizer()
                        Log.d(TAG, "SpeechRecognizer recreated after final error")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to recreate SpeechRecognizer", e)
                    }
                }, 300)
                
                // Notify Flutter of error
                VuiEventBus.publish(VuiCommand(
                    intent = "SPEECH_STATE",
                    slots = mapOf("state" to "ERROR", "error_code" to error.toString()),
                    rawText = ""
                ))
            }
            override fun onResults(results: Bundle?) {
                cancelWatchdog()
                retryCount = 0 // Reset on success
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.d(TAG, "Recognized: $text")
                    // Process the command - will send COMMAND_RECOGNIZED or COMMAND_NOT_RECOGNIZED
                    processCommand(text)
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]
                    Log.d(TAG, "Partial: $partialText")
                    // Send partial text to Flutter for live transcription
                    VuiEventBus.publish(VuiCommand(
                        intent = "SPEECH_STATE",
                        slots = mapOf("state" to "PARTIAL_TEXT", "text" to partialText),
                        rawText = partialText
                    ))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun processCommand(text: String) {
        val command = nlpEngine.processText(text)
        if (command != null) {
            Log.d(TAG, "Command detected: intent=${command.intent}, slots=${command.slots}")
            
            // Notify Flutter that a command was recognized
            VuiEventBus.publish(VuiCommand(
                intent = "SPEECH_STATE",
                slots = mapOf("state" to "COMMAND_RECOGNIZED", "text" to text, "command_intent" to command.intent),
                rawText = text
            ))
            
            VuiEventBus.publish(command)
            
            // Perform global action for back navigation
            if (command.intent == "NAV_BACK") {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        } else {
            Log.d(TAG, "No command recognized from: $text")
            // Notify Flutter that no command was recognized
            VuiEventBus.publish(VuiCommand(
                intent = "SPEECH_STATE",
                slots = mapOf("state" to "COMMAND_NOT_RECOGNIZED", "text" to text),
                rawText = text
            ))
        }
    }

    // This method is called when an AccessibilityEvent is fired.
    // We can use this to detect context changes if needed.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log.d(TAG, "Event: $event")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }
    
    // ===== VOLUME KEY FALLBACK TRIGGER =====
    
    /**
     * Intercept key events to support double-press volume-down as an alternative
     * trigger for speech recognition. This is essential for devices that use
     * gesture navigation where the accessibility button is not available.
     *
     * Single press: volume still works normally (event NOT consumed)
     * Double press (within 500ms): triggers speech recognition (events consumed)
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            val timeSinceLastPress = now - lastVolumeDownTime
            lastVolumeDownTime = now
            
            if (timeSinceLastPress < DOUBLE_PRESS_THRESHOLD_MS) {
                // Double-press detected!
                Log.d(TAG, "Volume-down double-press detected! Triggering speech recognition.")
                lastVolumeDownTime = 0L // Reset to prevent triple-press
                handleAccessibilityButtonClick()
                return true // Consume the event so volume doesn't change
            }
            
            // First press — schedule a check: if no second press comes within the threshold,
            // let the volume change happen naturally. We do this by NOT consuming the first press.
            // The volume will adjust on first press (this is acceptable UX trade-off).
            return false
        }
        
        return super.onKeyEvent(event)
    }
    
    /// Check if RECORD_AUDIO permission is still granted
    private fun hasMicrophonePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    
    // This method handles accessibility button click (called from our registered callback)
    // Also called from volume-key double-press handler
    private fun handleAccessibilityButtonClick() {
        Log.d(TAG, "Handling Accessibility Button Click")
        
        // Pre-check: verify RECORD_AUDIO permission is still granted
        if (!hasMicrophonePermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted! Notifying Flutter to re-request.")
            VuiEventBus.publish(VuiCommand(
                intent = "SPEECH_STATE",
                slots = mapOf("state" to "PERMISSION_LOST"),
                rawText = ""
            ))
            return
        }
        
        if (!isListening) {
            isListening = true
            retryCount = 0
            handler.post {
                try {
                    // Always destroy and recreate the recognizer for a fresh microphone binding.
                    // This prevents stale recognizer issues on Oppo/ColorOS devices where the
                    // internal mic binding silently becomes invalid after idle periods.
                    if (::speechRecognizer.isInitialized) {
                        try {
                            speechRecognizer.cancel()
                            speechRecognizer.destroy()
                            Log.d(TAG, "Previous recognizer destroyed for fresh session")
                        } catch (e: Exception) {
                            Log.w(TAG, "Error destroying previous recognizer (non-fatal)", e)
                        }
                    }
                    // Small delay to allow cleanup before recreating
                    handler.postDelayed({
                        try {
                            // Double-check permission right before starting (race condition guard)
                            if (!hasMicrophonePermission()) {
                                Log.e(TAG, "Permission lost between button press and listening start")
                                isListening = false
                                VuiEventBus.publish(VuiCommand(
                                    intent = "SPEECH_STATE",
                                    slots = mapOf("state" to "PERMISSION_LOST"),
                                    rawText = ""
                                ))
                                return@postDelayed
                            }
                            // Create fresh recognizer with new mic binding
                            setupSpeechRecognizer()
                            startWatchdog()
                            startReadyTimeout() // Auto-fallback if engine doesn't respond
                            speechRecognizer.startListening(speechIntent)
                            Log.d(TAG, "Starting listening with fresh recognizer (engine: ${if (useGoogleEngine) "Google" else "Default"})...")
                        } catch (e: Exception) {
                            Log.e(TAG, "Start listening failed", e)
                            isListening = false
                            cancelWatchdog()
                            cancelReadyTimeout()
                        }
                    }, 150)
                } catch (e: Exception) {
                     Log.e(TAG, "Cleanup or start listening failed", e)
                     isListening = false
                }
            }
        } else {
            Log.d(TAG, "Already listening, ignoring button press")
        }
    }
    
    // Watchdog: force-reset if no result within 10 seconds
    private fun startWatchdog() {
        cancelWatchdog()
        watchdogRunnable = Runnable {
            if (isListening) {
                Log.w(TAG, "Watchdog timeout! Force-resetting recognizer.")
                isListening = false
                retryCount = 0
                try {
                    speechRecognizer.cancel()
                    speechRecognizer.destroy()
                    setupSpeechRecognizer()
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog reset failed", e)
                }
                // Notify Flutter
                VuiEventBus.publish(VuiCommand(
                    intent = "SPEECH_STATE",
                    slots = mapOf("state" to "ERROR", "error_code" to "TIMEOUT"),
                    rawText = ""
                ))
            }
        }
        handler.postDelayed(watchdogRunnable!!, 10_000) // 10 second timeout
    }
    
    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }
    
    /**
     * Ready-timeout: if onReadyForSpeech is NOT called within READY_TIMEOUT_MS
     * after startListening, the current engine is silently broken.
     * Auto-switch to default engine and retry.
     */
    private fun startReadyTimeout() {
        cancelReadyTimeout()
        readyTimeoutRunnable = Runnable {
            if (isListening && useGoogleEngine) {
                Log.w(TAG, "Ready-timeout! Google engine did not respond in ${READY_TIMEOUT_MS}ms. Switching to default engine.")
                useGoogleEngine = false
                isListening = false
                cancelWatchdog()
                try {
                    speechRecognizer.cancel()
                    speechRecognizer.destroy()
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying unresponsive Google engine (non-fatal)", e)
                }
                // Retry with default engine
                handler.postDelayed({
                    handleAccessibilityButtonClick()
                }, 200)
            }
        }
        handler.postDelayed(readyTimeoutRunnable!!, READY_TIMEOUT_MS)
    }
    
    private fun cancelReadyTimeout() {
        readyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        readyTimeoutRunnable = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cancelWatchdog()
        // Unregister accessibility button callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && accessibilityButtonCallback != null) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback!!)
            Log.d(TAG, "Accessibility button callback unregistered")
        }
        // Clean up speech recognizer
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }
}
