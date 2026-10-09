package com.example.pokemoninventory

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val store by lazy { InventoryStore(this) }
    private val items = mutableListOf<InventoryItem>()
    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView
    private val io = Executors.newSingleThreadExecutor()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val upc = result.data?.getStringExtra(ScannerActivity.EXTRA_UPC) ?: return@registerForActivityResult
            onScanned(upc)
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportCsv(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items.addAll(store.load())
        buildUi()
        render()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 247, 248))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20))
            setBackgroundColor(Color.rgb(20, 42, 54))
        }
        header.addView(TextView(this).apply {
            text = "SEALED POKÉMON\nINVENTORY"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "Keep track of every sealed product in your collection."
            textSize = 14f
            setTextColor(Color.rgb(203, 218, 224))
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(header)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        actions.addView(actionButton("Scan UPC", true) {
            scanLauncher.launch(Intent(this, ScannerActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        actions.addView(actionButton("Add manually", false) { showAddDialog("", "") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)

        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        summary = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(76, 92, 99)) }
        toolbar.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(actionButton("Export CSV", false) {
            exportLauncher.launch("pokemon-sealed-inventory.csv")
        }, LinearLayout.LayoutParams(-2, dp(40)))
        root.addView(toolbar)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else Color.rgb(20, 42, 54))
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) Color.rgb(214, 75, 53) else Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun render() {
        listContainer.removeAllViews()
        val total = items.sumOf { it.quantity }
        summary.text = "${items.size} ${if (items.size == 1) "product" else "products"}  ·  $total total items"
        if (items.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "Your inventory is empty.\n\nScan a UPC to add your first sealed product."
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(93, 109, 116))
                setPadding(dp(30))
            }, LinearLayout.LayoutParams(-1, dp(230)))
            return
        }
        items.sortedBy { it.name.lowercase() }.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16))
                setBackgroundColor(Color.WHITE)
                elevation = dp(2).toFloat()
            }
            val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply {
                text = item.name
                textSize = 17f
                setTextColor(Color.rgb(24, 43, 51))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            titleRow.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val minus = qtyButton("−") { changeQuantity(item.upc, -1) }
            val qty = TextView(this).apply {
                text = item.quantity.toString()
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(24, 43, 51))
            }
            val plus = qtyButton("+") { changeQuantity(item.upc, 1) }
            titleRow.addView(minus, LinearLayout.LayoutParams(dp(38), dp(38)))
            titleRow.addView(qty, LinearLayout.LayoutParams(dp(42), dp(38)))
            titleRow.addView(plus, LinearLayout.LayoutParams(dp(38), dp(38)))
            card.addView(titleRow)
            card.addView(TextView(this).apply {
                text = "UPC  ${item.upc}"
                textSize = 13f
                setTextColor(Color.rgb(103, 119, 126))
                setPadding(0, dp(6), 0, 0)
            })
            card.setOnLongClickListener { showEditDialog(item); true }
            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun qtyButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0)
        setTextColor(Color.rgb(20, 42, 54))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(235, 240, 242))
        setOnClickListener { onClick() }
    }

    private fun onScanned(upc: String) {
        val existing = items.firstOrNull { it.upc == upc }
        if (existing != null) {
            changeQuantity(upc, 1)
            Toast.makeText(this, "Added to existing ${existing.name}", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Looking up product…", Toast.LENGTH_SHORT).show()
        io.execute {
            val name = ProductLookup.lookup(upc).orEmpty()
            runOnUiThread {
                if (items.any { it.upc == upc }) changeQuantity(upc, 1) else showAddDialog(upc, name)
            }
        }
    }

    private fun showAddDialog(upc: String, suggestedName: String) {
        val nameField = EditText(this).apply {
            hint = "Product name"
            setText(suggestedName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val upcField = EditText(this).apply {
            hint = "UPC (optional)"
            setText(upc)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), 0)
            addView(nameField)
            addView(upcField)
        }
        AlertDialog.Builder(this).setTitle(if (upc.isBlank()) "Add product" else "Confirm product")
            .setMessage(if (suggestedName.isBlank() && upc.isNotBlank()) "No product name was found. Enter one below." else null)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val finalUpc = upcField.text.toString().trim()
                val finalName = nameField.text.toString().trim().ifBlank { "Unknown product" }
                val existing = items.indexOfFirst { it.upc == finalUpc && finalUpc.isNotBlank() }
                if (existing >= 0) {
                    val old = items[existing]
                    items[existing] = old.copy(quantity = old.quantity + 1, name = finalName.ifBlank { old.name })
                } else {
                    items.add(InventoryItem(finalUpc.ifBlank { "NO UPC" }, finalName, 1))
                }
                persistAndRender()
            }.show()
    }

    private fun showEditDialog(item: InventoryItem) {
        val field = EditText(this).apply { setText(item.name); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        AlertDialog.Builder(this).setTitle("Edit product name")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove") { _, _ -> items.removeAll { it.upc == item.upc }; persistAndRender() }
            .setPositiveButton("Save") { _, _ ->
                val index = items.indexOfFirst { it.upc == item.upc }
                if (index >= 0) items[index] = items[index].copy(name = field.text.toString().trim().ifBlank { item.name })
                persistAndRender()
            }.show()
    }

    private fun changeQuantity(upc: String, delta: Int) {
        val index = items.indexOfFirst { it.upc == upc }
        if (index < 0) return
        val item = items[index]
        if (delta < 0 && item.quantity == 1) {
            AlertDialog.Builder(this).setMessage("Remove ${item.name} from your inventory?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove") { _, _ -> items.removeAt(index); persistAndRender() }.show()
        } else {
            items[index] = item.copy(quantity = item.quantity + delta)
            persistAndRender()
        }
    }

    private fun persistAndRender() { store.save(items); render() }

    private fun exportCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write("UPC,Product Name,Quantity\r\n")
                    items.forEach { item ->
                        writer.write("${csv(item.upc)},${csv(item.name)},${item.quantity}\r\n")
                    }
                }
            }
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not export CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
