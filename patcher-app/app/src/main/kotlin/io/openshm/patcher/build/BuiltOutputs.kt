package io.openshm.patcher.build

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** One finished output as recorded. */
data class BuiltRecord(val outputId: String, val path: String, val sizeBytes: Long, val sha256: String)

/** The last successful build: what it was built for ([key]) and its files. */
data class BuiltBuild(
    val key: String,
    val prefix: String,
    val catalogueSerial: Long,
    val builtAtMillis: Long,
    val outputs: List<BuiltRecord>,
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
                BuiltRecord(id, p.getProperty("$id.path"), p.getProperty("$id.size").toLong(), p.getProperty("$id.sha256"))
            }
            if (records.isEmpty() || records.any { File(it.path).length() != it.sizeBytes }) return null
            return BuiltBuild(
                key = p.getProperty("key"), prefix = p.getProperty("prefix"),
                catalogueSerial = p.getProperty("serial").toLong(), builtAtMillis = p.getProperty("builtAt").toLong(),
                outputs = records,
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
        p.setProperty("ids", build.outputs.joinToString(",") { it.outputId })
        for (o in build.outputs) {
            p.setProperty("${o.outputId}.path", o.path)
            p.setProperty("${o.outputId}.size", o.sizeBytes.toString())
            p.setProperty("${o.outputId}.sha256", o.sha256)
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

    @Synchronized
    fun clear() {
        file.delete()
    }
}
