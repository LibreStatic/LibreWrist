package io.openshm.patcher.device.guide

import java.text.Normalizer
import java.util.Locale

enum class Role {
    Any,

    /** A row with a switch or radio: [Match.checked] reads the checkable node inside the row. */
    Switch,

    /** A dialog button: the node itself must be clickable (or a Button). */
    Button,
}

/**
 * A label to find on screen. Resolution order: the Settings app's own strings ([settingsRes], so
 * the text is exact in the user's language and OEM skin), then the bundled [fallbacks], then the
 * stable [viewIds]. [exactLabels] are strings known at run time to be exact, such as an app's own label.
 */
data class Target(
    val settingsRes: List<String>,
    val fallbacks: List<String>,
    val viewIds: List<String> = emptyList(),
    val role: Role = Role.Any,
    val exactLabels: List<String> = emptyList(),
)

/**
 * A node on screen that answers a [Target].
 *
 * [node] is the node that carries the text. [clickNode] is what to click: the node itself when it
 * is clickable, else its nearest clickable ancestor. [bounds] is what to highlight: the click
 * node's, unless that is huge (nearly full width and more than three rows tall), then the text's.
 * [exact] is true for a stable view id or a string equal to a Settings resource; fallback labels
 * and substrings are never exact. [checked] is the state of the switch in the row (Role.Switch).
 */
data class Match(
    val node: GuideNode,
    val exact: Boolean,
    val clickNode: GuideNode,
    val bounds: GuideRect,
    val checked: Boolean?,
)

/** [ambiguous]: two or more different rows answer the target equally well; [match] is the top-most. */
data class MatchResult(val match: Match?, val ambiguous: Boolean)

private class Candidate(val node: GuideNode, val rank: Int, val click: GuideNode)

class TextMatcher(private val resolver: LabelResolver) {
    private val resolved = HashMap<String, String?>()

    /** The label to show the user for [target]: Settings' own string, else the first fallback. */
    fun label(target: Target): String =
        settingsLabel(target) ?: target.exactLabels.firstOrNull() ?: target.fallbacks.firstOrNull().orEmpty()

    /** Settings' own string for [target], or null when none of its resources resolves. */
    fun settingsLabel(target: Target): String? = target.settingsRes.firstNotNullOfOrNull { settings(it) }

    fun settings(name: String): String? =
        resolved.getOrPut(name) { resolver.settingsString(name)?.takeIf { it.isNotBlank() } }

    fun find(snapshot: WindowSnapshot, target: Target): MatchResult {
        val exactLabels = (target.settingsRes.mapNotNull { settings(it) } + target.exactLabels)
            .map(::normalize).filter { it.isNotEmpty() }.toSet()
        val fallbackLabels = target.fallbacks.map(::normalize).filter { it.isNotEmpty() }
        val byId = snapshot.nodes.associateBy { it.id }

        val candidates = ArrayList<Candidate>()
        for (node in snapshot.nodes) {
            if (!node.visibleToUser || node.bounds.width <= 0 || node.bounds.height <= 0) continue
            val rank = rank(node, target, exactLabels, fallbackLabels) ?: continue
            val click = when (target.role) {
                Role.Button -> if (node.clickable || node.className.orEmpty().endsWith("Button")) node else continue
                else -> clickTarget(node, byId)
            }
            candidates += Candidate(node, rank, click)
        }
        val best = candidates.minOfOrNull { it.rank } ?: return MatchResult(null, false)
        val distinct = candidates.filter { it.rank == best }.distinctBy { it.click.id }
        // One row can answer twice: a two-target preference opens its page from the text and toggles from
        // its switch, which carries the same label. Keep one per row: the switch for Role.Switch, else the row.
        val switch = target.role == Role.Switch
        val rows = distinct.filter { c ->
            distinct.none { o ->
                o !== c && (
                    (isInside(c.click, o.click, byId) && !(switch && c.click.checkable)) ||
                        (isInside(o.click, c.click, byId) && switch && o.click.checkable)
                    )
            }
        }.sortedWith(compareBy({ it.node.bounds.top }, { it.node.bounds.left }))
        val top = rows.first()
        val bounds = highlight(top.node, top.click, snapshot)
        val checked = if (target.role == Role.Switch) switchState(top.click, top.node, byId, snapshot) else null
        return MatchResult(Match(top.node, best == 0, top.click, bounds, checked), rows.size > 1)
    }

    private fun switchState(click: GuideNode, text: GuideNode, byId: Map<Int, GuideNode>, snapshot: WindowSnapshot): Boolean? {
        if (click.checkable) return click.checked
        if (text.checkable) return text.checked
        val inside = snapshot.nodes.firstOrNull { it.checkable && isInside(it, click, byId) }
        return inside?.checked
    }

    private fun isInside(node: GuideNode, ancestor: GuideNode, byId: Map<Int, GuideNode>): Boolean {
        var current = node.parentId?.let { byId[it] }
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current.id == ancestor.id) return true
            current = current.parentId?.let { byId[it] }
        }
        return false
    }

    private fun rank(node: GuideNode, target: Target, exactLabels: Set<String>, fallbacks: List<String>): Int? {
        val id = node.viewId
        if (id != null && target.viewIds.any { id == it || id.endsWith("/$it") }) return 0
        val texts = listOfNotNull(node.text, node.contentDescription).map(::normalize).filter { it.isNotEmpty() }
        if (texts.any { it in exactLabels }) return 0
        if (texts.any { it in fallbacks }) return 1
        val labels = exactLabels + fallbacks
        if (texts.any { t -> labels.any { it.length >= 4 && t.contains(it) } }) return 2
        return null
    }

    private fun clickTarget(node: GuideNode, byId: Map<Int, GuideNode>): GuideNode {
        var current: GuideNode? = node
        var guard = 0
        while (current != null && guard++ < 64) {
            if (current.clickable) return current
            current = current.parentId?.let { byId[it] }
        }
        return node
    }

    private fun highlight(text: GuideNode, click: GuideNode, snapshot: WindowSnapshot): GuideRect {
        if (click.id == text.id) return text.bounds
        val row = snapshot.screenHeight / 12
        val huge = click.bounds.width > snapshot.screenWidth * 0.95 && click.bounds.height > 3 * row
        return if (huge) text.bounds else click.bounds
    }

    companion object {
        private val marks = Regex("\\p{Mn}+")
        private val spaces = Regex("[\\s\\u00A0\\u202F\\u2009]+")
        private val edges = Regex("^[\\p{P}\\s]+|[\\p{P}\\s]+$")

        /** Case-, accent- and spacing-insensitive form used on both sides of every comparison. */
        fun normalize(text: String): String {
            val flat = Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD)
                .replace(marks, "")
                .lowercase(Locale.ROOT)
                .replace(spaces, " ")
                .trim()
            return flat.replace(edges, "")
        }
    }
}
