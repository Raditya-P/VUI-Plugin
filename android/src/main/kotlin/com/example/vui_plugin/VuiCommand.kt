package com.example.vui_plugin

import org.json.JSONObject

/**
 * Represents a structured voice command with intent and extracted slots.
 * 
 * @param intent The action to perform (e.g., "ADD_STOCK", "SEARCH_PRODUCT")
 * @param slots Extracted entities from voice input (e.g., product_name, quantity)
 * @param rawText The original transcribed text
 * @param confidence Recognition confidence (0.0 to 1.0)
 */
data class VuiCommand(
    val intent: String,
    val slots: Map<String, String> = emptyMap(),
    val rawText: String = "",
    val confidence: Float = 1.0f
) {
    /**
     * Serialize to JSON string for Flutter communication
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("intent", intent)
        json.put("rawText", rawText)
        json.put("confidence", confidence.toDouble())
        
        val slotsJson = JSONObject()
        slots.forEach { (key, value) ->
            slotsJson.put(key, value)
        }
        json.put("slots", slotsJson)
        
        return json.toString()
    }
    
    companion object {
        /**
         * Create a simple command without slots (for backward compatibility)
         */
        fun simple(intent: String, rawText: String = ""): VuiCommand {
            return VuiCommand(intent = intent, rawText = rawText)
        }
    }
}
