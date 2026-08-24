package com.example.meshrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules run on a laptop in milliseconds. Every bug caught here is a bug not
 * hunted with three phones on a table at 2am.
 */
class MeshRulesTest {

    private fun rules(cap: Int = 200) = MeshRules(myNodeId = "me", storeCap = cap)

    private fun incoming(
        id: String,
        type: MsgType = MsgType.INFO,
        ttl: Int = 6,
        copies: Int = 6,
        createdAt: Long = 1000L
    ) = MeshMessage(
        id, "them", type, "text", null, null, null, createdAt, ttl, copies,
        mutableListOf("them"), null
    )

    @Test
    fun `wire format survives a round trip`() {
        val r = rules()
        val m = r.originate(MsgType.MISSING, "Child, red shirt, Gate 3", 1234L)
        val back = Wire.decode(Wire.encode(m))
        assertNotNull(back)
        assertEquals(m.id, back!!.id)
        assertEquals(m.type, back.type)
        assertEquals(m.text, back.text)
        assertEquals(m.ttl, back.ttl)
        assertEquals(m.path, back.path)
    }

    @Test
    fun `a pipe in the text does not break the packet`() {
        val m = MeshMessage(
            "x-1", "x", MsgType.INFO, "gate 4 | gate 5", null, null, null, 1L, 6, 6,
            mutableListOf(), null
        )
        assertEquals("gate 4 | gate 5", Wire.decode(Wire.encode(m))!!.text)
    }

    @Test
    fun `rubbish is dropped, not crashed on`() {
        assertNull(Wire.decode("hello"))
        assertNull(Wire.decode("v1|a|b|INFO|1|1|1||x|y"))
    }

    @Test
    fun `the reported position survives the trip`() {
        val r = rules()
        val here = Position(17.38500, 78.48670)
        val m = r.originate(MsgType.CROWD, "crush at the barrier", 1L, null, here)
        assertEquals(here, Wire.decode(Wire.encode(m))!!.pos)
    }

    @Test
    fun `a report with no position is still carried`() {
        val r = rules()
        val m = r.originate(MsgType.MEDICAL, "someone has collapsed", 1L)
        assertNull(m.pos)
        val back = Wire.decode(Wire.encode(m))
        assertNotNull(back)
        assertNull(back!!.pos)
    }

    @Test
    fun `a nonsense position is dropped, and the report still arrives`() {
        val m = Wire.decode("v5|a-1|a|INFO|banana|||6|6|1|a||hello")
        assertNotNull(m)
        assertNull(m!!.pos)
        assertEquals("hello", m.text)
    }

    @Test
    fun `distance between two points is right`() {
        // One ten-thousandth of a degree of latitude is about 11 metres, anywhere.
        val d = Position.metresBetween(Position(17.3850, 78.4867), Position(17.3851, 78.4867))
        assertTrue("got " + d, d > 10.0 && d < 12.5)
    }

    /**
     * A phone running the old build must fail loudly rather than half-work. A mixed-build
     * demo that silently misreads fields would look like a radio fault (Plan.md 11.5).
     */
    @Test
    fun `a packet from a previous wire version is refused`() {
        assertNull(Wire.decode("v1|a-1|a|INFO|6|6|1|a||hello"))
        assertNull(Wire.decode("v2|a-1|a|INFO|GATE_3|6|6|1|a||hello"))
        assertNull(Wire.decode("v3|a-1|a|INFO|17.1,78.1|6|6|1|a||hello"))
        // v4 is the build that had no delivery receipts in it.
        assertNull(Wire.decode("v4|a-1|a|MEDICAL|17.1,78.1||||6|6|1|a||hello"))
    }

    @Test
    fun `the same message is only accepted once`() {
        val r = rules()
        assertEquals(Verdict.ACCEPTED, r.onReceive(incoming("a-1"), "them"))
        assertEquals(Verdict.DUPLICATE, r.onReceive(incoming("a-1"), "them"))
        assertEquals(Verdict.DUPLICATE, r.onReceive(incoming("a-1"), "them"))
        assertEquals(2, r.duplicatesBlocked)
        assertEquals(1, r.storeSize())
    }

    @Test
    fun `a phone never re-accepts its own message coming back around`() {
        val r = rules()
        val mine = r.originate(MsgType.CROWD, "Gate 4 crowded", 1L)
        assertEquals(Verdict.DUPLICATE, r.onReceive(Wire.decode(Wire.encode(mine))!!, "someone"))
    }

