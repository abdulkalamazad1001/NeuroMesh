package com.neuromesh.crisis.infrastructure.ml

import android.content.Context
import com.neuromesh.crisis.util.Logger
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelLoader @Inject constructor(private val context: Context) {

    private var cachedModelPath: String? = null

    fun getModelPath(): String? {
        cachedModelPath?.let { if (File(it).exists()) return it }

        // Aggressive memory save: Do not even try to extract or load the model 
        // if we know the RAM is physically insufficient. This saves 1.3GB of 
        // flash storage and significant RAM spikes during the extraction process.
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalRamMb = info.totalMem / (1024 * 1024)
        if (totalRamMb < 3000L) {
            Logger.w(TAG, "Low RAM device detected (${totalRamMb}MB). Skipping model extraction.")
            return null
        }

        val modelFile = File(context.filesDir, MODEL_FILENAME)
        if (modelFile.exists()) {
            cachedModelPath = modelFile.absolutePath
            Logger.d(TAG, "Model found at ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
            return cachedModelPath
        }

        return copyFromAssets(modelFile)
    }

    private fun copyFromAssets(dest: File): String? {
        return try {
            context.assets.open(MODEL_FILENAME).use { input ->
                FileOutputStream(dest).use { output ->
                    // Reduced buffer from 4MB to 512KB. 
                    // High buffers cause peak heap spikes that can trigger 
                    // the Low Memory Killer on 2GB devices during the first run.
                    val buffer = ByteArray(512 * 1024)
                    var read: Int
                    var totalBytes = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalBytes += read
                    }
                    output.flush()
                    // Force sync to disk to ensure the file isn't just in 
                    // kernel cache, which consumes "hidden" RAM.
                    output.fd.sync()
                    Logger.d(TAG, "Model copied from assets: ${totalBytes / 1024 / 1024}MB")
                }
            }
            cachedModelPath = dest.absolutePath
            dest.absolutePath
        } catch (e: Exception) {
            Logger.e(TAG, "Model not found in assets: ${e.message}")
            null
        }
    }

    fun isModelAvailable(): Boolean = getModelPath() != null

    fun getModelSizeMb(): Long {
        val path = getModelPath() ?: return 0L
        return File(path).length() / 1024 / 1024
    }

    companion object {
        private const val TAG = "ModelLoader"
        const val MODEL_FILENAME = "gemma4_e2b_q4.tflite"
    }
}