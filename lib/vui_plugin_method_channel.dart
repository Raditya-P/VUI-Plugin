import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'vui_plugin_platform_interface.dart';

/// An implementation of [VuiPluginPlatform] that uses method channels.
class MethodChannelVuiPlugin extends VuiPluginPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('vui_plugin');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  final eventChannel = const EventChannel('vui_plugin/events');

  @override
  Stream<String> get onCommand {
    return eventChannel.receiveBroadcastStream().map((event) => event as String);
  }
  
  @override
  Future<void> setCurrentScreen(String screenName) async {
    await methodChannel.invokeMethod('setCurrentScreen', {'screen': screenName});
  }
  
  @override
  Future<List<String>> getAvailableCommands() async {
    final result = await methodChannel.invokeMethod<List<dynamic>>('getAvailableCommands');
    return result?.cast<String>() ?? [];
  }

  @override
  Future<bool> isAccessibilityEnabled() async {
    final result = await methodChannel.invokeMethod<bool>('isAccessibilityEnabled');
    return result ?? false;
  }

  @override
  Future<bool> openAccessibilitySettings() async {
    final result = await methodChannel.invokeMethod<bool>('openAccessibilitySettings');
    return result ?? false;
  }
}
