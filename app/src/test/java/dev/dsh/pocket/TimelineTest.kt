package dev.dsh.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun call(turn: Int, step: Int, id: String, name: String, command: String) =
        event("tool/call", JSONObject().put("turn", turn).put("step", step).put("callId", id)
            .put("name", name).put("arguments", JSONObject().put("command", command).toString()))

    private fun result(turn: Int, step: Int, id: String, text: String) =
        event("tool/result", JSONObject().put("turn", turn).put("step", step).put("callId", id).put("text", text))

    @Test fun aWholeTurnCollapsesIntoOneWorkRow() {
        val timeline = buildTimeline(chat(true,
            message("user", "do it"),
            call(1, 1, "a", "bash", "ls -la"),
            result(1, 1, "a", "file.txt"),
            call(1, 2, "b", "bash", "python3 main.py"),
            call(1, 3, "c", "bash", "echo hi > out.txt"),
            message("assistant", "done"),
        ))
        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is Timeline.Bubble)
        val work = timeline[1] as Timeline.Work
        assertEquals(1, work.turn)
        assertEquals(3, work.calls.size)
        // The command decides the kind, not the tool name: everything is bash here.
        assertEquals(Action.READ, actionOf(work.calls[0]))
        assertEquals(Action.RUN, actionOf(work.calls[1]))
        assertEquals(Action.WRITE, actionOf(work.calls[2]))
        assertEquals("file.txt", work.calls[0].output)
        assertEquals("done", (timeline[2] as Timeline.Bubble).text)
    }

    @Test fun separateTurnsBecomeSeparateRows() {
        val timeline = buildTimeline(chat(true,
            call(1, 1, "a", "bash", "ls"),
            call(2, 1, "b", "bash", "pwd"),
        ))
        assertEquals(2, timeline.size)
        assertEquals(1, (timeline[0] as Timeline.Work).turn)
        assertEquals(2, (timeline[1] as Timeline.Work).turn)
    }

    @Test fun thinkingJoinsItsTurnAndRowsStayUnique() {
        val timeline = buildTimeline(chat(true,
            call(1, 1, "a", "bash", "ls"),
            event("assistant/thinking", JSONObject().put("text", "let me look")),
            message("assistant", "done"),
        ))
        assertEquals(2, timeline.size)
        val work = timeline[0] as Timeline.Work
        assertEquals("let me look", work.thinking)
        assertEquals(2, timeline.map { it.seq }.distinct().size)
    }

    @Test fun recordsWrittenBeforeNumberingStillGetUniqueRows() {
        val timeline = buildTimeline(chat(false,
            message("user", "hello"),
            call(1, 1, "a", "bash", "ls"),
            message("assistant", "hi"),
        ))
        assertEquals(3, timeline.size)
        assertEquals(3, timeline.map { it.seq }.distinct().size)
    }
}
