import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'vui_plugin_method_channel.dart';

abstract class VuiPluginPlatform extends PlatformInterface {
  /// Constructs a VuiPluginPlatform.
  VuiPluginPlatform() : super(token: _token);

  static final Object _token = Object();

  static VuiPluginPlatform _instance = MethodChannelVuiPlugin();

  /// The default instance of [VuiPluginPlatform] to use.
  ///
  /// Defaults to [MethodChannelVuiPlugin].
  static VuiPluginPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [VuiPluginPlatform] when
  /// they register themselves.
  static set instance(VuiPluginPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }

  Stream<String> get onCommand {
    throw UnimplementedError('onCommand has not been implemented.');
  }
  
  /// Set the current screen context for context-aware commands
  Future<void> setCurrentScreen(String screenName) {
    throw UnimplementedError('setCurrentScreen() has not been implemented.');
  }
  
  /// Get available voice commands for current screen
  Future<List<String>> getAvailableCommands() {
    throw UnimplementedError('getAvailableCommands() has not been implemented.');
  }
}
