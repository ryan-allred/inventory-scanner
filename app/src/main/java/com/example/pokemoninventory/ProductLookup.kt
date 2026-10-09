package com.example.pokemoninventory

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ProductLookup {
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
            items.optJSONObject(0)?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
