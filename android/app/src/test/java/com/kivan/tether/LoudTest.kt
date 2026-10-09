package com.kivan.tether

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudTest {
    @Test
    fun loudPostsUseTheUrgentChannel() {
        assertEquals("loud", Notifier.channelFor("dibs", true))
        assertEquals("app.dibs", Notifier.channelFor("dibs", false))
    }

    @Test
    fun theUrgentChannelIsNotAnAppChannel() {
        // appChannels() deletes every "app."-prefixed channel it doesn't want.
        assertFalse(Notifier.LOUD.startsWith("app."))
    }

    @Test
    fun aLoudPostAlertsEvenWithAReusedTag() {
        assertFalse(Notifier.alertOnce("task:7", true))
        assertFalse(Notifier.alertOnce(null, true))
        assertTrue(Notifier.alertOnce("task:7", false))
        assertFalse(Notifier.alertOnce(null, false))
    }
}
