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
