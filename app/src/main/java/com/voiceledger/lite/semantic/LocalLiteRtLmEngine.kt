package com.voiceledger.lite.semantic

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.voiceledger.lite.data.LocalAiSettings
import java.io.File

class LocalLiteRtLmEngine(private val context: Context) {
    fun openSession(
        model: ResolvedLocalModel,
        settings: LocalAiSettings,
    ): PreparedSession {
        val normalized = settings.normalized()
        var lastError: Throwable? = null

        preferredBackends().forEach { backend ->
            var engine: Engine? = null
            try {
                engine = Engine(
                    EngineConfig(
                        modelPath = model.path,
                        backend = backend,
                        // Gemma 4 E2B supports up to 32k context; 2048 matches the benchmark
                        // configuration and fits comfortably on mid-range devices.
                        maxNumTokens = COMPILED_MODEL_CONTEXT_WINDOW,
                        cacheDir = cacheDirectory.absolutePath,
                    ),
                )
                engine.initialize()
                return PreparedSession(
                    engine = engine,
                    backend = backend,
                    topK = normalized.topK.coerceAtMost(MAX_TOP_K),
                    temperature = normalized.temperature.coerceIn(0f, 1f),
                )
            } catch (throwable: Throwable) {
                runCatching { engine?.close() }
                lastError = throwable
            }
        }

        throw IllegalStateException(
            "LiteRT-LM engine initialization failed for ${model.label}.",
            lastError,
        )
    }

    private fun preferredBackends(): List<Backend> {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        return listOf(
            Backend.CPU(),
            Backend.NPU(nativeLibraryDir = nativeLibraryDir),
            Backend.GPU(),
        )
    }

    inner class PreparedSession(
        private val engine: Engine,
        private val backend: Backend,
        private val topK: Int,
        private val temperature: Float,
    ) : AutoCloseable {
        val backendLabel: String
            get() = backend::class.simpleName ?: backend.toString()

        fun generate(prompt: String): String {
            return openConversation().use { conversation ->
                conversation.sendMessage(
                    Contents.of(listOf(Content.Text(prompt))),
                ).toString().trim()
            }
        }

        private fun openConversation(): Conversation {
            return engine.createConversation(
                ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = DEFAULT_TOP_P,
                        temperature = temperature.toDouble(),
                    ),
                ),
            )
        }

        override fun close() {
            engine.close()
        }
    }

    private val cacheDirectory: File
        get() = File(context.cacheDir, "litertlm").apply { mkdirs() }

    companion object {
        private const val DEFAULT_TOP_P = 0.95
        private const val COMPILED_MODEL_CONTEXT_WINDOW = 2048
        private const val MAX_TOP_K = 32
    }
}
