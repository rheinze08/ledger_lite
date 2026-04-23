package com.voiceledger.lite.semantic

import android.content.Context
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.voiceledger.lite.data.LocalAiSettings
import kotlin.math.sqrt

class LocalEmbeddingEngine(private val context: Context) {
    fun openEmbedder(settings: LocalAiSettings): PreparedEmbedder {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveEmbeddingModel(context, normalized)
        if (model != null) {
            runCatching {
                return ModelEmbedder(TextEmbedder.createFromFile(context, model.path))
            }
        }
        return HashedEmbedder(normalized.embeddingDimensions)
    }

    suspend fun embed(text: String, settings: LocalAiSettings): FloatArray {
        return openEmbedder(settings).use { embedder ->
            embedder.embed(text)
        }
    }

    interface PreparedEmbedder : AutoCloseable {
        fun embed(text: String): FloatArray

        override fun close() = Unit
    }

    private inner class ModelEmbedder(
        private val embedder: TextEmbedder,
    ) : PreparedEmbedder {
        override fun embed(text: String): FloatArray {
            val embedding = embedder.embed(text).embeddingResult().embeddings().firstOrNull()
                ?: error("Embedding model returned no vector.")
            return embedding.toFloatArray()
        }

        override fun close() {
            embedder.close()
        }
    }

    private inner class HashedEmbedder(
        private val dimensions: Int,
    ) : PreparedEmbedder {
        override fun embed(text: String): FloatArray = embedHashed(text, dimensions)
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
