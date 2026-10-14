package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {

    @Test fun callerNameLosesWhatTheClusterCantDraw() {
        val mom = CallerText.of("Mom ❤️", "+91 98765 43210")
        assertEquals("MOM", mom.text)
        assertTrue("❤" in mom.dropped)
        assertEquals("DR RAHUL", CallerText.of("Dr. Rahul", null).text)
        // nothing drawable left (only emoji / another script): the number, digits only
        val emojiOnly = CallerText.of("🏍️🔥", "+91 98765-43210")
        assertEquals("919876543210", emojiOnly.text)
        assertTrue(emojiOnly.usedNumber)
        assertEquals("919876543210", CallerText.of("राहुल", "+919876543210").text)
        assertEquals("UNKNOWN", CallerText.of(null, null).text)
    }

    @Test fun textsFromTheCallerArePaged() {
        assertTrue(SmsPager.sameNumber("+91 98765 43210", "09876543210"))
        assertTrue(!SmsPager.sameNumber("+919876543210", "+919876543211"))
        val t = SmsPager.text("MOM", listOf("Ok, call me when free.", "Bring milk 🥛"))
        assertEquals("MOM: OK CALL ME WHEN FREE BRING MILK", t)
        assertEquals(listOf("MOM: OK CALL ME", "WHEN FREE BRING", "MILK"), SmsPager.pages(t))
        assertEquals(listOf("ABCDEFGHIJKLMNOPQ", "RS"), SmsPager.pages("ABCDEFGHIJKLMNOPQRS"))
    }

    @Test fun infoButtonPressAndLongPress() {
        val b = InfoButton(longMs = 1500)
        assertEquals(InfoButton.Event.DOWN, b.feed(true, 0))
        assertNull(b.feed(true, 1000))
        assertEquals(InfoButton.Event.LONG, b.feed(true, 1600))
        assertNull(b.feed(true, 2600))                       // long fires once
        assertNull(b.feed(false, 3000))
        assertEquals(InfoButton.Event.DOWN, b.feed(true, 3500))
        assertEquals(2, b.pressId)
    }

    @Test fun declineThenHoldToTextBack() {
        val f = CallFlow(promptMs = 12_000)
        val b = InfoButton()
        f.ring("+919876543210")
        assertEquals(CallFlow.Action.Decline, f.button(b.feed(true, 0)!!, b.pressId, 0))
        assertEquals(CallFlow.Action.OfferSms("+919876543210"), f.declined(0))
        assertTrue(f.awaitingRelease)
        // keeping the declining press held doesn't send the text
        assertNull(f.button(b.feed(true, 2000)!!, b.pressId, 2000))
        b.feed(false, 2500)
        assertEquals(CallFlow.Action.OfferSms("+919876543210"), f.released(2500))
        b.feed(true, 3000)
        assertEquals(CallFlow.Action.SendSms("+919876543210"), f.button(b.feed(true, 4600)!!, b.pressId, 4600))
        assertNull(f.promptNumber)
    }

    @Test fun offerClockStartsWhenTheSwitchIsLetGo() {
        val f = CallFlow(promptMs = 30_000, releaseWaitMs = 10_000)
        f.ring("+919876543210")
        f.button(InfoButton.Event.DOWN, 1, 0); f.declined(0)
        // the cluster never showed the release: the offer starts after 10 s anyway
        assertNull(f.tick(9_000))
        assertEquals(CallFlow.Action.OfferSms("+919876543210"), f.tick(10_000))
        // 17 s after declining (a hold that failed on 11 Oct with the old 12 s window) now sends
        assertEquals(CallFlow.Action.SendSms("+919876543210"), f.button(InfoButton.Event.LONG, 2, 17_000))
    }

    @Test fun holdStartedJustBeforeTheEndStillCounts() {
        val f = CallFlow(promptMs = 12_000)
        f.ring("+919876543210")
        assertEquals(CallFlow.Action.OfferSms("+919876543210"), f.ended(0))
        assertEquals(CallFlow.Action.SendSms("+919876543210"), f.button(InfoButton.Event.LONG, 5, 13_000))
    }

    @Test fun missedCallOffersAndTimesOut() {
        val f = CallFlow(promptMs = 12_000)
        f.ring("+919876543210")
        assertEquals(CallFlow.Action.OfferSms("+919876543210"), f.ended(1000))
        assertNull(f.tick(5000))
        assertNull(f.tick(14_000))  // a hold that began in time is still landing
        assertEquals(CallFlow.Action.Clear, f.tick(15_100))
        assertNull(f.button(InfoButton.Event.LONG, 9, 16_000))  // too late
        // answered: nothing offered
        f.ring("+911111111111"); f.answered()
        assertNull(f.ended(20_000))
    }

    @Test fun marqueeScrollsLongTextOnly() {
        assertEquals(listOf("MG ROAD"), Marquee.frames("MG ROAD"))
        val f = Marquee.frames("KEEP RIGHT TO TAKE THE FLYOVER")
        assertEquals("KEEP RIGHT TO TAK", f.first())
        assertEquals("EEP RIGHT TO TAKE", f[1])
        assertTrue(f.all { it.length == 17 })
        assertEquals(30 + 4, f.size)   // the text and the gap, then round again
        assertEquals("KEEP RIGHT TO TAKE THE FLYOVER", NavParser.helpLine(NavParser.parse("300 m · Keep right to take the flyover", null, null)!!, 300, scroll = true).let { it })
    }
}
