import 'dart:convert';

import 'vui_plugin_platform_interface.dart';

/// Represents a structured voice command with intent and extracted slots.
class VuiCommand {
  final String intent;
  final Map<String, String> slots;
  final String rawText;
  final double confidence;

  VuiCommand({
    required this.intent,
    this.slots = const {},
    this.rawText = '',
    this.confidence = 1.0,
  });

  factory VuiCommand.fromJson(Map<String, dynamic> json) {
    return VuiCommand(
      intent: json['intent'] as String? ?? '',
      slots: (json['slots'] as Map<String, dynamic>?)?.map(
            (key, value) => MapEntry(key, value.toString()),
          ) ??
          {},
      rawText: json['rawText'] as String? ?? '',
      confidence: (json['confidence'] as num?)?.toDouble() ?? 1.0,
    );
  }

  @override
  String toString() => 'VuiCommand(intent: $intent, slots: $slots, rawText: $rawText)';
}

class VuiPlugin {
  Future<String?> getPlatformVersion() {
    return VuiPluginPlatform.instance.getPlatformVersion();
  }

  /// Raw command stream (JSON strings from native)
  Stream<String> get onCommand {
    return VuiPluginPlatform.instance.onCommand;
  }

  /// Parsed command stream with structured VuiCommand objects
  Stream<VuiCommand> get onVuiCommand {
    return onCommand.map((json) {
      try {
        final decoded = jsonDecode(json) as Map<String, dynamic>;
        return VuiCommand.fromJson(decoded);
      } catch (e) {
        // Fallback for legacy string commands
        return VuiCommand(intent: json, rawText: json);
      }
    });
  }

  /// Set the current screen context for context-aware commands
  Future<void> setCurrentScreen(String screenName) {
    return VuiPluginPlatform.instance.setCurrentScreen(screenName);
  }

  /// Get available voice commands for current screen
  Future<List<String>> getAvailableCommands() {
    return VuiPluginPlatform.instance.getAvailableCommands();
  }

  /// Check if VUI accessibility service is enabled
  Future<bool> isAccessibilityEnabled() {
    return VuiPluginPlatform.instance.isAccessibilityEnabled();
  }

  /// Open Android Accessibility Settings page
  Future<bool> openAccessibilitySettings() {
    return VuiPluginPlatform.instance.openAccessibilitySettings();
  }
}