    @Test
    fun `each hop costs one ttl`() {
        val r = rules()
        val m = incoming("a-1", ttl = 2, copies = 4)
        r.onReceive(m, "them")
        assertEquals(1, m.ttl)
        assertTrue(r.shouldForward(m))
    }

    @Test
    fun `handing a message on splits the copy budget, it is never created`() {
        val r = rules()
        val m = incoming("a-1", copies = 6)
        r.onReceive(m, "them")

        assertEquals(3, r.splitCopiesFor(m, "p1"))   // give 3
        assertEquals(3, m.copies)           // keep 3 - total is still 6
        assertEquals(1, r.splitCopiesFor(m, "p2"))
        assertEquals(2, m.copies)
        assertEquals(1, r.splitCopiesFor(m, "p3"))
        assertEquals(1, m.copies)
        assertEquals(0, r.splitCopiesFor(m, "p4"))   // nothing left to give
        assertEquals(1, m.copies)
    }

    /**
     * The bug found on real phones, 21 Aug: links break and re-form constantly, and
     * every reconnection re-flushed the store and paid copy budget again. Within a
     * minute a message allowed six copies was down to one and the mesh stopped
     * relaying, with nothing on screen to say why.
     */
    @Test
    fun `re-meeting the same phone costs no copy budget`() {
        val r = rules()
        val m = incoming("a-1", copies = 6)
        r.onReceive(m, "them")

        assertEquals(3, r.splitCopiesFor(m, "peerX"))
        assertEquals(3, m.copies)
        repeat(10) { assertEquals(0, r.splitCopiesFor(m, "peerX")) }
        assertEquals(3, m.copies)          // ten reconnections later, still 3
        assertTrue(r.shouldForward(m))     // and it can still reach someone new
    }

    @Test
    fun `a message is never handed back to the phone it came from`() {
        val r = rules()
        val m = incoming("a-1", copies = 6)
        r.onReceive(m, "them")
        assertEquals(0, r.splitCopiesFor(m, "them"))
        assertTrue(r.flushOrderFor("them").none { it.id == "a-1" })
        assertTrue(r.flushOrderFor("someone-else").any { it.id == "a-1" })
    }

    @Test
    fun `an emergency is allowed more of the crowd's radio time than chatter`() {
        val r = rules()
        val med = r.originate(MsgType.MEDICAL, "collapsed, sector 12", 1L)
        val info = r.originate(MsgType.INFO, "where is the food stall", 2L)
        assertTrue(med.copies > info.copies)
        assertEquals(MsgType.MEDICAL.copyBudget, med.copies)
        assertEquals(MsgType.INFO.copyBudget, info.copies)
    }

    /**
     * The bug found on hardware, 21 Aug: INFO had a copy budget of 3, so the first relay
     * received 1 copy and refused to pass it on. INFO never reached the third phone while
     * every other type did. It looked like a radio fault and it was arithmetic.
     *
     * Walks A -> B -> C for every message type, the way the real phones do.
     */
    @Test
    fun `every message type survives two hops to a third phone`() {
        // A stand-in for the real signature check, so signed types are not refused here
        // for a reason this test is not about.
        val verifier: (MeshMessage) -> Boolean = { it.sig == "ok" }
        val signer: (MeshMessage) -> String? = { "ok" }

        for (type in MsgType.entries) {
            val a = MeshRules(myNodeId = "A", verifySignature = verifier)
            val b = MeshRules(myNodeId = "B", verifySignature = verifier)
            val c = MeshRules(myNodeId = "C", verifySignature = verifier)

            val atA = a.originate(type, "test", 1L, if (type.needsSignature) signer else null)

            val giveB = a.splitCopiesFor(atA, "B")
            assertTrue(type.name + ": A had nothing to hand to B", giveB >= 1)
            val atB = Wire.decode(Wire.encode(atA.copy().also { it.copies = giveB }))!!
            assertEquals(type.name + ": B refused it", Verdict.ACCEPTED, b.onReceive(atB, "A"))
            assertTrue(type.name + ": B will not relay it", b.shouldForward(atB))

            val giveC = b.splitCopiesFor(atB, "C")
            assertTrue(type.name + ": B had nothing to hand to C", giveC >= 1)
            val atC = Wire.decode(Wire.encode(atB.copy().also { it.copies = giveC }))!!
            assertEquals(type.name + ": C refused it", Verdict.ACCEPTED, c.onReceive(atC, "B"))

            assertEquals(type.name + ": path is wrong", listOf("A", "B", "C"), atC.path)
        }
    }

