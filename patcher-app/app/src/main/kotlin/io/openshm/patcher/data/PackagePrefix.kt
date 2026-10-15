package io.openshm.patcher.data

import java.util.Random

/**
 * The user-chosen `PKG_PREFIX` that names the bridge apps, the alarm fork and the injected code
 * (handoff 4.3). The two Samsung Health Monitor apps keep their own package and are not affected.
 */
object PackagePrefix {
    const val DEFAULT_BASE = "io.openshm"
    private const val HEX_LENGTH = 6

    /** Longest allowed prefix: leaves room for the longest package suffix of the catalogue; Android caps names at 255. */
    private const val MAX_LENGTH = 200

    private val segment = Regex("[a-z][a-z0-9_]*")

    /** Namespaces that belong to someone else; helper apps must never claim them. */
    private val reserved = listOf("android", "com.android", "com.google", "com.samsung", "com.sec", "samsung")

    enum class Problem { Empty, TooShort, TooLong, InvalidSegment, Reserved }

    /** `io.openshm.u<6 hex>`, e.g. `io.openshm.u3fa91c`. */
    fun generate(random: Random = Random()): String {
        val hex = buildString { repeat(HEX_LENGTH) { append("0123456789abcdef"[random.nextInt(16)]) } }
        return "$DEFAULT_BASE.u$hex"
    }

    /** Null when [prefix] is usable. */
    fun validate(prefix: String): Problem? {
        if (prefix.isEmpty()) return Problem.Empty
        if (prefix.length > MAX_LENGTH) return Problem.TooLong
        val parts = prefix.split('.')
        if (parts.size < 2) return Problem.TooShort
        if (parts.any { !segment.matches(it) }) return Problem.InvalidSegment
        if (reserved.any { prefix == it || prefix.startsWith("$it.") }) return Problem.Reserved
        return null
    }
}
