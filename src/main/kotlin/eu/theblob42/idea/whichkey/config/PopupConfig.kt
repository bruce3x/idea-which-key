package eu.theblob42.idea.whichkey.config

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.options.OptionScope
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimInt
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString
import eu.theblob42.idea.whichkey.model.Mapping
import kotlinx.coroutines.*
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.KeyStroke

object PopupConfig {

    private const val DEFAULT_POPUP_DELAY = 200
    private val defaultPopupDelay =
        when (val delay = VimPlugin.getVariableService().getGlobalVariableValue("WhichKey_DefaultDelay")) {
            null -> DEFAULT_POPUP_DELAY
            !is VimInt -> DEFAULT_POPUP_DELAY
            else -> delay.value
        }

    private val DEFAULT_SORT_OPTION = SortOption.BY_KEY
    private val sortOption =
        when (val option = VimPlugin.getVariableService().getGlobalVariableValue("WhichKey_SortOrder")) {
            null -> DEFAULT_SORT_OPTION
            !is VimString -> DEFAULT_SORT_OPTION
            else -> SortOption.values().firstOrNull { it.name.equals(option.asString(), ignoreCase = true) }
                ?: DEFAULT_SORT_OPTION
        }

    private val maxRows = 6

    private var currentBalloon: Balloon? = null
    private var displayBalloonJob: Job? = null

    /**
     * Either cancel the display job or hide the current popup
     */
    fun hidePopup() {
        // cancel job or wait till it's done (if it already started)
        runBlocking {
            displayBalloonJob?.cancelAndJoin()
        }
        // hide Balloon if present and reset value
        currentBalloon?.let {
            it.hide()
            currentBalloon = null
        }
    }

    /**
     * Show the popup presenting the nested mappings for [typedKeys]
     * Do not show the popup instantly but instead start a coroutine job to show the popup after a delay
     *
     * If there are no 'nestedMappings' (empty list) this function does nothing
     *
     * @param ideFrame The [JFrame] to attach the popup to
     * @param typedKeys The already typed key stroke sequence
     * @param nestedMappings A [List] of nested mappings to display
     * @param startTime Timestamp to consider for the calculation of the popup delay
     */
    fun showPopup(
        ideFrame: JFrame,
        typedKeys: List<KeyStroke>,
        nestedMappings: List<Pair<String, Mapping>>,
        startTime: Long
    ) {
        if (nestedMappings.isEmpty()) {
            return
        }

        val columns: List<List<Pair<String, Mapping>>> = sortMappings(nestedMappings)
            .chunked(maxRows)
            .toList()

        val table = buildString {
            append("<table>")

            for (row in 0 until maxRows) {
                append("<tr>")

                for (column in columns) {
                    val maxMapping = column.maxByOrNull { (key, mapping) -> key.length + mapping.description.length }!!
                    val maxKeyLength = column.maxOf { (key, _) -> key.length }
                    // (we have manually checked that 'nestedMappings' is not empty)
                    // calculate the pixel width of the longest mapping string (with HTML formatting & styling)
                    val maxStringWidth =
                        JLabel("<html>${FormatConfig.formatMappingEntry(maxMapping)}</html>").preferredSize.width

                    val columnWidth = maxStringWidth + 20

                    val entry = column.getOrNull(row)
                    val display = if (entry != null) {
                        FormatConfig.formatMappingEntry(
                            entry.first.padStart(maxKeyLength) to entry.second
                        )
                    } else {
                        ""
                    }
                    append("<td width=\"${columnWidth}px\">$display</td>")
                }

                append("</tr>")
            }

            append("</table>")
        }

        val mappingsStringBuilder = StringBuilder()
        mappingsStringBuilder.append(table)

        // append the already typed key sequence below the nested mappings table if configured (default: true)
        val showTypedSequence =
            when (val show = VimPlugin.getVariableService().getGlobalVariableValue("WhichKey_ShowTypedSequence")) {
                null -> true
                !is VimString -> true
                else -> show.asBoolean()
            }
        if (showTypedSequence) {
            mappingsStringBuilder.append("<hr style=\"margin-bottom: 2px;\">") // some small margin to not look cramped
            mappingsStringBuilder.append(FormatConfig.formatTypedSequence(typedKeys))
        }

        val target = RelativePoint.getSouthWestOf(ideFrame.rootPane)
        val fadeoutTime = if (VimPlugin.getOptionService().getOptionValue(OptionScope.GLOBAL, "timeout").asBoolean()) {
            VimPlugin.getOptionService().getOptionValue(OptionScope.GLOBAL, "timeoutlen").asDouble().toLong()
        } else {
            0L
        }

        // the extra variable 'newWhichKeyBalloon' is needed so that the currently displayed Balloon
        // can be hidden in case the 'displayBalloonJob' gets canceled before execution
        val newWhichKeyBalloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(
                mappingsStringBuilder.toString(),
                null,
                EditorColorsManager.getInstance().schemeForCurrentUITheme.defaultBackground,
                null
            )
            .setAnimationCycle(10) // shorten animation time
            .setFadeoutTime(fadeoutTime)
            .createBalloon()

        /*
         * wait for a few ms before showing the Balloon to prevent flickering on fast consecutive key presses
         * subtract the already passed time (for calculations etc.) to make the delay as consistent as possible
         */
        val delay = (defaultPopupDelay - (System.currentTimeMillis() - startTime)).let {
            if (it < 0) 0 else it
        }

        displayBalloonJob = GlobalScope.launch {
            delay(delay)
            newWhichKeyBalloon.show(target, Balloon.Position.above)
            currentBalloon = newWhichKeyBalloon
        }
    }

    /**
     * Sort mappings dependent on the configured sort options
     * @param nestedMappings The list of mappings to sort
     * @return The sorted list of mappings
     */
    private fun sortMappings(nestedMappings: List<Pair<String, Mapping>>): List<Pair<String, Mapping>> {
        return when (sortOption) {
            SortOption.BY_KEY -> nestedMappings.sortedBy { it.first }
            SortOption.BY_KEY_PREFIX_FIRST -> nestedMappings.sortedWith(compareBy({ !it.second.prefix }, { it.first }))
            SortOption.BY_DESCRIPTION -> nestedMappings.sortedBy { it.second.description }
            SortOption.BY_KEY_IGNORE_CASE -> nestedMappings.sortedWith(SortByKeyIgnoreCase)
        }
    }
}

enum class SortOption {
    BY_KEY,
    BY_KEY_PREFIX_FIRST,
    BY_DESCRIPTION,
    BY_KEY_IGNORE_CASE,
}

/**
 * 1. symbols first
 * 2. sort by lower letter order
 * 3. sort by letter case
 */
object SortByKeyIgnoreCase : Comparator<Pair<String, Mapping>> {
    override fun compare(o1: Pair<String, Mapping>, o2: Pair<String, Mapping>): Int {
        val key1 = o1.first.first()
        val key2 = o2.first.first()

        val isLetter1 = key1.lowercaseChar() in ('a'..'z')
        val isLetter2 = key2.lowercaseChar() in ('a'..'z')

        return if (isLetter1 && isLetter2) {
            val r = key1.lowercaseChar().compareTo(key2.lowercaseChar())
            if (r == 0) {
                if (key1.isLowerCase()) -1 else 1
            } else {
                r
            }
        } else if (isLetter1) {
            1
        } else if (isLetter2) {
            -1
        } else {
            key1.compareTo(key2)
        }

    }

}