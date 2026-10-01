package io.vikunja.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFontSizeTest {

    @Test
    fun `null preference resolves to auto`() {
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref(null))
    }

    @Test
    fun `known preference names resolve`() {
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref("auto"))
        assertEquals(WidgetFontSize.COMPACT, WidgetFontSize.fromPref("compact"))
        assertEquals(WidgetFontSize.LARGE, WidgetFontSize.fromPref("large"))
    }

    @Test
    fun `unknown or corrupt preference falls back to auto`() {
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref(""))
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref("Compact"))
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref("small"))
        assertEquals(WidgetFontSize.AUTO, WidgetFontSize.fromPref("14"))
    }

    @Test
    fun `every font size round-trips through its preference name`() {
        for (fontSize in WidgetFontSize.entries) {
            assertEquals(fontSize, WidgetFontSize.fromPref(fontSize.prefName))
        }
    }
}
