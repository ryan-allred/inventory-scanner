package com.example.pokemoninventory

import com.example.pokemoninventory.InventoryDatabase.Item
import com.example.pokemoninventory.InventoryDatabase.Job
import com.example.pokemoninventory.InventoryDatabase.Product
import com.example.pokemoninventory.InventoryDatabase.Request
import com.example.pokemoninventory.InventoryDatabase.Setting
import org.json.JSONArray
import java.io.File
import java.util.UUID

// Called only on the coordinator's serial executor. Network and image work run elsewhere.
internal class InventoryEngine(
    private val db: InventoryDatabase,
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val store = db.store()

    fun initialize(legacy: List<InventoryItem>) = db.runInTransaction {
        if (store.setting("legacyImported") == null) {
            legacy.forEach { old ->
                val key = if (validBarcode(old.upc)) LookupPolicy.barcodeKey(old.upc) else UUID.randomUUID().toString()
                val existing = store.item(key)
                store.putItem(Item().apply {
                    id = key; upc = old.upc; name = old.name
                    quantity = old.quantity + (existing?.quantity ?: 0)
                    nameEdited = old.name != "Unknown product"
                    imageFileName = old.imageFileName
                })
            }
            setting("legacyImported", 1)
        }
        store.jobs().filter { store.item(it.upc) == null }.forEach { store.deleteJob(it.upc, it.kind) }
        store.recoverJobs()
        store.items().filter { validBarcode(it.upc) }.forEach { ensureWork(it) }
    }

    fun scan(barcode: String) = db.runInTransaction {
        require(validBarcode(barcode)) { "Unsupported barcode" }
        val key = LookupPolicy.barcodeKey(barcode)
        val item = store.item(key) ?: Item().apply { id = key; upc = barcode; quantity = 0 }
        item.quantity++
        store.putItem(item)
        ensureWork(item)
    }

    fun addManual(barcode: String, name: String) = db.runInTransaction {
        val key = if (validBarcode(barcode)) LookupPolicy.barcodeKey(barcode) else UUID.randomUUID().toString()
        val item = store.item(key) ?: Item().apply { id = key; upc = barcode.ifBlank { "NO UPC" }; quantity = 0 }
        item.quantity++
        item.name = name.ifBlank { "Unknown product" }
        item.nameEdited = true
        store.putItem(item)
        if (validBarcode(item.upc)) ensureWork(item)
    }

    fun editName(id: String, name: String) = db.runInTransaction {
        store.item(id)?.let { item ->
            item.name = name.ifBlank { item.name }
            item.nameEdited = true
            store.putItem(item)
        }
    }

    fun changeQuantity(id: String, delta: Int) = db.runInTransaction {
        store.item(id)?.let { item ->
            item.quantity = maxOf(1, item.quantity + delta)
            store.putItem(item)
        }
    }

    fun remove(id: String) = db.runInTransaction {
        store.deleteItem(id)
        listOf("lookup", "image").forEach { kind ->
            // An active task still owns this barcode until its completion. Keeping the
            // claim prevents removal followed by a quick re-add from duplicating work.
            if (store.job(id, kind)?.state != "running") store.deleteJob(id, kind)
        }
        // Keep cached metadata and images for a future re-add. Completion never inserts inventory.
    }

    fun retry(id: String) = db.runInTransaction {
        val item = store.item(id) ?: return@runInTransaction
        if (!validBarcode(item.upc)) return@runInTransaction
        val lookup = store.job(id, "lookup")
        val image = store.job(id, "image")
        if (listOfNotNull(lookup, image).any { it.state != "paused" }) return@runInTransaction
        val product = store.product(id)
        if (product?.found == true && imageFile(product.imageFileName) == null && urls(product).isNotEmpty()) {
            store.deleteJob(id, "image")
            enqueue(item, "image")
        } else {
            store.deleteProduct(id)
            store.deleteJob(id, "lookup")
            store.deleteJob(id, "image")
            enqueue(item, "lookup")
        }
    }

    private fun ensureWork(item: Item) {
        val product = store.product(item.id)
        if (product == null) {
            enqueue(item, "lookup")
            return
        }
        if (!item.nameEdited) item.name = product.title?.takeIf(String::isNotBlank) ?: "Unknown product"
        val file = imageFile(product.imageFileName)
        if (file != null) item.imageFileName = product.imageFileName
        store.putItem(item)
        if (file == null && imageFile(item.imageFileName) == null && urls(product).isNotEmpty()) enqueue(item, "image")
    }

    private fun enqueue(item: Item, kind: String) = store.enqueue(Job().apply {
        upc = item.id; barcode = item.upc; this.kind = kind; createdAt = clock()
    })

    fun snapshot(): List<InventoryItem> {
        val jobs = store.jobs().associateBy { it.upc to it.kind }
        return store.items().map { item ->
            val lookup = jobs[item.id to "lookup"]
            val image = jobs[item.id to "image"]
            val product = store.product(item.id)
            val status = when {
                lookup?.state == "running" -> "Looking up…"
                lookup?.state == "paused" -> "Lookup failed · retry available"
                lookup != null -> "Waiting for lookup"
                image?.state == "running" -> "Loading image…"
                image?.state == "paused" -> "Image unavailable · retry available"
                image != null -> "Waiting for image"
                product != null && !product.found -> "No match · retry available"
                else -> null
            }
            InventoryItem(item.upc, item.name, item.quantity,
                item.imageFileName.takeIf { imageFile(it) != null }, item.id, status)
        }
    }

    fun nextLookupAt(): Long = LookupPolicy.nextRequestAt(clock(), store.requests(), store.setting("cooldown") ?: 0)

    fun claimLookups(): List<Job> {
        var batch = emptyList<Job>()
        db.runInTransaction {
            val now = clock()
            if (nextLookupAt() > now) return@runInTransaction
            batch = store.jobs().filter { it.kind == "lookup" && it.state == "queued" && it.nextAttemptAt <= now }.take(2)
            if (batch.isEmpty()) return@runInTransaction
            store.pruneRequests(now - LookupPolicy.DAY)
            store.reserve(Request().apply { attemptedAt = now })
            batch.forEach { it.state = "running"; it.attempts++; store.putJob(it) }
        }
        return batch
    }

    fun completeLookups(batch: List<Job>, result: LookupBatchResult) = db.runInTransaction {
        setting("cooldown", maxOf(store.setting("cooldown") ?: 0, result.retryAfter))
        batch.forEach { job ->
            val products = result.products
            if (products == null) {
                // Removal can cancel a job while its request is still finishing.
                val current = store.job(job.upc, "lookup")
                if (store.item(job.upc) == null) {
                    store.deleteJob(job.upc, "lookup")
                } else if (current?.state == "running") {
                    current.state = if (result.permanentFailure) "paused" else "queued"
                    current.nextAttemptAt = maxOf(result.retryAfter, LookupPolicy.retryAt(clock(), current.attempts))
                    store.putJob(current)
                }
            } else {
                val match = products[job.barcode]
                val previous = store.product(job.upc)
                store.putProduct(Product().apply {
                    upc = job.upc; title = match?.title; found = match != null
                    imageUrls = JSONArray(match?.imageUrls.orEmpty()).toString()
                    imageFileName = previous?.imageFileName ?: store.item(job.upc)?.imageFileName
                })
                store.deleteJob(job.upc, "lookup")
                store.item(job.upc)?.let(::ensureWork)
            }
        }
    }

    fun claimImages(limit: Int): List<Job> {
        var jobs = emptyList<Job>()
        db.runInTransaction {
            jobs = store.jobs().filter { it.kind == "image" && it.state == "queued" && it.nextAttemptAt <= clock() }.take(limit)
            jobs.forEach { it.state = "running"; it.attempts++; store.putJob(it) }
        }
        return jobs
    }

    fun imageUrls(job: Job): List<String> = store.product(job.upc)?.let(::urls).orEmpty()

    fun completeImage(job: Job, fileName: String?) = db.runInTransaction {
        if (fileName != null) {
            store.product(job.upc)?.let { it.imageFileName = fileName; store.putProduct(it) }
            store.item(job.upc)?.let { it.imageFileName = fileName; store.putItem(it) }
            store.deleteJob(job.upc, "image")
        } else {
            if (store.item(job.upc) == null) {
                store.deleteJob(job.upc, "image")
                return@runInTransaction
            }
            store.job(job.upc, "image")?.takeIf { it.state == "running" }?.let {
                it.state = if (it.attempts >= 5) "paused" else "queued"
                it.nextAttemptAt = LookupPolicy.retryAt(clock(), it.attempts)
                store.putJob(it)
            }
        }
    }

    fun nextWakeAt(lookupAvailable: Boolean, imageAvailable: Boolean): Long? = store.jobs().filter {
        it.state == "queued" && ((it.kind == "lookup" && lookupAvailable) || (it.kind == "image" && imageAvailable))
    }.minOfOrNull {
        if (it.kind == "lookup") maxOf(it.nextAttemptAt, nextLookupAt()) else it.nextAttemptAt
    }

    private fun imageFile(fileName: String?): File? = fileName?.let { File(filesDir, it) }?.takeIf { it.isFile }
    private fun urls(product: Product): List<String> = JSONArray(product.imageUrls).let { array ->
        (0 until array.length()).map { array.getString(it) }
    }
    private fun setting(key: String, value: Long) = store.putSetting(Setting().apply { this.key = key; this.value = value })
    private fun validBarcode(code: String) = code.length in listOf(8, 12, 13, 14) && code.all { it in '0'..'9' }
}
