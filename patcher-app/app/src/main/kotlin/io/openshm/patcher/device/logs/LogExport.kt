package io.openshm.patcher.device.logs

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The combined export: a header, then the watch log, then the phone log. */
object LogExport {
    const val NOT_CONNECTED = "(not connected — no lines captured)"

    class Section(val title: String, val label: String?, val file: File?)

    fun fileName(nowMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = zone }.format(Date(nowMillis))
            .let { "openshm-logs-$it.txt" }

    fun header(versionName: String, nowMillis: Long, catalogueSerial: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { timeZone = zone }.format(Date(nowMillis))
        return "OpenSHM patcher $versionName — device logs\nExported: $date\nCatalogue serial: $catalogueSerial\n"
    }

    /** Writes [header] and every section to [out]; file contents are copied as a stream, never loaded whole. */
    fun write(out: OutputStream, header: String, sections: List<Section>) {
        out.write(header.toByteArray(Charsets.UTF_8))
        for (section in sections) {
            out.write("\n===== ${section.title} (${section.label ?: "unknown"}) =====\n".toByteArray(Charsets.UTF_8))
            val file = section.file?.takeIf { it.isFile && it.length() > 0 }
            if (file == null) {
                out.write("$NOT_CONNECTED\n".toByteArray(Charsets.UTF_8))
            } else {
                file.inputStream().use { it.copyTo(out) }
            }
        }
        out.flush()
    }
}
