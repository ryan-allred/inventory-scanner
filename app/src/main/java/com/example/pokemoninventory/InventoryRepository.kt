package com.example.pokemoninventory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import android.util.Log
import androidx.room.Room
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class InventoryRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val serial = Executors.newSingleThreadScheduledExecutor()
    private val lookupIo = Executors.newSingleThreadExecutor()
    private val imageIo = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(List<InventoryItem>) -> Unit>()
    private val db = Room.databaseBuilder(app, InventoryDatabase::class.java, "inventory.db").build()
    private val engine = InventoryEngine(db, app.filesDir)
    private var lookupRunning = false
    private var imagesRunning = 0
    private var wake: ScheduledFuture<*>? = null
    private var active = false

    init {
        serial.execute { engine.initialize(InventoryStore(app).load()) }
    }

    // Listener membership is confined to the main thread; database work stays on serial.
    fun observe(listener: (List<InventoryItem>) -> Unit) {
        listeners.add(listener)
        serial.execute { active = true; publish(); pump() }
    }

    fun stopObserving(listener: (List<InventoryItem>) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) serial.execute { active = false; wake?.cancel(false) }
    }

    fun scan(upc: String) = mutate { engine.scan(upc.trim()) }
    fun addManual(upc: String, name: String) = mutate { engine.addManual(upc.trim(), name.trim()) }
    fun editName(id: String, name: String) = mutate { engine.editName(id, name.trim()) }
    fun changeQuantity(id: String, delta: Int) = mutate { engine.changeQuantity(id, delta) }
    fun remove(id: String) = mutate { engine.remove(id) }
    fun retry(id: String) = mutate { engine.retry(id) }

    private fun mutate(action: () -> Unit) {
        serial.execute {
            try { action(); publish(); pump() }
            catch (error: Exception) {
                Log.e("InventoryRepository", "Could not save inventory operation", error)
                main.post { android.widget.Toast.makeText(app, "Could not save inventory. Please retry.", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun publish() {
        val snapshot = engine.snapshot()
        main.post { listeners.toList().forEach { it(snapshot) } }
    }

    private fun pump() {
        wake?.cancel(false)
        if (!active) return
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        if (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true ||
            !network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            wake = serial.schedule({ pump() }, 30, TimeUnit.SECONDS)
            return
        }
        if (!lookupRunning) {
            val batch = engine.claimLookups()
            if (batch.isNotEmpty()) {
                lookupRunning = true
                publish()
                lookupIo.execute {
                    val result = ProductLookup.lookupBatch(batch.map { it.barcode })
                    serial.execute {
                        engine.completeLookups(batch, result)
                        lookupRunning = false
                        publish(); pump()
                    }
                }
            }
        }
        if (imagesRunning < 2) {
            engine.claimImages(2 - imagesRunning).forEach { job ->
                val urls = engine.imageUrls(job)
                imagesRunning++
                publish()
                imageIo.execute {
                    val fileName = runCatching {
                        val relative = "product-images/product_${job.upc}.png"
                        val file = File(app.filesDir, relative)
                        if (!file.isFile) {
                            val bytes = ProductLookup.downloadFirstValidImage(urls) ?: return@runCatching null
                            file.parentFile?.mkdirs()
                            val atomic = AtomicFile(file)
                            val output = atomic.startWrite()
                            try { output.write(bytes); atomic.finishWrite(output) }
                            catch (error: Exception) { atomic.failWrite(output); throw error }
                        }
                        relative
                    }.getOrNull()
                    serial.execute {
                        engine.completeImage(job, fileName)
                        imagesRunning--
                        publish(); pump()
                    }
                }
            }
        }
        engine.nextWakeAt(!lookupRunning, imagesRunning < 2)?.let { next ->
            wake = serial.schedule({ pump() }, (next - System.currentTimeMillis()).coerceIn(100, 60_000), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        @Volatile private var instance: InventoryRepository? = null
        fun get(context: Context): InventoryRepository = instance ?: synchronized(this) {
            instance ?: InventoryRepository(context).also { instance = it }
        }
    }
}
