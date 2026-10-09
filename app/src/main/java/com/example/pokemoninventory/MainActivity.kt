package com.example.pokemoninventory

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import java.io.File
import java.io.OutputStreamWriter

class MainActivity : AppCompatActivity() {
    private val repository by lazy { InventoryRepository.get(this) }
    private val items = mutableListOf<InventoryItem>()
    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView
    private val inventoryListener: (List<InventoryItem>) -> Unit = { snapshot ->
        items.clear()
        items.addAll(snapshot)
        render()
    }
    private val imageCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = maxOf(1, value.byteCount / 1024)
    }

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
        }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(actions)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        summary = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(76, 92, 99)) }
        toolbar.addView(summary, LinearLayout.LayoutParams(-1, -2))
        val secondaryActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        secondaryActions.addView(actionButton("Add manually", false) { showAddDialog("", "") },
            LinearLayout.LayoutParams(-2, dp(48)))
        secondaryActions.addView(Space(this), LinearLayout.LayoutParams(dp(10), 1))
        secondaryActions.addView(actionButton("Export CSV", false) {
            exportLauncher.launch("pokemon-sealed-inventory.csv")
        }, LinearLayout.LayoutParams(-2, dp(48)))
        toolbar.addView(secondaryActions, LinearLayout.LayoutParams(-1, -2))
        root.addView(toolbar)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val safeArea = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(20, 42, 54))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(safeArea)
        safeArea.applySafeAreaInsets()
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = if (primary) 19f else 13f
        setTypeface(null, android.graphics.Typeface.BOLD)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), 0, dp(16), 0)
        setTextColor(if (primary) Color.WHITE else Color.rgb(20, 42, 54))
        backgroundTintList = null
        background = buttonBackground(
            if (primary) Color.rgb(214, 75, 53) else Color.WHITE,
            if (primary) Color.rgb(180, 57, 38) else Color.rgb(190, 205, 212),
            if (primary) 0x33FFFFFF else 0x22142A36
        )
        elevation = dp(if (primary) 4 else 1).toFloat()
        setOnClickListener { onClick() }
    }

    private fun buttonBackground(fill: Int, border: Int, ripple: Int): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), border)
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
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
            val contentRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            item.imageFileName?.let(::loadProductThumbnail)?.let { bitmap ->
                contentRow.addView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.rgb(245, 247, 248))
                    contentDescription = "Image of ${item.name}"
                }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(12) })
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val titleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply {
                text = item.name
                textSize = 17f
                setTextColor(Color.rgb(24, 43, 51))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            titleRow.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val minus = qtyButton("−") { changeQuantity(item.id, -1) }
            val qty = TextView(this).apply {
                text = item.quantity.toString()
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(24, 43, 51))
            }
            val plus = qtyButton("+") { changeQuantity(item.id, 1) }
            minus.contentDescription = "Decrease quantity of ${item.name}"
            plus.contentDescription = "Increase quantity of ${item.name}"
            details.addView(titleRow)
            details.addView(TextView(this).apply {
                text = "UPC  ${item.upc}"
                textSize = 13f
                setTextColor(Color.rgb(103, 119, 126))
                setPadding(0, dp(6), 0, 0)
            })
            val quantityRow = LinearLayout(this).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
                addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(qty, LinearLayout.LayoutParams(dp(42), dp(48)))
                addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            details.addView(quantityRow, LinearLayout.LayoutParams(-1, -2))
            item.status?.let { status ->
                details.addView(TextView(this).apply {
                    text = status
                    textSize = 12f
                    setTextColor(Color.rgb(103, 119, 126))
                    setPadding(0, dp(8), 0, 0)
                })
            }
            contentRow.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(contentRow)
            card.setOnLongClickListener { showEditDialog(item); true }
            listContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }
    }

    private fun qtyButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 20f
        setTypeface(null, android.graphics.Typeface.BOLD)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0)
        setTextColor(Color.rgb(20, 42, 54))
        backgroundTintList = null
        background = buttonBackground(Color.rgb(235, 240, 242), Color.rgb(180, 198, 207), 0x22142A36)
        elevation = dp(2).toFloat()
        setOnClickListener { onClick() }
    }

    private fun onScanned(upc: String) {
        repository.scan(upc)
    }

    private fun loadProductThumbnail(fileName: String): Bitmap? {
        imageCache.get(fileName)?.let { return it }
        val file = File(filesDir, fileName)
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= 512) sampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return null
        imageCache.put(fileName, bitmap)
        return bitmap
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
        AlertDialog.Builder(this).setTitle("Add product")
            .setMessage(if (suggestedName.isBlank() && upc.isNotBlank()) "No product name was found. Enter one below." else null)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                val finalUpc = upcField.text.toString().trim()
                val finalName = nameField.text.toString().trim().ifBlank { "Unknown product" }
                repository.addManual(finalUpc, finalName)
            }.show()
    }

    private fun showEditDialog(item: InventoryItem) {
        val field = EditText(this).apply { setText(item.name); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        AlertDialog.Builder(this).setTitle("Edit product name")
            .setView(field)
            .setItems(if (item.upc.all(Char::isDigit)) arrayOf("Retry product details / image") else emptyArray()) { _, _ ->
                repository.retry(item.id)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove") { _, _ ->
                repository.remove(item.id)
            }
            .setPositiveButton("Save") { _, _ ->
                repository.editName(item.id, field.text.toString())
            }.show()
    }

    private fun changeQuantity(id: String, delta: Int) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        val item = items[index]
        if (delta < 0 && item.quantity == 1) {
            AlertDialog.Builder(this).setMessage("Remove ${item.name} from your inventory?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove") { _, _ ->
                    repository.remove(item.id)
                }.show()
        } else {
            repository.changeQuantity(item.id, delta)
        }
    }

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

    override fun onStart() {
        super.onStart()
        repository.observe(inventoryListener)
    }

    override fun onStop() {
        repository.stopObserving(inventoryListener)
        super.onStop()
    }
}
