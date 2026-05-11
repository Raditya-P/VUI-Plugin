package com.example.vui_plugin

import android.util.Log

/**
 * NLP Engine for processing Indonesian voice commands with slot filling.
 * Uses rule-based keyword spotting and regex patterns for entity extraction.
 */
class NlpEngine {
    companion object {
        private const val TAG = "NlpEngine"
        
        // Current screen context for context-aware commands
        private var currentScreen: String = "beranda"
        
        fun setContext(screen: String?) {
            currentScreen = screen ?: "beranda"
            Log.d(TAG, "Screen context set to: $currentScreen")
        }
        
        fun getContext(): String = currentScreen
    }
    
    // Indonesian number words mapping
    private val numberWords = mapOf(
        "satu" to 1, "dua" to 2, "tiga" to 3, "empat" to 4, "lima" to 5,
        "enam" to 6, "tujuh" to 7, "delapan" to 8, "sembilan" to 9, "sepuluh" to 10,
        "sebelas" to 11, "dua belas" to 12, "seratus" to 100, "seribu" to 1000
    )
    
    /**
     * Process Indonesian voice input and return structured command with slots.
     */
    fun processText(text: String): VuiCommand? {
        Log.d(TAG, "Processing text: $text")
        var lowerText = text.lowercase().trim()
        // Normalize: Google Speech often recognizes Indonesian 'stok' as English 'stock' or 'stop'
        lowerText = lowerText.replace("stock", "stok")
        // 'stop' → 'stok' only when it appears in stok-related command patterns
        // e.g. "tambah stop" → "tambah stok", "isi stop" → "isi stok", "tambahkan stop" → "tambahkan stok"
        lowerText = lowerText.replace(Regex("""(?<=tambah |tambahkan |tambahin |isi |isikan |catat |masuk |masukkan |cek |lihat |riwayat )stop"""), "stok")
        // Also handle "stop saat ini" → "stok saat ini" (SET_FIELD for current stock)
        lowerText = lowerText.replace(Regex("""^stop\s+saat"""), "stok saat")
        // Handle standalone "stop" at the beginning followed by a number (SET_FIELD: "stok 10")
        lowerText = lowerText.replace(Regex("""^stop\s+(\d)"""), "stok $1")
        
        // Try SET_FIELD first to prevent "harga beli X" from matching QUICK_BUY
        // Then try quick commands, then navigation
        return tryParseSetField(lowerText)
            ?: tryParseQuickAddProduct(lowerText)
            ?: tryParseQuickSell(lowerText)
            ?: tryParseQuickBuy(lowerText)
            ?: tryParseNavigation(lowerText)
            ?: tryParseAddStock(lowerText)
            ?: tryParseSearchProduct(lowerText)
            ?: tryParseAddProduct(lowerText)
            ?: tryParseHelp(lowerText)
            ?: tryParseViewCommands(lowerText)
    }
    
    // ===== NAVIGATION COMMANDS =====
    
    private fun tryParseNavigation(text: String): VuiCommand? {
        return when {
            // Back navigation
            text.contains("kembali") || text.contains("balik") -> {
                VuiCommand(intent = "NAV_BACK", rawText = text)
            }
            // Navigate to product list
            text.contains("lihat produk") || text.contains("daftar produk") || 
            text.contains("buka produk") -> {
                VuiCommand(intent = "NAV_PRODUCT_LIST", rawText = text)
            }
            // Navigate to add product (only if no product name follows)
            text in listOf("tambah produk", "tambah barang", "tambahkan produk", "tambahkan barang",
                "tambahin produk", "tambahin barang", "produk baru", "barang baru",
                "bikin produk baru", "buat produk baru") -> {
                VuiCommand(intent = "NAV_ADD_PRODUCT", rawText = text)
            }
            // Navigate to stock history
            text.contains("lihat stok") || text.contains("riwayat stok") ||
            text.contains("cek stok") -> {
                VuiCommand(intent = "NAV_STOCK_HISTORY", rawText = text)
            }
            // Navigate to sales
            text.contains("lihat penjualan") || text.contains("buka penjualan") ||
            text.contains("catat penjualan") -> {
                VuiCommand(intent = "NAV_SALES", rawText = text)
            }
            // Navigate to add stock - check if product name is present
            (text.contains("isi stok") || text.contains("tambah stok") || text.contains("tambahkan stok") || text.contains("tambahin stok")) && !extractProductName(text).isNullOrEmpty() -> {
                // If product name mentioned, this is ADD_STOCK action, not navigation
                null
            }
            text.contains("isi stok") || text.contains("tambah stok") || text.contains("tambahkan stok") || text.contains("tambahin stok") -> {
                VuiCommand(intent = "NAV_ADD_STOCK", rawText = text)
            }
            else -> null
        }
    }
    
