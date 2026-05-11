## 1.0.1

* Simplified help commands: unified 4 global commands shown on all screens
* Help overlay no longer changes per screen context

## 1.0.0

* **Stable release** 🎉
* **Device compatibility improvements:**
  * Google Speech engine auto-binding for Samsung/OEM devices with automatic fallback to default engine if Google engine is unresponsive (3-second ready-timeout)
  * Volume-down double-press as alternative trigger for devices using gesture navigation (where accessibility button is unavailable)
  * Accessibility button availability detection with auto-fallback logging
  * Diagnostic broadcast on service connect (device info, speech engine status, button availability)
* **Enhanced NLP patterns:**
  * Flexible verb variants: `tambahkan`, `tambahin`, `masukkan`, `isikan`, `jualkan`, `bikin`
  * Inverted word order support: "tambahkan 5 stok chocolatos" alongside "tambah stok chocolatos 5"
  * Extended stop→stok normalization for new verb variants
  * Reordered regex patterns to prevent conflicts between specific and generic matches
* **Bug fixes:**
  * Product-not-found validation before navigation (shows error in speech overlay instead of navigating to empty screen)
  * `errorMessage` support in VuiSpeechOverlay for contextual error feedback
  * Improved `isActive` state detection in speech overlay

## 0.1.1

* Re-added `EXTRA_PREFER_OFFLINE` flag for offline speech recognition support
* Plugin now prefers offline language model when available, enabling fully offline usage
* Existing auto-retry and recreate-on-demand patterns handle devices without offline language packs

## 0.1.0

* Initial release
* On-device speech recognition with offline support (`EXTRA_PREFER_OFFLINE`)
* Rule-based NLP engine for Indonesian language
  * Keyword spotting with regex patterns
  * Slot filling for structured data extraction
  * Text normalization (e.g., "stock" → "stok", currency parsing with ribu/juta)
* Android Accessibility Service integration
  * Accessibility button for system-wide voice activation
  * Context-aware commands per screen
* Supported intents: SET_FIELD, QUICK_SELL, QUICK_BUY, ADD_PRODUCT, SEARCH_PRODUCT, NAV_BACK, SHOW_HELP
* Event-driven architecture via EventBus (pub/sub)
* Real-time speech feedback (partial results, listening state)
* Auto-retry on transient errors (network, recognizer busy)
* Watchdog timeout for stuck recognizer recovery
