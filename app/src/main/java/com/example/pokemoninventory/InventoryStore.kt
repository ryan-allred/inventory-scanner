package com.example.pokemoninventory

import android.content.Context
import org.json.JSONArray

// Read-only importer for installations that used SharedPreferences before Room.
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

}
