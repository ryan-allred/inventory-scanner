package com.example.pokemoninventory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

data class ProductLookupResult(
    val title: String?,
    val imageUrls: List<String>
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

    fun lookup(upc: String): ProductLookupResult? {
        var connection: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(upc, "UTF-8")
            connection = URL("https://api.upcitemdb.com/prod/trial/lookup?upc=$encoded").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return null
            val item = items.optJSONObject(0) ?: return null
            val title = item.optString("title")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(::cleanTitle)
            val imageArray = item.optJSONArray("images")
            val imageUrls = if (imageArray == null) emptyList() else {
                (0 until imageArray.length()).map { imageArray.optString(it).trim() }
                    .filter { it.isNotBlank() && it != "null" }
            }
            ProductLookupResult(title, imageUrls)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
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
