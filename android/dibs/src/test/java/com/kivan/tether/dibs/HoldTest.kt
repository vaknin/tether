package com.kivan.tether.dibs

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Task #69: the user holds dibs's answer while they're still writing.
class HoldTest {
    private val keeping = JSONObject().put("kind", "held").put("note", "dibs keeps its reply until you tap Go")
        .put("button", "Go").put("action", "go-ahead").put("style", "primary")

    private val ready = JSONObject().put("kind", "held").put("note", "dibs's reply is ready")
        .put("button", "Go").put("action", "go-ahead").put("style", "primary")

    private fun chip(kind: String): JSONObject = when (kind) {
        "held" -> keeping
        "ready" -> ready
        else -> JSONObject().put("kind", kind).put("note", JSONObject.NULL).put("button", "Wait")
            .put("action", "hold").put("style", "outline").put("tapped", keeping)
    }

    private fun view(hold: String?) = DibsView.parse(
        JSONObject().put("state", JSONObject().put("busy", true).put("hold", hold?.let(::chip) ?: JSONObject.NULL)),
    )

    @After
    fun reset() {
        Dibs.holdTap = null
    }

    @Test
    fun typingPingsAreThrottledAndStopOnce() {
        val sent = mutableListOf<Boolean>()
        val t = Typing { sent += it }
        t.edited("h", 1_000)
        t.edited("he", 2_000)
        t.edited("hel", 1_000 + Typing.EVERY_MS)
        assertEquals(listOf(true, true), sent)
        t.edited("", 7_000)
        t.stopped()
        assertEquals("one not-typing for an emptied box", listOf(true, true, false), sent)
        t.edited("x", 8_000)
        t.sent()
        t.stopped()
        assertEquals("a sent box says nothing more: the line tells dibs", listOf(true, true, false, true), sent)
    }

    @Test
    fun stateHoldIsParsedAndAbsentFromAnOlderDibs() {
        val wait = view("wait").state.hold!!
        assertEquals("wait" to "Wait", wait.kind to wait.button)
        assertNull(wait.note)
        assertEquals("hold", wait.action)
        assertEquals("held", wait.tapped?.kind)
        assertEquals("dibs keeps its reply until you tap Go", wait.tapped?.note)
        assertEquals("Go" to "go-ahead", view("held").state.hold?.let { it.button to it.action })
        assertEquals("dibs keeps its reply until you tap Go", view("held").state.hold?.note)
        assertEquals("dibs's reply is ready", view("ready").state.hold?.note)
        assertNull(view("held").state.hold?.tapped)
        assertNull(view(null).state.hold)
        assertNull(DibsView.parse(JSONObject().put("state", JSONObject())).state.hold)
        assertNull("a string from a build of dibs before the words moved", DibsView.parse(JSONObject().put("state", JSONObject().put("hold", "wait"))).state.hold)
    }

    @Test
    fun aTapShowsAtOnceUntilTheViewAgrees() {
        val now = 100_000L
        assertEquals("wait", Dibs.hold(view("wait"), now)?.kind)
        Dibs.holdTap = HoldTap(view("wait").state.hold!!.tapped, "wait", now)
        assertEquals("held", Dibs.hold(view("wait"), now + 1)?.kind)
        assertEquals("a stale tap gives way to the view", "wait", Dibs.hold(view("wait"), now + Dibs.TAP_MS)?.kind)
        Dibs.holdTap = HoldTap(null, "held", now)
        assertNull("Go hides the chip while the view still says held", Dibs.hold(view("held"), now + 1))
        assertEquals("wait", Dibs.hold(view("wait"), now + 1)?.kind)
    }

    @Test
    fun theViewAgreeingEndsTheTap() {
        val now = System.currentTimeMillis()
        Dibs.holdTap = HoldTap(view("wait").state.hold!!.tapped, "wait", now)
        Dibs.seen(view("wait"))
        assertEquals("still says wait", "held", Dibs.holdTap?.shown?.kind)
        Dibs.seen(view("held"))
        assertNull(Dibs.holdTap)
        Dibs.holdTap = HoldTap(null, "held", now)
        Dibs.seen(view("held"))
        assertEquals(null to "held", Dibs.holdTap?.shown to Dibs.holdTap?.gone)
        Dibs.seen(view(null))
        assertNull(Dibs.holdTap)
    }
}
