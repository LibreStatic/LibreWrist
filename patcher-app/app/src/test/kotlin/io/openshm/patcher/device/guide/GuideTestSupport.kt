package io.openshm.patcher.device.guide

/** Builds window snapshots for the guide tests: rows are a clickable container with a text child. */
class Screen(
    private val pkg: String = "com.android.settings",
    private val cls: String? = "SubSettings",
    private val width: Int = 1080,
    private val height: Int = 2400,
) {
    class Row(val containerId: Int, val textId: Int)

    private val nodes = mutableListOf<GuideNode>()
    private var nextId = 1
    private var nextY = 300

    fun node(
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        visible: Boolean = true,
        parent: Int? = null,
        bounds: GuideRect = GuideRect(0, nextY, width, nextY + 150).also { nextY += 160 },
    ): Int {
        val id = nextId++
        nodes += GuideNode(
            id, text, description, viewId, className, bounds, clickable, checkable, checked, scrollable, visible, parent,
        )
        return id
    }

    /** A row: clickable container plus its title; [checked] adds a switch inside the container. */
    fun row(title: String, checked: Boolean? = null, visible: Boolean = true, clickable: Boolean = true): Row {
        val box = GuideRect(0, nextY, width, nextY + 150)
        nextY += 160
        val container = node(clickable = clickable, visible = visible, bounds = box)
        val text = node(
            text = title, visible = visible, parent = container,
            bounds = GuideRect(40, box.top + 20, 600, box.top + 80),
        )
        if (checked != null) {
            node(
                className = "android.widget.Switch", checkable = true, checked = checked, visible = visible,
                parent = container, bounds = GuideRect(900, box.top + 20, 1040, box.top + 100),
            )
        }
        return Row(container, text)
    }

    fun list(): Int = node(scrollable = true, bounds = GuideRect(0, 200, width, height))

    fun snapshot() = WindowSnapshot(pkg, cls, nodes.toList(), width, height)
}

fun screen(pkg: String = "com.android.settings", cls: String? = "SubSettings", build: Screen.() -> Unit): WindowSnapshot =
    Screen(pkg, cls).apply(build).snapshot()

/** Settings' own strings, as the service would resolve them. */
val settingsStrings = mapOf(
    "build_number" to "Build number",
    "header_category_system" to "System",
    "development_settings_title" to "Developer options",
    "development_settings_enable" to "Use developer options",
    "enable_adb_wireless" to "Wireless debugging",
    "wireless_debugging_main_switch_title" to "Use wireless debugging",
    "adb_pair_method_code_title" to "Pair device with pairing code",
    "show_dev_on" to "You are now a developer!",
    "show_dev_already" to "No need, you are already a developer.",
    "permit_draw_overlay" to "Allow display over other apps",
    "storage_settings_for_app" to "Storage & cache",
    "manage_space_text" to "Manage space",
)

val settingsResolver = LabelResolver { settingsStrings[it] }
val noResolver = LabelResolver { null }
val envOk = GuideEnv(developerOptionsEnabled = true, debuggingDisallowed = false)