    // ===== ADD STOCK COMMAND =====
    
    private fun tryParseAddStock(text: String): VuiCommand? {
        val numPattern = """\d+|${numberWords.keys.joinToString("|")}"""
        
        // Patterns for "tambah stok" with flexible word order and verb variants
        val addStokPatterns = listOf(
            // "tambah stok chocolatos 5" / "tambahkan stok chocolatos 5" / "tambahin stok chocolatos 5"
            Regex("""(?:tambah|tambahkan|tambahin|isi|isikan|masukkan|masuk)\s+stok\s+(.+?)\s+($numPattern)(?:\s|$)"""),
            // "tambah stok 5 chocolatos" / "tambahkan stok 5 chocolatos"
            Regex("""(?:tambah|tambahkan|tambahin|isi|isikan|masukkan|masuk)\s+stok\s+($numPattern)\s+(.+)"""),
            // "tambahkan 5 stok chocolatos" / "tambahin 5 stok chocolatos"
            Regex("""(?:tambah|tambahkan|tambahin|isi|isikan|masukkan|masuk)\s+($numPattern)\s+stok\s+(.+)"""),
            // "stok chocolatos tambah 5" / "stok chocolatos plus 5"
            Regex("""stok\s+(.+?)\s+(?:tambah|tambahkan|tambahin|plus|ditambah)\s+($numPattern)"""),
            // "stok masuk chocolatos 5" / "stok masuk 5 chocolatos"
            Regex("""stok\s+masuk\s+(.+?)\s+($numPattern)(?:\s|$)"""),
            Regex("""stok\s+masuk\s+($numPattern)\s+(.+)""")
        )
        
        for (pattern in addStokPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val groups = match.groupValues
                val (productName, quantityStr) = if (groups[1].matches(Regex("""\d+"""))) {
                    Pair(groups[2].trim(), groups[1])
                } else {
                    Pair(groups[1].trim(), groups[2])
                }
                
                val quantity = parseNumber(quantityStr)
                if (productName.isNotEmpty() && quantity != null) {
                    Log.d(TAG, "ADD_STOCK detected: product=$productName, quantity=$quantity")
                    return VuiCommand(
                        intent = "ADD_STOCK",
                        slots = mapOf(
                            "product_name" to productName,
                            "quantity" to quantity.toString()
                        ),
                        rawText = text
                    )
                }
            }
        }
        return null
    }
    
    // ===== SEARCH PRODUCT =====
    
    private fun tryParseSearchProduct(text: String): VuiCommand? {
        val searchPatterns = listOf(
            Regex("""(?:cari|temukan|search)\s+(?:stok|produk|barang)?\s*(.+)"""),
            Regex("""(?:produk|barang)\s+(.+)""")
        )
        
        // Only match if it starts with search keyword
        if (text.startsWith("cari") || text.startsWith("temukan")) {
            for (pattern in searchPatterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val query = match.groupValues[1].trim()
                    if (query.isNotEmpty() && query.length > 1) {
                        Log.d(TAG, "SEARCH_PRODUCT detected: query=$query")
                        return VuiCommand(
                            intent = "SEARCH_PRODUCT",
                            slots = mapOf("query" to query),
                            rawText = text
                        )
                    }
                }
            }
        }
        return null
    }
    
    // ===== ADD PRODUCT WITH DETAILS =====
    
    private fun tryParseAddProduct(text: String): VuiCommand? {
        // Pattern: "tambah barang [name] harga [price]"
        val pattern = Regex("""(?:tambah|buat)\s+(?:barang|produk)\s+(.+?)(?:\s+harga\s+(\d+))?(?:\s|$)""")
        val match = pattern.find(text)
        
        if (match != null && text.contains("harga")) {
            val productName = match.groupValues[1].trim()
            val price = match.groupValues.getOrNull(2)?.trim()
            
            val slots = mutableMapOf("product_name" to productName)
            if (!price.isNullOrEmpty()) {
                slots["price"] = price
            }
            
            Log.d(TAG, "ADD_PRODUCT detected: $slots")
            return VuiCommand(
                intent = "ADD_PRODUCT",
                slots = slots,
                rawText = text
            )
        }
        return null
    }
    
    // ===== SET FIELD (for form filling) =====
    
    private fun tryParseSetField(text: String): VuiCommand? {
        // Only active on form screens
        if (currentScreen !in listOf("add_product", "tambah_stok", "edit_product")) {
            return null
        }
        
        val fieldPatterns = mapOf(
            // Basic fields
            "nama" to Regex("""(?:nama|nama produk|nama barang)\s+(.+)"""),
            // Harga fields - handle Rp prefix and ribu/juta
            "harga_jual" to Regex("""(?:harga jual|harga)\s+(?:rp\.?\s*)?([0-9.]+)\s*(ribu|juta|rb|jt)?""", RegexOption.IGNORE_CASE),
            "harga_beli" to Regex("""(?:harga beli|modal)\s+(?:rp\.?\s*)?([0-9.]+)\s*(ribu|juta|rb|jt)?""", RegexOption.IGNORE_CASE),
            "stok" to Regex("""(?:stok saat ini|jumlah stok|stok awal)\s+(\d+)"""),
            "kategori" to Regex("""(?:kategori)\s+(.+)"""),
            
            // Additional Add Product fields
            "jenis_kemasan" to Regex("""(?:jenis kemasan|kemasan)\s+(kardus|pcs|satuan)"""),
            "pcs_per_kemasan" to Regex("""(?:pcs per kemasan|isi kemasan|isi per kemasan|isi)\s+(\d+)"""),
            "minimum_order" to Regex("""(?:minimum order|minimal order|minimum)\s+(\d+)"""),
            "avg_penjualan" to Regex("""(?:rata-rata penjualan|rata rata|penjualan mingguan|penjualan)\s+(\d+)"""),
            "freq_isi_stok" to Regex("""(?:frekuensi isi stok|frekuensi stok|frekuensi)\s+(1x|2x|sekali|satu|dua|1|2|2 minggu sekali).*""", RegexOption.IGNORE_CASE),
            
            // TambahStok specific fields - handle Rp prefix and dot separators
            "total_transaksi" to Regex("""(?:total transaksi|total|transaksi)\s+(?:rp\.?\s*)?([0-9.]+)\s*(ribu|juta|rb|jt)?""", RegexOption.IGNORE_CASE),
            "jumlah_stok" to Regex("""(?:jumlah|qty|kuantitas)\s+(\d+)"""),
            "sisa_eceran" to Regex("""(?:sisa eceran|eceran)\s+(\d+)"""),
            
            // Metode kulakan
            "metode_kulakan" to Regex("""(?:metode kulakan|kulakan|metode)\s+(langsung|supplier)""", RegexOption.IGNORE_CASE)
        )
        
        for ((fieldName, pattern) in fieldPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                var value = match.groupValues[1].trim()
                val suffix = match.groupValues.getOrNull(2)?.lowercase() ?: ""
                
                // Normalize certain values
                if (fieldName == "jenis_kemasan") {
                    value = when (value.lowercase()) {
                        "kardus" -> "Kardus"
                        "pcs", "satuan" -> "pcs"  // Match TambahStokScreen dropdown
                        else -> value
                    }
                }
                if (fieldName == "freq_isi_stok") {
                    value = when (value.lowercase()) {
                        "1x", "1", "satu", "sekali" -> "1"
                        "2x", "2", "dua" -> "2"
                        "2 minggu sekali" -> "3"
                        else -> value
                    }
                }
                if (fieldName == "metode_kulakan") {
                    value = value.lowercase()
                }
                
                // Handle currency fields with ribu/juta suffix
                if (fieldName in listOf("harga_jual", "harga_beli", "total_transaksi")) {
                    // Remove dots from number (500.000 -> 500000)
                    value = value.replace(".", "")
                    
                    // Apply ribu/juta multiplier
                    val numValue = value.toDoubleOrNull() ?: 0.0
                    val multiplier = when (suffix) {
                        "ribu", "rb" -> 1000
                        "juta", "jt" -> 1000000
                        else -> 1
                    }
                    value = (numValue * multiplier).toLong().toString()
                }
                
                if (value.isNotEmpty()) {
                    Log.d(TAG, "SET_FIELD detected: field=$fieldName, value=$value")
                    return VuiCommand(
                        intent = "SET_FIELD",
                        slots = mapOf(
                            "field_name" to fieldName,
                            "value" to value
                        ),
                        rawText = text
                    )
                }
            }
        }
        return null
    }
    
    // ===== HELP COMMAND =====
    
    private fun tryParseHelp(text: String): VuiCommand? {
        if (text.contains("bantuan") || text.contains("tolong") || 
            text.contains("perintah apa") || text.contains("bisa apa")) {
            Log.d(TAG, "SHOW_HELP detected")
            return VuiCommand(
                intent = "SHOW_HELP",
                slots = mapOf("screen" to currentScreen),
                rawText = text
            )
        }
        return null
    }
    
    // ===== QUICK ADD PRODUCT COMMAND (for beranda) =====
    
    private fun tryParseQuickAddProduct(text: String): VuiCommand? {
        // Extended verb+noun pattern: tambah/tambahkan/tambahin/bikin/buat + produk/barang
        val verbNoun = """(?:tambah|tambahkan|tambahin|bikin|buat)\s+(?:produk|barang)(?:\s+baru)?"""
        
        // Pattern with price: "tambah produk X harga 5000" or "tambahkan produk X 5000"
        val patternWithPrice = Regex("""$verbNoun\s+(.+?)\s+(?:harga\s+)?(\d+)$""")
        // Pattern without price: "tambah produk X" / "tambahkan barang X"
        val patternNoPrice = Regex("""$verbNoun\s+(.+)""")
        
        // Try with price first
        val matchWithPrice = patternWithPrice.find(text)
        if (matchWithPrice != null) {
            val productName = matchWithPrice.groupValues[1].trim()
            val price = matchWithPrice.groupValues[2]
            if (productName.isNotEmpty() && productName !in listOf("produk", "barang", "baru")) {
                Log.d(TAG, "QUICK_ADD_PRODUCT detected: name=$productName, price=$price")
                return VuiCommand(
                    intent = "QUICK_ADD_PRODUCT",
                    slots = mapOf("product_name" to productName, "price" to price),
                    rawText = text
                )
            }
        }
        
        // Try without price
        val matchNoPrice = patternNoPrice.find(text)
        if (matchNoPrice != null) {
            val productName = matchNoPrice.groupValues[1].trim()
            // Only if product name is meaningful
            if (productName.isNotEmpty() && productName !in listOf("produk", "barang", "baru")) {
                Log.d(TAG, "QUICK_ADD_PRODUCT detected: name=$productName (no price)")
                return VuiCommand(
                    intent = "QUICK_ADD_PRODUCT",
                    slots = mapOf("product_name" to productName),
                    rawText = text
                )
            }
        }
        
        return null
    }
    
    // ===== QUICK SELL/BUY COMMANDS (for beranda) =====
    
    private fun tryParseQuickSell(text: String): VuiCommand? {
        // Extended verb patterns for selling
        val sellVerb = """(?:jual|jualkan|catat jual|catat penjualan)"""
        
        val sellPatterns = listOf(
            // "jual chocolatos 5" / "jualkan chocolatos 5" / "catat penjualan chocolatos 5"
            Regex("""$sellVerb\s+(.+?)\s+(\d+)$"""),
            // "jual 5 chocolatos" / "jualkan 5 chocolatos"
            Regex("""$sellVerb\s+(\d+)\s+(.+)"""),
            // "jual chocolatos" (no quantity)
            Regex("""$sellVerb\s+(.+)""")
        )
        
        for (pattern in sellPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val groups = match.groupValues
                if (groups.size >= 3) {
                    // Pattern with quantity (2 capture groups)
                    val (productName, quantityStr) = if (groups[1].matches(Regex("""\d+"""))) {
                        Pair(groups[2].trim(), groups[1])
                    } else {
                        Pair(groups[1].trim(), groups[2])
                    }
                    if (productName.isNotEmpty()) {
                        Log.d(TAG, "QUICK_SELL detected: product=$productName, quantity=$quantityStr")
                        return VuiCommand(
                            intent = "QUICK_SELL",
                            slots = mapOf("product_name" to productName, "quantity" to quantityStr),
                            rawText = text
                        )
                    }
                } else {
                    // Pattern without quantity (1 capture group)
                    val productName = groups[1].trim()
                    if (productName.isNotEmpty()) {
                        Log.d(TAG, "QUICK_SELL detected: product=$productName (no quantity)")
                        return VuiCommand(
                            intent = "QUICK_SELL",
                            slots = mapOf("product_name" to productName),
                            rawText = text
                        )
                    }
                }
            }
        }
        
        return null
    }
    
    private fun tryParseQuickBuy(text: String): VuiCommand? {
        // Extended verb+stok patterns
        val buyVerb = """(?:tambah|tambahkan|tambahin|catat|masuk|masukkan|isi|isikan)"""
        
        val buyPatterns = listOf(
            // With kardus unit:
            // "tambah stok chocolatos 5 kardus" / "tambahkan stok chocolatos 5 kardus"
            Regex("""$buyVerb\s+stok\s+(.+?)\s+(\d+)\s+kardus$"""),
            // "tambahkan 5 kardus stok chocolatos"
            Regex("""$buyVerb\s+(\d+)\s+kardus\s+(?:stok\s+)?(.+)"""),
            // "tambahkan 5 stok chocolatos kardus"
            Regex("""$buyVerb\s+(\d+)\s+stok\s+(.+?)\s+kardus$"""),
            
            // Without kardus:
            // "tambah stok chocolatos 5" / "tambahkan stok chocolatos 5"
            Regex("""$buyVerb\s+stok\s+(.+?)\s+(\d+)$"""),
            // "tambah stok 5 chocolatos" / "tambahkan stok 5 chocolatos"
            Regex("""$buyVerb\s+stok\s+(\d+)\s+(.+)"""),
            // "tambahkan 5 stok chocolatos" / "tambahin 5 stok chocolatos"
            Regex("""$buyVerb\s+(\d+)\s+stok\s+(.+)"""),
            // "stok masuk chocolatos 5"
            Regex("""stok\s+masuk\s+(.+?)\s+(\d+)$"""),
            // "stok masuk 5 chocolatos"
            Regex("""stok\s+masuk\s+(\d+)\s+(.+)"""),
            // "beli chocolatos 5" (standalone beli)
            Regex("""beli\s+(.+?)\s+(\d+)$"""),
            // "beli 5 chocolatos"
            Regex("""beli\s+(\d+)\s+(.+)"""),
            
            // No quantity:
            // "tambah stok chocolatos" / "tambahkan stok chocolatos"
            Regex("""$buyVerb\s+stok\s+(.+)"""),
            // "beli chocolatos"
            Regex("""beli\s+(.+)""")
        )
        
        for (pattern in buyPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val groups = match.groupValues
                
                // Check if this is a kardus pattern (first 3 patterns)
                val isKardusPattern = text.contains("kardus")
                
                if (groups.size >= 3) {
                    // Pattern with quantity (2 capture groups)
                    val (productName, quantityStr) = if (groups[1].matches(Regex("""\d+"""))) {
                        Pair(groups[2].trim(), groups[1])
                    } else {
                        Pair(groups[1].trim(), groups[2])
                    }
                    if (productName.isNotEmpty()) {
                        val slots = mutableMapOf("product_name" to productName, "quantity" to quantityStr)
                        if (isKardusPattern) slots["unit"] = "kardus"
                        Log.d(TAG, "QUICK_BUY detected: product=$productName, quantity=$quantityStr${if (isKardusPattern) ", unit=kardus" else ""}")
                        return VuiCommand(
                            intent = "QUICK_BUY",
                            slots = slots,
                            rawText = text
                        )
                    }
                } else {
                    // Pattern without quantity (1 capture group)
                    val productName = groups[1].trim()
                    if (productName.isNotEmpty()) {
                        Log.d(TAG, "QUICK_BUY detected: product=$productName (no quantity)")
                        return VuiCommand(
                            intent = "QUICK_BUY",
                            slots = mapOf("product_name" to productName),
                            rawText = text
                        )
                    }
                }
            }
        }
        
        return null
    }
    
    // ===== VIEW/LIST COMMANDS =====
    
    private fun tryParseViewCommands(text: String): VuiCommand? {
        return when {
            text.contains("buka kategori") || text.contains("lihat kategori") -> {
                VuiCommand(intent = "NAV_CATEGORY", rawText = text)
            }
            text.contains("beranda") || text.contains("halaman utama") || text.contains("home") -> {
                VuiCommand(intent = "NAV_HOME", rawText = text)
            }
            else -> null
        }
    }
    
    // ===== UTILITY FUNCTIONS =====
    
    private fun extractProductName(text: String): String? {
        val pattern = Regex("""(?:stok|produk)\s+([a-zA-Z\s]+)""")
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
    }
    
    private fun parseNumber(str: String): Int? {
        // Try direct number parsing
        str.toIntOrNull()?.let { return it }
        
        // Try Indonesian number words
        val lowerStr = str.lowercase()
        numberWords[lowerStr]?.let { return it }
        
        // Handle compound numbers like "dua puluh" (20)
        if (lowerStr.contains("puluh")) {
            val parts = lowerStr.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val tens = numberWords[parts[0]] ?: 0
                val ones = if (parts.size > 2) numberWords[parts[2]] ?: 0 else 0
                return tens * 10 + ones
            }
        }
        
        return null
    }
    
    /**
     * Get available commands for current screen (for help overlay)
     */
    fun getAvailableCommands(): List<String> {
        return listOf(
            "tambah produk [nama produk]",
            "jual [nama produk] [jumlah produk]",
            "tambah stok [nama produk] [jumlah produk]",
            "cari stok [nama produk]"
        )
    }
}
