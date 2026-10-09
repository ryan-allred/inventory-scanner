package com.example.pokemoninventory

internal object LookupPolicy {
    const val MINUTE = 60_000L
    const val DAY = 86_400_000L

    // A rolling daily window is conservative: the server can reset sooner, but we never
    // invent additional quota after restarting. Future timestamps also survive clock rollback.
    fun nextRequestAt(now: Long, requests: List<Long>, serverCooldown: Long): Long {
        val minute = requests.filter { it > now - MINUTE }.sorted()
        val day = requests.filter { it > now - DAY }.sorted()
        return maxOf(now, serverCooldown,
            if (minute.size >= 6) minute[minute.size - 6] + MINUTE + 100 else now,
            if (day.size >= 100) day[day.size - 100] + DAY + 100 else now)
    }

    fun retryAt(now: Long, attempts: Int): Long =
        now + minOf(3_600_000L, 30_000L * (1L shl (attempts - 1).coerceIn(0, 7)))

    // UPC-A, its zero-prefixed EAN-13, and GTIN-14 share a key. Keep the original
    // barcode for display and transmission; do not parse it as a number.
    fun barcodeKey(code: String): String = code.trim().padStart(14, '0')

    fun expandUpce(code: String): String {
        if (code.length != 8 || code[0] !in "01" || !code.all { it in '0'..'9' }) return code
        val data = code.substring(1, 7)
        val body = when (data[5]) {
            '0', '1', '2' -> data.substring(0, 2) + data[5] + "0000" + data.substring(2, 5)
            '3' -> data.substring(0, 3) + "00000" + data.substring(3, 5)
            '4' -> data.substring(0, 4) + "00000" + data[4]
            else -> data.substring(0, 5) + "0000" + data[5]
        }
        return code[0] + body + code[7]
    }
}
