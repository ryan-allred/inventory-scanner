package com.example.pokemoninventory

data class InventoryItem(
    val upc: String,
    val name: String,
    val quantity: Int,
    val imageFileName: String? = null,
    val id: String = upc,
    val status: String? = null
)
