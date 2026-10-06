package dev.dsh.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TimelineTest {
    private fun message(role: String, text: String) =
        JSONObject().put("role", role).put("text", text).put("time", 1)

    private fun event(type: String, data: JSONObject) =
        JSONObject().put("type", type).put("data", data).put("time", 1)

    // numbered mirrors what the bridge writes; without it, old records have no seq.
    private fun chat(numbered: Boolean, vararg entries: JSONObject): JSONObject {
        val messages = JSONArray()
        val events = JSONArray()
        var seq = 0
        for (entry in entries) {
            val copy = JSONObject(entry.toString())
            if (numbered) copy.put("seq", ++seq)
            if (copy.has("role")) messages.put(copy) else events.put(copy)
        }
        return JSONObject().put("messages", messages).put("events", events)
    }

    private fun call(turn: Int, step: Int, id: String, name: String, arguments: String) =
        event("tool/call", JSONObject().put("turn", turn).put("step", step)
            .put("callId", id).put("name", name).put("arguments", arguments))

    private fun result(turn: Int, step: Int, id: String, text: String) =
        event("tool/result", JSONObject().put("turn", turn).put("step", step).put("callId", id).put("text", text))

    @Test fun groupsReadsAndRunsIntoOneCard() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val timeline = buildTimeline(chat(true,
                message("user", "do it"),
                call(1, 1, "a", "bash", "{\"command\":\"ls -la\"}"),
                result(1, 1, "a", "file.txt"),
                call(1, 1, "b", "read", "{\"path\":\"a.py\"}"),
                event("assistant/thinking", JSONObject().put("text", "let me look")),
                message("assistant", "done"),
            ))
            assertEquals(4, timeline.size)
            assertTrue(timeline[0] is Timeline.Bubble)
            val tools = timeline[1] as Timeline.Tools
            assertEquals(2, tools.calls.size)
            assertEquals("read files and ran code", tools.label)
            assertEquals("file.txt", tools.calls[0].output)
            assertTrue(timeline[2] is Timeline.Thinking)
            assertEquals("done", (timeline[3] as Timeline.Bubble).text)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun aSecondStepBecomesItsOwnCard() {
        val timeline = buildTimeline(chat(true,
            call(1, 1, "a", "bash", "{\"command\":\"one\"}"),
            call(2, 1, "b", "bash", "{\"command\":\"two\"}"),
        ))
        assertEquals(2, timeline.size)
        assertTrue(timeline[0] is Timeline.Tools)
        assertTrue(timeline[1] is Timeline.Tools)
    }

    @Test fun oldRecordsGetUniqueRowIdentities() {
        val timeline = buildTimeline(chat(false,
            message("user", "hello"),
            call(1, 1, "a", "bash", "{\"command\":\"ls\"}"),
            message("assistant", "hi"),
        ))
        assertEquals(3, timeline.size)
        assertEquals(3, timeline.map { it.seq }.distinct().size)
    }
}