    @Test
    fun `a late location catches up with the report it belongs to`() {
        val phone = MeshRules(myNodeId = "far")

        // The urgent report arrives first, with no position - the sender had no fix.
        val report = MeshMessage(
            "a-1", "a", MsgType.MEDICAL, "chest pain", null, null, null,
            1L, 6, 24, mutableListOf("a"), null
        )
        assertEquals(Verdict.ACCEPTED, phone.onReceive(report, "a"))
        assertNull(phone.inboxOrder().first().pos)

        // The follow-up arrives once the sender's GPS finally worked.
        val here = Position(17.38500, 78.48670)
        val fix = MeshMessage(
            "a-2", "a", MsgType.LOCFIX, "", here, null, "a-1",
            2L, 6, 16, mutableListOf("a"), null
        )
        assertEquals(Verdict.ACCEPTED, phone.onReceive(fix, "a"))

        assertEquals(here, phone.inboxOrder().first().pos)
        assertEquals("the update itself must never be shown", 1, phone.inboxOrder().size)
    }

    @Test
    fun `a late location still lands if it arrives before the report`() {
        val phone = MeshRules(myNodeId = "far")
        val here = Position(17.38500, 78.48670)

        phone.onReceive(
            MeshMessage("a-2", "a", MsgType.LOCFIX, "", here, null, "a-1",
                2L, 6, 16, mutableListOf("a"), null), "a"
        )
        phone.onReceive(
            MeshMessage("a-1", "a", MsgType.MEDICAL, "chest pain", null, null, null,
                1L, 6, 24, mutableListOf("a"), null), "a"
        )
        assertEquals(here, phone.inboxOrder().first().pos)
    }

    /**
     * If a location could be bolted onto a signed evacuation order after the fact, that is
     * the redirect-the-crowd hole straight back open.
     */
    @Test
    fun `a signed order can never be amended`() {
        val phone = MeshRules(myNodeId = "far", verifySignature = { it.sig == "ok" })
        val order = MeshMessage(
            "o-1", "org", MsgType.AUTHORITY, "EVACUATE", Position(17.0, 78.0), null, null,
            1L, 6, 24, mutableListOf("org"), "ok"
        )
        assertEquals(Verdict.ACCEPTED, phone.onReceive(order, "org"))

        val hijack = MeshMessage(
            "o-2", "org", MsgType.LOCFIX, "", Position(12.0, 77.0), null, "o-1",
            2L, 6, 16, mutableListOf("org"), null
        )
        phone.onReceive(hijack, "org")
        assertEquals(Position(17.0, 78.0), phone.inboxOrder().first().pos)
    }

    @Test
    fun `only the author of a report may amend it`() {
        val phone = MeshRules(myNodeId = "far")
        phone.onReceive(
            MeshMessage("a-1", "a", MsgType.MEDICAL, "chest pain", null, null, null,
                1L, 6, 24, mutableListOf("a"), null), "a"
        )
        phone.onReceive(
            MeshMessage("z-9", "z", MsgType.LOCFIX, "", Position(1.0, 1.0), null, "a-1",
                2L, 6, 16, mutableListOf("z"), null), "z"
        )
        assertNull(phone.inboxOrder().first().pos)
    }

    @Test
    fun `location updates are not rate limited as emergencies`() {
        val r = rules()
        val now = 10_000_000L
        repeat(5) { r.originate(MsgType.MEDICAL, "help", now) }
        assertTrue(!r.canOriginate(MsgType.MEDICAL, now))
        // The follow-up must still get out, or the report it belongs to stays unlocated.
        assertTrue(r.canOriginate(MsgType.LOCFIX, now))
    }

    /**
     * Which report a responder walks towards. Urgency decides; distance only breaks ties.
     * Proximity pretending to be judgement is how the nearest trivial thing gets attended
     * to while a heart attack waits.
     */
    @Test
    fun `a distant emergency outranks a nearby question`() {
        val here = Position(17.38500, 78.48670)
        val nearChatter = MeshMessage(
            "i-1", "a", MsgType.INFO, "where is the food stall",
            Position(17.38505, 78.48670), null, null, 1L, 6, 6, mutableListOf(), null
        )
        val farEmergency = MeshMessage(
            "m-1", "b", MsgType.MEDICAL, "chest pain",
            Position(17.38700, 78.48670), null, null, 1L, 6, 24, mutableListOf(), null
        )
        assertEquals("m-1", chooseTarget(listOf(nearChatter, farEmergency), here)!!.id)
    }

