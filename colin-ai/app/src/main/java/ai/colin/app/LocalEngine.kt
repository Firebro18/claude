package ai.colin.app

import android.content.Context
import java.io.File

/** Kotlin side of the native llama.cpp engine (see src/main/cpp/colin_jni.cpp). Not thread-safe: use from one thread. */
class LocalEngine private constructor(private var handle: Long) {

    fun interface TextCallback {
        /** Receives complete UTF-8 chunks; return false to stop generating. */
        fun onText(bytes: ByteArray): Boolean
    }

    /** Streams the reply to [onText]. Throws on engine errors. */
    fun generate(system: String, history: List<ChatMessage>, maxTokens: Int, temperature: Float, onText: (String) -> Boolean) {
        val roles = mutableListOf("system")
        val contents = mutableListOf(system)
        history.forEach {
            roles += if (it.role == Role.USER) "user" else "assistant"
            contents += it.text
        }
        val n = nativeGenerate(handle, roles.toTypedArray(), contents.toTypedArray(), maxTokens, temperature) { bytes ->
            onText(String(bytes, Charsets.UTF_8))
        }
        if (n < 0) throw IllegalStateException(nativeLastError(handle))
    }

    /** Safe to call from any thread. */
    fun stop() = nativeStop(handle)

    fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0L
    }

    companion object {
        init { System.loadLibrary("colin") }

        /** Copies the model out of the APK on first use (llama.cpp needs a real file), then loads it. */
        fun load(context: Context, brain: Brain): LocalEngine {
            val dir = File(context.filesDir, "models").apply { mkdirs() }
            val file = File(dir, brain.file)
            val stamp = File(dir, brain.file + ".v" + BuildConfig.VERSION_CODE)
            if (!file.exists() || !stamp.exists()) {
                val tmp = File(dir, brain.file + ".tmp")
                context.assets.open("models/${brain.file}").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                tmp.renameTo(file)
                dir.listFiles { f -> f.name.startsWith(brain.file + ".v") }?.forEach { it.delete() }
                stamp.createNewFile()
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val h = nativeCreate()
            val err = nativeLoad(h, file.absolutePath, brain.contextTokens, threads)
            if (err.isNotEmpty()) {
                nativeFree(h)
                throw IllegalStateException(err)
            }
            return LocalEngine(h)
        }

        @JvmStatic external fun nativeCreate(): Long
        @JvmStatic external fun nativeLoad(handle: Long, path: String, nCtx: Int, threads: Int): String
        @JvmStatic external fun nativeGenerate(
            handle: Long, roles: Array<String>, contents: Array<String>, maxTokens: Int, temperature: Float, cb: TextCallback,
        ): Int
        @JvmStatic external fun nativeLastError(handle: Long): String
        @JvmStatic external fun nativeStop(handle: Long)
        @JvmStatic external fun nativeFree(handle: Long)
    }
}
