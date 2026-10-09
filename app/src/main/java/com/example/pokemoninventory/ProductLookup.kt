package com.example.pokemoninventory

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

object ProductLookup {
    private val pokemonPrefix = Regex(
        """^\s*pok[eé]mon(?:(?:[\s:：\-‐‑‒–—|/·•,;.]*)?(?:tcg|trading[\s\-‐‑‒–—]*card[\s\-‐‑‒–—]*game|card[\s\-‐‑‒–—]*game))?(?=$|[\s:：\-‐‑‒–—|/·•,;.])""",
        RegexOption.IGNORE_CASE
    )

    fun lookup(upc: String): String? {
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
            items.optJSONObject(0)?.optString("title")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let(::removePokemonPrefix)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun removePokemonPrefix(title: String): String {
        val original = Normalizer.normalize(title, Normalizer.Form.NFC).trim()
        var cleaned = original
        val separators = ":：-‐‑‒–—|/·•,;."
        while (true) {
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
