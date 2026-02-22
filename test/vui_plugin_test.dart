import 'package:flutter_test/flutter_test.dart';
import 'package:vui_plugin/vui_plugin.dart';
import 'package:vui_plugin/vui_plugin_platform_interface.dart';
import 'package:vui_plugin/vui_plugin_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockVuiPluginPlatform
    with MockPlatformInterfaceMixin
    implements VuiPluginPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final VuiPluginPlatform initialPlatform = VuiPluginPlatform.instance;

  test('$MethodChannelVuiPlugin is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelVuiPlugin>());
  });

  test('getPlatformVersion', () async {
    VuiPlugin vuiPlugin = VuiPlugin();
    MockVuiPluginPlatform fakePlatform = MockVuiPluginPlatform();
    VuiPluginPlatform.instance = fakePlatform;

    expect(await vuiPlugin.getPlatformVersion(), '42');
  });
}
