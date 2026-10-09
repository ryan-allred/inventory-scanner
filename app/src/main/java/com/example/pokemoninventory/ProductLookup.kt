package com.example.pokemoninventory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ProductLookupResult(
    val title: String?,
    val imageUrls: List<String>
)

internal data class LookupBatchResult(
    val products: Map<String, ProductLookupResult?>? = null,
    val retryAfter: Long = 0,
    val permanentFailure: Boolean = false
)

object ProductLookup {
    private const val MAX_IMAGE_SOURCES = 8
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_THUMBNAIL_DIMENSION = 768

    private val yearBeforePokemonPrefix = Regex(
        """^\s*(?:19|20)\d{2}(?=[\s:：\-‐‑‒–—|/·•,;.]*pok[eé]mon(?=$|[\s:：\-‐‑‒–—|/·•,;.]))""",
        RegexOption.IGNORE_CASE
    )
    private val pokemonPrefix = Regex(
        """^\s*pok[eé]mon(?:(?:[\s:：\-‐‑‒–—|/·•,;.]*)?(?:tcg|trading[\s\-‐‑‒–—]*card[\s\-‐‑‒–—]*games?|card[\s\-‐‑‒–—]*games?))?(?=$|[\s:：\-‐‑‒–—|/·•,;.])""",
        RegexOption.IGNORE_CASE
    )

    internal fun lookupBatch(upcs: List<String>): LookupBatchResult {
        require(upcs.size in 1..2 && upcs.distinct().size == upcs.size)
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(upcs.joinToString(","), "UTF-8")
            connection = URL("https://api.upcitemdb.com/prod/trial/lookup?upc=$encoded").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val now = System.currentTimeMillis()
            val cooldown = serverCooldown(connection, now, status)
            if (status !in 200..299) return LookupBatchResult(
                retryAfter = cooldown,
                permanentFailure = status in 400..499 && status !in listOf(408, 429)
            )
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            runCatching { LookupBatchResult(parseBatch(upcs, body), cooldown) }
                .getOrElse { LookupBatchResult(retryAfter = cooldown) }
        } catch (_: Exception) {
            LookupBatchResult()
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseBatch(upcs: List<String>, body: String): Map<String, ProductLookupResult?> {
        val json = JSONObject(body)
        require(json.optString("code") == "OK") { "Lookup did not succeed" }
        val items = requireNotNull(json.optJSONArray("items")) { "Missing lookup results" }
        val results = upcs.associateWith { null as ProductLookupResult? }.toMutableMap()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val identifiers = listOf("upc", "ean", "gtin").map { item.optString(it) }
                .filter { it.isNotBlank() && it != "null" }.map(LookupPolicy::barcodeKey)
            val matching = upcs.filter { LookupPolicy.barcodeKey(it) in identifiers }
            require(matching.isNotEmpty()) { "Unrecognized barcode in lookup response" }
            val title = item.optString("title")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(::cleanTitle)
            val imageArray = item.optJSONArray("images")
            val imageUrls = if (imageArray == null) emptyList() else {
                (0 until imageArray.length()).map { imageArray.optString(it).trim() }
                    .filter { it.isNotBlank() && it != "null" }
            }
            matching.forEach { results[it] = ProductLookupResult(title, imageUrls) }
        }
        return results
    }

    private fun serverCooldown(connection: HttpURLConnection, now: Long, status: Int): Long {
        val retry = connection.getHeaderField("Retry-After")
        val retryAt = retry?.toLongOrNull()?.coerceAtLeast(0)?.let { now + it * 1000 }
            ?: retry?.let {
                runCatching {
                    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }.parse(it)?.time
                }.getOrNull()
            } ?: 0
        val exhausted = connection.getHeaderField("X-RateLimit-Remaining")?.toLongOrNull() == 0L
        val resetAt = if (exhausted || status == 429) {
            connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000) ?: 0
        } else 0
        return maxOf(retryAt, resetAt,
            if (status == 429 && retryAt <= now && resetAt <= now) now + 60_000 else 0)
    }

    fun downloadFirstValidImage(imageUrls: List<String>): ByteArray? {
        val attempted = mutableSetOf<String>()
        val count = minOf(imageUrls.size, MAX_IMAGE_SOURCES)
        for (index in 0 until count) {
            val source = imageUrls[index].trim()
            if (source.isBlank() || source == "null") continue
            val httpsSource = source.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
            if (!attempted.add(httpsSource)) continue
            downloadAndPrepareImage(httpsSource)?.let { return it }
        }
        return null
    }

    private fun downloadAndPrepareImage(source: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(source)
            if (url.protocol != "https") return null
            connection = url.openConnection() as? HttpURLConnection ?: return null
            connection.connectTimeout = 3000
            connection.readTimeout = 4000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8")
            connection.setRequestProperty("User-Agent", "PokemonSealedInventory/1.0")
            if (connection.responseCode !in 200..299) return null
            val contentLength = connection.contentLength
            if (contentLength > MAX_IMAGE_BYTES) return null

            val bytes = connection.inputStream.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
            prepareThumbnail(bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun prepareThumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_THUMBNAIL_DIMENSION) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun cleanTitle(title: String): String {
        val original = Normalizer.normalize(title, Normalizer.Form.NFC).trim()
        var cleaned = original
        val separators = ":：-‐‑‒–—|/·•,;."
        while (true) {
            val yearMatch = yearBeforePokemonPrefix.find(cleaned)
            if (yearMatch != null) {
                val afterYear = cleaned.substring(yearMatch.range.last + 1)
                    .trimStart { it.isWhitespace() || it in separators }
                if (afterYear != cleaned) {
                    cleaned = afterYear
                    continue
                }
            }

            val match = pokemonPrefix.find(cleaned) ?: break
            if (match.value.isBlank()) break
            val next = cleaned.substring(match.range.last + 1)
                .trimStart { it.isWhitespace() || it in separators }
                .trim()
            if (next.isBlank() || next == cleaned) break
            cleaned = next
        }
        return cleaned.ifBlank { original }
    }
}
