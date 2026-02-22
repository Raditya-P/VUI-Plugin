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
        // Normalize: Google Speech often recognizes Indonesian 'stok' as English 'stock'
        lowerText = lowerText.replace("stock", "stok")
        
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
            (text == "tambah produk" || text == "tambah barang" || 
             text == "produk baru" || text == "barang baru") -> {
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
            // Navigate to add stock
            text.contains("isi stok") || text.contains("tambah stok") && !extractProductName(text).isNullOrEmpty() -> {
                // If product name mentioned, this is ADD_STOCK action, not navigation
                null
            }
            text.contains("isi stok") || (text.contains("tambah stok") && extractProductName(text).isNullOrEmpty()) -> {
                VuiCommand(intent = "NAV_ADD_STOCK", rawText = text)
            }
            else -> null
        }
    }
    
    // ===== ADD STOCK COMMAND =====
    
    private fun tryParseAddStock(text: String): VuiCommand? {
        // Pattern: "tambah stok [product] [quantity]" or "isi stok [product] [quantity]"
        val addStokPatterns = listOf(
            Regex("""(?:tambah|isi)\s+stok\s+(.+?)\s+(\d+|${numberWords.keys.joinToString("|")})(?:\s|$)"""),
            Regex("""(?:tambah|isi)\s+stok\s+(\d+|${numberWords.keys.joinToString("|")})\s+(.+)"""),
            Regex("""stok\s+(.+?)\s+(?:tambah|plus)\s+(\d+|${numberWords.keys.joinToString("|")})""")
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
            Regex("""(?:cari|temukan|search)\s+(?:produk\s+)?(.+)"""),
            Regex("""(?:produk|barang)\s+(.+)""")
        )
        
        // Only match if it starts with search keyword
        if (text.startsWith("cari") || text.startsWith("temukan")) {
            for (pattern in searchPatterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val query = match.groupValues[1].trim()
                    if (query.isNotEmpty() && query.length > 2) {
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
            "stok" to Regex("""(?:stok|jumlah stok|stok awal)\s+(\d+)"""),
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
        // Pattern: "tambah produk [nama produk] [harga]" - price is optional
        val patternWithPrice = Regex("""(?:tambah produk|tambah barang)\s+(.+?)\s+(?:harga\s+)?(\d+)$""")
        val patternNoPrice = Regex("""(?:tambah produk|tambah barang)\s+(.+)""")
        
        // Try with price first
        val matchWithPrice = patternWithPrice.find(text)
        if (matchWithPrice != null) {
            val productName = matchWithPrice.groupValues[1].trim()
            val price = matchWithPrice.groupValues[2]
            if (productName.isNotEmpty() && productName != "produk" && productName != "barang") {
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
            // Only if product name is meaningful (not just "produk" or "barang")
            if (productName.isNotEmpty() && productName != "produk" && productName != "barang") {
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
        // Pattern: "jual [nama produk] [jumlah]" - quantity is optional at the end
        val patternWithQty = Regex("""(?:jual|catat jual)\s+(.+?)\s+(\d+)$""")
        val patternNoQty = Regex("""(?:jual|catat jual)\s+(.+)""")
        
        // Try with quantity first
        val matchWithQty = patternWithQty.find(text)
        if (matchWithQty != null) {
            val productName = matchWithQty.groupValues[1].trim()
            val quantity = matchWithQty.groupValues[2]
            if (productName.isNotEmpty()) {
                Log.d(TAG, "QUICK_SELL detected: product=$productName, quantity=$quantity")
                return VuiCommand(
                    intent = "QUICK_SELL",
                    slots = mapOf("product_name" to productName, "quantity" to quantity),
                    rawText = text
                )
            }
        }
        
        // Try without quantity
        val matchNoQty = patternNoQty.find(text)
        if (matchNoQty != null) {
            val productName = matchNoQty.groupValues[1].trim()
            if (productName.isNotEmpty()) {
                Log.d(TAG, "QUICK_SELL detected: product=$productName (no quantity)")
                return VuiCommand(
                    intent = "QUICK_SELL",
                    slots = mapOf("product_name" to productName),
                    rawText = text
                )
            }
        }
        
        return null
    }
    
    private fun tryParseQuickBuy(text: String): VuiCommand? {
        // Pattern: "tambah stok [nama produk] [jumlah] kardus" - with kardus unit
        val patternWithKardus = Regex("""(?:tambah stok|catat stok|masuk stok|beli)\s+(.+?)\s+(\d+)\s+kardus$""")
        // Pattern: "tambah stok [nama produk] [jumlah]" - default (biji/satuan)
        val patternWithQty = Regex("""(?:tambah stok|catat stok|masuk stok|beli)\s+(.+?)\s+(\d+)$""")
        val patternNoQty = Regex("""(?:tambah stok|catat stok|masuk stok|beli)\s+(.+)""")
        
        // Try with kardus unit first
        val matchWithKardus = patternWithKardus.find(text)
        if (matchWithKardus != null) {
            val productName = matchWithKardus.groupValues[1].trim()
            val quantity = matchWithKardus.groupValues[2]
            if (productName.isNotEmpty()) {
                Log.d(TAG, "QUICK_BUY detected: product=$productName, quantity=$quantity, unit=kardus")
                return VuiCommand(
                    intent = "QUICK_BUY",
                    slots = mapOf("product_name" to productName, "quantity" to quantity, "unit" to "kardus"),
                    rawText = text
                )
            }
        }
        
        // Try with quantity (default to biji)
        val matchWithQty = patternWithQty.find(text)
        if (matchWithQty != null) {
            val productName = matchWithQty.groupValues[1].trim()
            val quantity = matchWithQty.groupValues[2]
            if (productName.isNotEmpty()) {
                Log.d(TAG, "QUICK_BUY detected: product=$productName, quantity=$quantity")
                return VuiCommand(
                    intent = "QUICK_BUY",
                    slots = mapOf("product_name" to productName, "quantity" to quantity),
                    rawText = text
                )
            }
        }
        
        // Try without quantity
        val matchNoQty = patternNoQty.find(text)
        if (matchNoQty != null) {
            val productName = matchNoQty.groupValues[1].trim()
            if (productName.isNotEmpty()) {
                Log.d(TAG, "QUICK_BUY detected: product=$productName (no quantity)")
                return VuiCommand(
                    intent = "QUICK_BUY",
                    slots = mapOf("product_name" to productName),
                    rawText = text
                )
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
        return when (currentScreen) {
            "beranda" -> listOf(
                "tambah produk [nama] [harga]",
                "jual [nama produk]",
                "tambah stok [nama produk]",
                "lihat produk",
                "lihat stok",
                "catat penjualan"
            )
            "product_list" -> listOf(
                "cari [nama produk]",
                "tambah produk",
                "kembali",
                "bantuan"
            )
            "add_product", "edit_product" -> listOf(
                "nama [nama produk]",
                "harga [jumlah]",
                "harga beli [jumlah]",
                "stok [jumlah]",
                "sisa eceran [jumlah]",
                "kategori [nama]",
                "kemasan [kardus/satuan(pcs)]",
                "kulakan [langsung/supplier]",
                "kembali"
            )
            "tambah_stok" -> listOf(
                "tambah stok [produk] [jumlah]",
                "tambah stok [produk] [jumlah] kardus",
                "stok [jumlah]",
                "sisa eceran [jumlah]",
                "kembali",
                "bantuan"
            )
            "kasir" -> listOf(
                "jual [nama produk] [jumlah]",
                "kembali",
                "bantuan"
            )
            else -> listOf(
                "kembali",
                "beranda",
                "bantuan"
            )
        }
    }
}
