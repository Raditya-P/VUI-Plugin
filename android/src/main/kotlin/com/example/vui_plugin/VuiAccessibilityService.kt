package com.example.vui_plugin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback
import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.os.Bundle
import android.os.Build
import android.content.Context

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        
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
    }
    
    private fun setupAccessibilityButtonCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            accessibilityButtonCallback = object : AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) {
                    Log.d(TAG, "Accessibility Button Clicked (via callback)")
                    handleAccessibilityButtonClick()
                }
                
                override fun onAvailabilityChanged(controller: AccessibilityButtonController, available: Boolean) {
                    Log.d(TAG, "Accessibility Button availability changed: $available")
                }
            }
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
            Log.d(TAG, "Accessibility button callback registered")
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID") // Indonesian
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Prefer offline for speed
                }
                
                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { 
                        Log.d(TAG, "Ready for speech") 
                        // Notify Flutter that listening has started
                        VuiEventBus.publish(VuiCommand(
                            intent = "SPEECH_STATE",
                            slots = mapOf("state" to "LISTENING_START"),
                            rawText = ""
                        ))
                    }
                    override fun onBeginningOfSpeech() { 
                        Log.d(TAG, "Beginning of speech") 
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
                        Log.e(TAG, "Speech Error: $error (retry $retryCount/$MAX_RETRIES)")
                        cancelWatchdog()
                        isListening = false
                        
                        // Classify error type
                        val isTransient = error in listOf(
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_SERVER,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
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
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
            }
        } else {
            Log.e(TAG, "Speech Recognition not available")
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
    
    // This method handles accessibility button click (called from our registered callback)
    private fun handleAccessibilityButtonClick() {
        Log.d(TAG, "Handling Accessibility Button Click")
        if (!isListening) {
            isListening = true
            retryCount = 0
            handler.post {
                try {
                    // Cancel any previous session to avoid ERROR_RECOGNIZER_BUSY (error 8)
                    speechRecognizer.cancel()
                    // Small delay to allow cleanup
                    handler.postDelayed({
                        try {
                            startWatchdog()
                            speechRecognizer.startListening(speechIntent)
                            Log.d(TAG, "Starting listening...")
                        } catch (e: Exception) {
                            Log.e(TAG, "Start listening failed", e)
                            isListening = false
                            cancelWatchdog()
                        }
                    }, 100)
                } catch (e: Exception) {
                     Log.e(TAG, "Cancel or start listening failed", e)
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
