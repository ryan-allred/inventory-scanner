package com.example.pokemoninventory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class InventoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("inventory", Context.MODE_PRIVATE)

    fun load(): MutableList<InventoryItem> {
        val result = mutableListOf<InventoryItem>()
        val json = prefs.getString("items", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    InventoryItem(
                        obj.getString("upc"),
                        obj.getString("name"),
                        obj.getInt("quantity"),
                        obj.optString("imageFileName").takeIf { it.isNotBlank() && it != "null" }
                    )
                )
            }
        }
        return result
    }

    fun save(items: List<InventoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject()
                .put("upc", item.upc)
                .put("name", item.name)
                .put("quantity", item.quantity)
            item.imageFileName?.let { obj.put("imageFileName", it) }
            array.put(obj)
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
