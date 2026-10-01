package io.vikunja.app.widget

/**
 * Font size (density) preference of a single widget instance, persisted by
 * [WidgetConfigureActivity] under `widget_font_size_<id>`.
 *
 * [AUTO] keeps the size-driven density tiers of [WidgetLayouts]: short
 * instances render dense, tall ones comfortable. [COMPACT] and [LARGE]
 * override them at any size — smaller text for more visible rows, or bigger
 * text at the cost of rows. The row-capacity model follows the chosen
 * density, so no size clips: instances too short for one row of the chosen
 * density render the title bar alone ([WidgetLayout.isHeaderOnly]).
 */
enum class WidgetFontSize(val prefName: String) {
    /** Density derived from the instance's size. */
    AUTO("auto"),

    /** Dense rows regardless of size: smaller text, more rows fit. */
    COMPACT("compact"),

    /** Comfortable rows regardless of size: bigger text, fewer rows fit. */
    LARGE("large");

    companion object {
        /** Parses a stored preference value; anything unknown falls back to [AUTO]. */
        fun fromPref(value: String?): WidgetFontSize =
            entries.firstOrNull { it.prefName == value } ?: AUTO
    }
}
