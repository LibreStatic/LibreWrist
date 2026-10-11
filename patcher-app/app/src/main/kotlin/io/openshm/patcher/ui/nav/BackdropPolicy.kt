package io.openshm.patcher.ui.nav

import androidx.compose.runtime.saveable.Saver

/**
 * When the floating backdrop is shown. It stays through the light setup steps, leaves for good once
 * the heavy part starts (Build and everything after it, Home included) and only comes back when a
 * new run of the wizard starts: a Welcome or Profile back-stack entry that was never seen before.
 * Going back to an earlier step re-shows an entry already seen, so it does not bring the shapes back.
 */
data class BackdropPolicy(
    val dispersed: Boolean = false,
    /** Ids of the back-stack entries seen so far; only their identity matters. */
    val seen: Set<String> = emptySet(),
) {
    fun onDestination(route: String?, entryId: String): BackdropPolicy {
        val fresh = entryId !in seen
        val dispersed = when {
            route in heavyRoutes -> true
            fresh && route in startRoutes -> false
            else -> dispersed
        }
        // Bounded: a long session never needs more than the recent history.
        return BackdropPolicy(dispersed, (seen + entryId).toList().takeLast(MAX_SEEN).toSet())
    }

    companion object {
        private const val MAX_SEEN = 64

        private val startRoutes = setOf(Route.Welcome.path, Route.Profile.path)

        /** Build and every route after it in the setup flow. */
        private val heavyRoutes: Set<String> =
            SetupFlow.order.dropWhile { it != Route.Build }.map { it.path }.toSet()

        val Saver: Saver<BackdropPolicy, Any> = Saver(
            save = { arrayListOf<String>().apply { add(it.dispersed.toString()); addAll(it.seen) } },
            restore = { saved ->
                @Suppress("UNCHECKED_CAST")
                val list = saved as List<String>
                BackdropPolicy(list.first().toBoolean(), list.drop(1).toSet())
            },
        )
    }
}
