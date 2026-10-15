package io.openshm.patcher.build

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * One finished output as recorded. [fingerprint] is what it was built from (see [outputFingerprint]; empty
 * for a record written before it existed, which never matches), [reused] that the last build kept the
 * file of an earlier one instead of patching again.
 */
data class BuiltRecord(
    val outputId: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val fingerprint: String = "",
    val reused: Boolean = false,
)

/** The last successful build: what it was built for ([key]) and its files. */
data class BuiltBuild(
    val key: String,
    val prefix: String,
    val catalogueSerial: Long,
    val builtAtMillis: Long,
    val outputs: List<BuiltRecord>,
    /** What the build depended on (see [buildFacts]); empty for a record written before this existed. */
    val facts: Map<String, String> = emptyMap(),
)

/**
 * The record of the last successful build, a small properties file next to the outputs. Written
 * atomically; a missing, damaged or stale record (a listed file gone or changed size) reads as "no
 * build". Building does not lock the prefix; installing does. Plain JVM code.
 */
class BuiltOutputs(private val file: File) {
    @Synchronized
    fun read(): BuiltBuild? {
        val p = Properties()
        try {
            file.inputStream().use { p.load(it) }
            val ids = p.getProperty("ids")?.split(',')?.filter { it.isNotEmpty() } ?: return null
            val records = ids.map { id ->
                BuiltRecord(
                    id, p.getProperty("$id.path"), p.getProperty("$id.size").toLong(),
                    p.getProperty("$id.sha256"),
                    fingerprint = p.getProperty("$id.fingerprint").orEmpty(), reused = p.getProperty("$id.reused") == "true",
                )
            }
            if (records.isEmpty() || records.any { File(it.path).length() != it.sizeBytes }) return null
            return BuiltBuild(
                key = p.getProperty("key"), prefix = p.getProperty("prefix"),
                catalogueSerial = p.getProperty("serial").toLong(), builtAtMillis = p.getProperty("builtAt").toLong(),
                outputs = records,
                facts = p.stringPropertyNames().filter { it.startsWith(FACT_PREFIX) }
                    .associate { it.removePrefix(FACT_PREFIX) to p.getProperty(it) },
            )
        } catch (e: Exception) {
            return null
        }
    }

    @Synchronized
    fun write(build: BuiltBuild) {
        val p = Properties()
        p.setProperty("key", build.key)
        p.setProperty("prefix", build.prefix)
        p.setProperty("serial", build.catalogueSerial.toString())
        p.setProperty("builtAt", build.builtAtMillis.toString())
        for ((k, v) in build.facts) p.setProperty(FACT_PREFIX + k, v)
        p.setProperty("ids", build.outputs.joinToString(",") { it.outputId })
        for (o in build.outputs) {
            p.setProperty("${o.outputId}.path", o.path)
            p.setProperty("${o.outputId}.size", o.sizeBytes.toString())
            p.setProperty("${o.outputId}.sha256", o.sha256)
            p.setProperty("${o.outputId}.fingerprint", o.fingerprint)
            p.setProperty("${o.outputId}.reused", o.reused.toString())
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        try {
            FileOutputStream(tmp).use { p.store(it, null); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val FACT_PREFIX = "fact."
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
