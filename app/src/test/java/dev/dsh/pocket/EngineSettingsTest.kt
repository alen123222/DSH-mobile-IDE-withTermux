package dev.dsh.pocket

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EngineSettingsTest {
    @Test fun oldPresetsKeepProviderDefaults() {
        val settings = EngineSettings.from(JSONObject().put("model", "existing"))
        assertEquals("", settings.reasoningEffort)
        assertEquals("existing", settings.model)
    }

    @Test fun savedPresetsRetainSeparateReasoningSelections() {
        val high = EngineSettings(model = "one", protocol = "openai-chat", reasoningEffort = "high")
        val low = EngineSettings(model = "two", reasoningEffort = "low")
        assertEquals(high, EngineSettings.from(high.json()).copy(provider = high.provider))
        assertEquals("low", EngineSettings.from(low.json()).reasoningEffort)
        assertFalse(reasoningLevels("deepseek-messages").contains("medium"))
    }
}
