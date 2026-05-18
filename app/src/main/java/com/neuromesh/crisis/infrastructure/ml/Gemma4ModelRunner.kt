package com.neuromesh.crisis.infrastructure.ml

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.neuromesh.crisis.util.Logger
import com.neuromesh.crisis.util.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class Gemma4ModelRunner @Inject constructor(
    private val context: Context,
    private val modelLoader: ModelLoader
) {
    private var llmInference: LlmInference? = null
    private var isInitialized = false
    private var streamingListener: ((String, Boolean) -> Unit)? = null

    /**
     * Pre-warms the model. Should be called early on low-RAM devices to
     * ensure it can load or fail gracefully before memory fills up.
     */
    suspend fun preload(): Result<Unit> {
        if (isReady()) return Result.Success(Unit)
        return initialize()
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelPath = modelLoader.getModelPath()
                ?: return@withContext Result.Error("Model file not found")

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(MAX_TOKENS)
                .setTopK(TOP_K)
                .setTemperature(TEMPERATURE)
                .setRandomSeed(RANDOM_SEED)
                .setResultListener { partialResult, done ->
                    streamingListener?.invoke(partialResult, done)
                }
                .setErrorListener { error ->
                    Logger.e(TAG, "LlmInference error: ${error.message}")
                }
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true
            Logger.d(TAG, "Gemma 4 model initialized at $modelPath")
            Result.Success(Unit)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize model", e)
            Result.Error("Model initialization failed: ${e.message}")
        }
    }

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        if (!isInitialized || llmInference == null) {
            return@withContext Result.Error("Model not initialized")
        }
        try {
            val response = llmInference!!.generateResponse(prompt)
            Logger.d(TAG, "Generated response (${response.length} chars)")
            Result.Success(response)
        } catch (e: Exception) {
            Logger.e(TAG, "Generation failed", e)
            Result.Error("Generation failed: ${e.message}")
        }
    }

    suspend fun generateStreaming(
        prompt: String,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.Default) {
        if (!isInitialized || llmInference == null) {
            return@withContext Result.Error("Model not initialized")
        }
        try {
            suspendCancellableCoroutine<Result<String>> { continuation ->
                val sb = StringBuilder()
                streamingListener = { partialResult, done ->
                    if (partialResult != null) {
                        sb.append(partialResult)
                        onToken(partialResult)
                    }
                    if (done) {
                        streamingListener = null
                        if (continuation.isActive) {
                            continuation.resume(Result.Success(sb.toString()))
                        }
                    }
                }
                llmInference!!.generateResponseAsync(prompt)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Streaming generation failed", e)
            Result.Error("Streaming failed: ${e.message}")
        }
    }

    fun isReady(): Boolean = isInitialized && llmInference != null

    fun close() {
        llmInference?.close()
        llmInference = null
        isInitialized = false
    }

    companion object {
        private const val TAG = "Gemma4ModelRunner"
        // Reduced from 1024 to 384 for low-RAM compatibility (2GB devices).
        // Each token in context increases KV-cache RAM usage.
        private const val MAX_TOKENS = 384
        private const val TOP_K = 40
        // Low temperature: we want reliable, schema-conformant JSON, not
        // creative variation. 0.7 was a major source of hallucinated crises
        // and malformed output.
        private const val TEMPERATURE = 0.2f
        private const val RANDOM_SEED = 42
    }
}