    @Test
    fun `among equally urgent reports the nearest is chosen`() {
        val here = Position(17.38500, 78.48670)
        val near = MeshMessage(
            "m-near", "a", MsgType.MEDICAL, "collapsed",
            Position(17.38510, 78.48670), null, null, 1L, 6, 24, mutableListOf(), null
        )
        val far = MeshMessage(
            "m-far", "b", MsgType.MEDICAL, "collapsed",
            Position(17.39000, 78.48670), null, null, 1L, 6, 24, mutableListOf(), null
        )
        assertEquals("m-near", chooseTarget(listOf(far, near), here)!!.id)
    }

    @Test
    fun `nothing to head for without a position on either side`() {
        val located = MeshMessage(
            "m-1", "a", MsgType.MEDICAL, "collapsed", Position(17.0, 78.0), null, null,
            1L, 6, 24, mutableListOf(), null
        )
        val unlocated = MeshMessage(
            "m-2", "a", MsgType.MEDICAL, "collapsed", null, null, null,
            1L, 6, 24, mutableListOf(), null
        )
        assertNull(chooseTarget(listOf(located), null))
        assertNull(chooseTarget(listOf(unlocated), Position(17.0, 78.0)))
    }

    @Test
    fun `a location follow-up is never something to walk towards`() {
        val here = Position(17.0, 78.0)
        val plumbing = MeshMessage(
            "a-2", "a", MsgType.LOCFIX, "", Position(17.001, 78.0), null, "a-1",
            1L, 6, 16, mutableListOf(), null
        )
        assertNull(chooseTarget(listOf(plumbing), here))
    }

    @Test
    fun `no message type is given a budget too small to be relayed`() {
        // Halving means a budget under 4 cannot survive a single relay. 6 leaves margin.
        for (type in MsgType.entries) {
            assertTrue(type.name + " budget too small", type.copyBudget >= 6)
        }
    }

    @Test
    fun `the rate limit can be cleared for rehearsal`() {
        val r = rules()
        val now = 10_000_000L
        repeat(5) { r.originate(MsgType.MEDICAL, "help", now) }
        assertTrue(!r.canOriginate(MsgType.MEDICAL, now))
        r.resetRateLimit()
        assertTrue(r.canOriginate(MsgType.MEDICAL, now))
    }

    @Test
    fun `the last copy is delivered directly, never spread further`() {
        val r = rules()
        val m = incoming("a-1", copies = 1)
        r.onReceive(m, "them")
        assertTrue(!r.shouldForward(m))
        assertEquals(1, r.storeSize())   // still held, still readable
    }

    @Test
    fun `a message with no ttl left is kept but never forwarded again`() {
        val r = rules()
        val m = incoming("a-1", ttl = 1)
        r.onReceive(m, "them")
        assertEquals(0, m.ttl)
        assertTrue(!r.shouldForward(m))
        assertEquals(1, r.storeSize())   // still readable on this phone
    }

    @Test
    fun `every hop is recorded in the path`() {
        val r = rules()
        val m = incoming("a-1")
        r.onReceive(m, "them")
        assertEquals(listOf("them", "me"), m.path)
    }

    @Test
    fun `a medical call jumps ahead of thirty info messages`() {
        val r = rules()
        repeat(30) { r.onReceive(incoming("i-$it", MsgType.INFO, createdAt = it.toLong()), "them") }
        r.onReceive(incoming("med-1", MsgType.MEDICAL, createdAt = 999L), "them")

        assertEquals("med-1", r.flushOrder().first().id)
        assertEquals("med-1", r.inboxOrder().first().id)
    }

    @Test
    fun `within one priority the oldest is handed on first`() {
        val r = rules()
        r.onReceive(incoming("late", MsgType.CROWD, createdAt = 500L), "them")
        r.onReceive(incoming("early", MsgType.CROWD, createdAt = 100L), "them")
        assertEquals(listOf("early", "late"), r.flushOrder().map { it.id })
    }

