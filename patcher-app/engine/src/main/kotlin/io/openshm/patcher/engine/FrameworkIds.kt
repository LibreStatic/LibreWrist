package io.openshm.patcher.engine

/**
 * Ids of the public framework attrs and styles (`android:` resources), from a table generated out of
 * the Android SDK jar (`engine/tools/GenFrameworkIds.java`). The patcher needs it to name the
 * attributes it creates and to read/write `@android:style/...` references.
 */
object FrameworkIds {
    private val byName = HashMap<String, Int>()
    private val byId = HashMap<Int, String>()

    init {
        val stream = FrameworkIds::class.java.getResourceAsStream("framework-ids.txt")
            ?: throw IllegalStateException("framework-ids.txt is missing from the engine resources")
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val eq = line.indexOf('=')
                val key = line.substring(0, eq)
                val id = line.substring(eq + 1).toLong(16).toInt()
                byName[key] = id
                byId.putIfAbsent(id, key)
            }
        }
    }

    /** Id of `android:attr/<name>`, or null. */
    fun attr(name: String): Int? = byName["attr/$name"]

    /** Id of `@android:<type>/<name>` (type: `style`), or null. */
    fun resource(type: String, name: String): Int? = byName["$type/$name"]

    /** `type/name` of a framework resource id, or null. */
    fun nameOf(id: Int): String? = byId[id]
}
