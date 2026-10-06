package dev.dsh.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LangTest {
    private fun withLocale(locale: Locale, body: () -> Unit) {
        val previous = Locale.getDefault()
        try { Locale.setDefault(locale); body() } finally { Locale.setDefault(previous) }
    }

    @Test fun `english locale translates the interface`() = withLocale(Locale.US) {
        assertFalse(Lang.chinese)
        assertEquals("Save", tr("保存"))
        assertEquals("Home", tr("主目录"))
        assertEquals("Internal storage", tr("内部存储"))
        assertEquals("Workspaces", tr("工作区"))
        assertEquals("Zoom in", tr("放大"))
        assertEquals("Run in terminal", tr("在终端运行"))
    }

    @Test fun `chinese locale keeps the source strings`() = withLocale(Locale.SIMPLIFIED_CHINESE) {
        assertTrue(Lang.chinese)
        assertEquals("保存", tr("保存"))
        assertEquals("主目录", tr("主目录"))
    }

    @Test fun `an untranslated string falls back to its source, never a placeholder`() = withLocale(Locale.US) {
        assertEquals("这个字符串没有翻译", tr("这个字符串没有翻译"))
    }
}
