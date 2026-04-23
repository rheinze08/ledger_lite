package com.voiceledger.lite.semantic

import android.content.Context
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.voiceledger.lite.data.LocalAiSettings
import kotlin.math.sqrt

class LocalEmbeddingEngine(private val context: Context) {
    suspend fun embed(text: String, settings: LocalAiSettings): FloatArray {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveEmbeddingModel(context, normalized)
        if (model != null) {
            runCatching { return embedWithModel(text, model.path) }
        }
        return embedHashed(text, normalized.embeddingDimensions)
    }

    private fun embedWithModel(text: String, modelPath: String): FloatArray {
        return TextEmbedder.createFromFile(context, modelPath).use { embedder ->
            val embedding = embedder.embed(text).embeddingResult().embeddings().firstOrNull()
                ?: error("Embedding model returned no vector.")
            embedding.toFloatArray()
        }
    }

    private fun Embedding.toFloatArray(): FloatArray {
        return when (val values = floatEmbedding() as Any?) {
            null -> error("Embedding model did not expose a float embedding.")
            is FloatArray -> values
            is List<*> -> values.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
            else -> error("Unsupported embedding container: ${values::class.java.name}")
        }
    }

    private fun embedHashed(text: String, dimensions: Int): FloatArray {
        val vector = FloatArray(dimensions)
        tokenize(text).forEachIndexed { position, token ->
            val firstIndex = positiveMod(token.hashCode(), dimensions)
            val secondIndex = positiveMod((token.hashCode() * 31) + position, dimensions)
            val weight = 1f + (position.coerceAtMost(24) / 24f)
            vector[firstIndex] += weight
            vector[secondIndex] += weight * 0.5f
        }
        return vector.normalized()
    }

    private fun FloatArray.normalized(): FloatArray {
        val magnitude = sqrt(sumOf { value -> value * value.toDouble() }).toFloat()
        if (magnitude <= 0f) {
            return this
        }
        return FloatArray(size) { index -> this[index] / magnitude }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { token -> token.length >= 2 }
    }

    private fun positiveMod(value: Int, size: Int): Int {
        val mod = value % size
        return if (mod < 0) mod + size else mod
    }
}