    @Test
    fun `a full store throws away junk and keeps the emergency`() {
        val r = rules(cap = 10)
        repeat(10) {
            val m = incoming("i-$it", MsgType.INFO, createdAt = it.toLong())
            r.onReceive(m, "them")
            r.markForwarded(m)          // they have all had their chance to move
        }
        r.onReceive(incoming("med-1", MsgType.MEDICAL, createdAt = 99L), "them")

        assertEquals(10, r.storeSize())
        assertEquals(1, r.evicted)
        assertTrue(r.flushOrder().any { it.id == "med-1" })
        assertTrue(r.flushOrder().none { it.id == "i-0" })   // oldest junk went first
    }

    @Test
    fun `nothing is evicted before it has had one chance to move`() {
        val r = rules(cap = 3)
        repeat(4) { r.onReceive(incoming("i-$it", MsgType.INFO, createdAt = it.toLong()), "them") }
        // Cap must still be honoured, but only because there was no better victim.
        assertEquals(3, r.storeSize())
    }

    @Test
    fun `an unsigned order to move the crowd is refused`() {
        val r = rules()
        val fake = incoming("fake-1", MsgType.AUTHORITY)
        assertEquals(Verdict.UNSIGNED_AUTHORITY, r.onReceive(fake, "them"))
        assertEquals(0, r.storeSize())   // never stored, so never forwarded or shown
    }

    @Test
    fun `a signed order is accepted when the key checks out`() {
        val r = MeshRules(myNodeId = "me", verifySignature = { it.sig == "good" })
        val real = incoming("real-1", MsgType.AUTHORITY).copy(sig = "good")
        assertEquals(Verdict.ACCEPTED, r.onReceive(real, "them"))
    }

    // -----------------------------------------------------------------------
    // Question 6 of the seven: how does anyone ever know it arrived?
    // -----------------------------------------------------------------------

    /** A phone that cannot act on a report has no business confirming it. */
    @Test
    fun `an ordinary phone never confirms anything`() {
        val r = rules()
        assertNull(r.receiptFor(incoming("a-1", MsgType.MEDICAL), 5000L))
    }

    @Test
    fun `a responder confirms an urgent report, and says how far it travelled`() {
        val r = rules()
        r.amResponder = true
        val m = incoming("a-1", MsgType.MEDICAL)
        r.onReceive(m, "them")                       // path becomes them > me

        val receipt = r.receiptFor(m, 5000L)
        assertNotNull(receipt)
        assertEquals(MsgType.RECEIPT, receipt!!.type)
        assertEquals("a-1", receipt.ref)             // pointed back at the report
        assertEquals("1", receipt.text)              // one hop
        assertEquals("me", receipt.origin)
    }

    /**
     * A receipt is a whole extra message travelling back through the crowd. Spending that
     * on "where is the food stall" is spending the crowd's radio time on nothing - the
     * same reasoning that already limits location follow-ups to urgent reports.
     */
    @Test
    fun `only urgent reports are worth confirming`() {
        assertTrue(expectsReceipt(MsgType.MEDICAL))
        assertTrue(expectsReceipt(MsgType.MISSING))
        assertTrue(expectsReceipt(MsgType.FIRE))
        assertTrue(expectsReceipt(MsgType.SECURITY))
        assertTrue(!expectsReceipt(MsgType.CROWD))
        assertTrue(!expectsReceipt(MsgType.INFO))
        // An order is a broadcast to everybody - there is no single arrival to confirm.
        assertTrue(!expectsReceipt(MsgType.AUTHORITY))
        // And plumbing must never trigger plumbing, or it never stops.
        assertTrue(!expectsReceipt(MsgType.LOCFIX))
        assertTrue(!expectsReceipt(MsgType.RECEIPT))
    }

    @Test
    fun `a responder does not confirm a receipt, or its own report`() {
        val r = rules()
        r.amResponder = true

        val ack = incoming("a-2", MsgType.RECEIPT)
        r.onReceive(ack, "them")
        assertNull(r.receiptFor(ack, 5000L))

        val mine = r.originate(MsgType.MEDICAL, "help", 1000L)
        assertNull(r.receiptFor(mine, 5000L))
    }

