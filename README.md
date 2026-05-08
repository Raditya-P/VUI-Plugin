# VUI Plugin

A Flutter plugin for **Voice User Interface (VUI)** using Android Accessibility Service. Provides fully on-device speech recognition with rule-based NLP for **Indonesian language**, enabling voice-controlled navigation, form filling, and command execution in Flutter apps.

[![pub package](https://img.shields.io/pub/v/vui_plugin.svg)](https://pub.dev/packages/vui_plugin)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## Features

- 🎙️ **On-device speech recognition** — Prefers offline processing for speed and privacy
- 🧠 **Rule-based NLP** — Indonesian keyword spotting & slot filling, no cloud dependency
- ♿ **Accessibility Service** — Uses Android Accessibility Button for system-wide voice activation
- 🔊 **Volume-key trigger** — Double-press volume-down as fallback for gesture navigation devices
- 🔄 **Google Speech auto-binding** — Forces Google engine on Samsung/OEM devices, auto-fallback to default if unresponsive
- 📡 **Event-driven architecture** — Pub/sub pattern via EventBus for loose coupling
- 🔁 **Auto-retry & watchdog** — Robust error recovery with automatic retries on transient failures
- 📱 **Context-aware commands** — Different voice commands available per screen
- 🗣️ **Live transcription** — Real-time partial speech results for responsive UI feedback

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  Flutter App                     │
│  ┌───────────┐   ┌──────────┐   ┌────────────┐ │
│  │ VuiPlugin  │◀──│ Platform │◀──│  Method    │ │
│  │ (Dart API) │   │ Interface│   │  Channel   │ │
│  └───────────┘   └──────────┘   └─────┬──────┘ │
├───────────────────────────────────────┼─────────┤
│              Android Native           │         │
│  ┌────────────────┐   ┌──────────────▼───────┐ │
│  │ Accessibility   │──▶│    VuiEventBus      │ │
│  │ Service         │   │    (Pub/Sub)        │ │
│  │ + SpeechRecog.  │   └──────────┬──────────┘ │
│  └───────┬────────┘              │             │
│          │                ┌──────▼──────────┐  │
│          └───────────────▶│   NlpEngine     │  │
│                           │ (Keyword Spot + │  │
│                           │  Slot Filling)  │  │
│                           └─────────────────┘  │
└─────────────────────────────────────────────────┘
```

## Getting Started

### Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  vui_plugin:
    path: packages/vui_plugin  # or from pub.dev
```

### Android Setup

#### 1. Create Accessibility Service Config

Create `android/app/src/main/res/xml/accessibility_service_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityFlags="flagDefault|flagRequestAccessibilityButton|flagRequestFilterKeyEvents"
    android:accessibilityFeedbackType="feedbackSpoken"
    android:notificationTimeout="100"
    android:canRetrieveWindowContent="true"
    android:canRequestFilterKeyEvents="true"
    android:description="@string/accessibility_service_description"
    android:settingsActivity="com.example.app.MainActivity" />
```

#### 2. Register in AndroidManifest.xml

```xml
<service
    android:name="com.example.vui_plugin.VuiAccessibilityService"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

#### 3. Add Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

#### 4. Download Offline Language Pack

For best performance, download the Indonesian speech recognition model:

**Settings → System → Languages → Speech → Offline speech recognition → Indonesian**

## Usage

### Basic Setup

```dart
import 'package:vui_plugin/vui_plugin.dart';

final vuiPlugin = VuiPlugin();

// Listen to voice commands
vuiPlugin.onVuiCommand.listen((command) {
  print('Intent: ${command.intent}');
  print('Slots: ${command.slots}');
  print('Raw text: ${command.rawText}');
});

// Set current screen for context-aware commands
await vuiPlugin.setCurrentScreen('beranda');

// Get available commands for current screen
final commands = await vuiPlugin.getAvailableCommands();
```

### VuiCommand Structure

```dart
class VuiCommand {
  final String intent;          // e.g., "SET_FIELD", "QUICK_SELL"
  final Map<String, String> slots;  // e.g., {"field_name": "harga_jual", "value": "5000"}
  final String rawText;         // Original speech text
  final double confidence;      // Recognition confidence (0.0 - 1.0)
}
```

### Speech State Events

The plugin emits `SPEECH_STATE` events for UI feedback:

| State | Description |
|-------|-------------|
| `LISTENING_START` | Microphone activated, ready for speech |
| `PARTIAL_TEXT` | Live transcription update |
| `LISTENING_END` | Speech input finished |
| `COMMAND_RECOGNIZED` | Valid command detected |
| `COMMAND_NOT_RECOGNIZED` | Speech heard but no matching command |
| `RETRYING` | Auto-retrying after transient error |
| `ERROR` | Recognition error (with `error_code`) |

### Supported Intents

| Intent | Description | Example Voice Command |
|--------|-------------|-----------------------|
| `SET_FIELD` | Fill a form field | "harga jual 5000" |
| `QUICK_SELL` | Quick sale action | "jual indomie 3" / "jualkan 3 indomie" |
| `QUICK_BUY` | Quick stock addition | "tambah stok indomie 10" / "tambahkan 10 stok indomie" |
| `ADD_PRODUCT` | Navigate to add product | "tambah produk" / "bikin produk baru" |
| `SEARCH_PRODUCT` | Search for a product | "cari indomie" |
| `NAV_BACK` | Navigate back | "kembali" |
| `SHOW_HELP` | Show help overlay | "bantuan" |

## Customization

### Adding Custom NLP Patterns

Extend `NlpEngine.kt` to add domain-specific voice commands:

```kotlin
// In tryParseSetField(), add new field patterns:
val fieldPatterns = mapOf(
    "your_field" to Regex("""(?:your trigger words)\s+(.+)"""),
    // ... existing patterns
)
```

### Adding New Screens

Register new screen commands in `getAvailableCommands()`:

```kotlin
"your_screen" -> listOf(
    "command pattern 1",
    "command pattern 2",
)
```

## Requirements

- **Android** 8.0 (API 26) or higher
- **Flutter** 3.3.0 or higher
- Accessibility Service must be enabled by the user
- Microphone permission required

## License

MIT License — see [LICENSE](LICENSE) for details.