    @Test
    fun `the sender's screen flips from in flight to delivered`() {
        val r = rules()
        val sent = r.originate(MsgType.MISSING, "Child, red shirt", 1000L)

        val before = r.deliveryOf(sent.id)
        assertNotNull(before)
        assertTrue(!before!!.isDelivered)
        assertEquals(1, r.awaitingConfirmation())

        // The confirmation comes back, having been created by a responder two hops away.
        val ack = MeshMessage(
            "c-1", "responder", MsgType.RECEIPT, "2", null, null, sent.id,
            1500L, 6, 6, mutableListOf("responder"), null
        )
        assertEquals(Verdict.RECEIPT_FOR_ME, r.onReceive(ack, "them", now = 42_000L))

        val after = r.deliveryOf(sent.id)!!
        assertTrue(after.isDelivered)
        assertEquals(2, after.hops)
        assertEquals("responder", after.by)
        assertEquals(41L, after.seconds)     // this phone's own clock, start to finish
        assertEquals(1, r.confirmed)
        assertEquals(0, r.awaitingConfirmation())
    }

    /** It has reached the only phone that was waiting for it. Nobody else needs it. */
    @Test
    fun `a receipt stops at the phone it was meant for`() {
        val r = rules()
        val sent = r.originate(MsgType.MEDICAL, "help", 1000L)
        val storedBefore = r.storeSize()

        val ack = MeshMessage(
            "c-1", "responder", MsgType.RECEIPT, "1", null, null, sent.id,
            1500L, 6, 6, mutableListOf("responder"), null
        )
        r.onReceive(ack, "them", now = 2000L)

        assertEquals(storedBefore, r.storeSize())            // not stored
        assertTrue(r.flushOrder().none { it.id == "c-1" })   // and never handed on
    }

    /** Somebody else's confirmation is carried like any other message. */
    @Test
    fun `a receipt for someone else is relayed, not swallowed`() {
        val r = rules()
        val ack = MeshMessage(
            "c-1", "responder", MsgType.RECEIPT, "1", null, null, "stranger-9",
            1500L, 6, 6, mutableListOf("responder"), null
        )
        assertEquals(Verdict.ACCEPTED, r.onReceive(ack, "them"))
        assertTrue(r.shouldForward(ack))
        assertTrue(r.flushOrder().any { it.id == "c-1" })
    }

    /** Two responders can both get the report. The second one does not re-time it. */
    @Test
    fun `the first confirmation wins`() {
        val r = rules()
        val sent = r.originate(MsgType.MEDICAL, "help", 1000L)

        fun ack(id: String, from: String, hops: String) = MeshMessage(
            id, from, MsgType.RECEIPT, hops, null, null, sent.id,
            1500L, 6, 6, mutableListOf(from), null
        )
        r.onReceive(ack("c-1", "first", "1"), "them", now = 3000L)
        r.onReceive(ack("c-2", "second", "4"), "them", now = 90_000L)

        val d = r.deliveryOf(sent.id)!!
        assertEquals("first", d.by)
        assertEquals(1, d.hops)
        assertEquals(2L, d.seconds)
        assertEquals(1, r.confirmed)
    }

    /** Confirming a report must never count against the sender's emergency allowance. */
    @Test
    fun `confirmations are not rate limited as emergencies`() {
        val r = rules()
        r.amResponder = true
        val now = 10_000_000L
        repeat(20) {
            val m = incoming("a-$it", MsgType.MEDICAL)
            r.onReceive(m, "them")
            assertNotNull(r.receiptFor(m, now))
        }
        assertTrue(r.canOriginate(MsgType.MEDICAL, now))
    }

    /** A responder's own report is already where reports are trying to get to. */
    @Test
    fun `a responder does not wait on its own reports`() {
        val r = rules()
        r.amResponder = true
        val m = r.originate(MsgType.MEDICAL, "help", 1000L)
        assertNull(r.deliveryOf(m.id))
        assertEquals(0, r.awaitingConfirmation())
    }

    /** Nothing is left in flight forever just because nobody promised to confirm it. */
    @Test
    fun `chatter is never left waiting for a confirmation`() {
        val r = rules()
        val info = r.originate(MsgType.INFO, "where is the food stall", 1000L)
        assertNull(r.deliveryOf(info.id))
        assertEquals(0, r.awaitingConfirmation())
    }

    @Test
    fun `one phone cannot flood the network with emergencies`() {
        val r = rules()
        val now = 10_000_000L
        repeat(5) {
            assertTrue(r.canOriginate(MsgType.MEDICAL, now))
            r.originate(MsgType.MEDICAL, "help", now)
        }
        assertTrue(!r.canOriginate(MsgType.MEDICAL, now))

        // Chatter is not rate limited, and the limit lifts after an hour.
        assertTrue(r.canOriginate(MsgType.INFO, now))
        assertTrue(r.canOriginate(MsgType.MEDICAL, now + 3_600_001L))
    }
}
