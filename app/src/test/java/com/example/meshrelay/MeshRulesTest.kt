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
    ) = MeshMessage(id, "them", type, "text", createdAt, ttl, copies, mutableListOf("them"), null)

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
        val m = MeshMessage("x-1", "x", MsgType.INFO, "gate 4 | gate 5", 1L, 6, 6, mutableListOf(), null)
        assertEquals("gate 4 | gate 5", Wire.decode(Wire.encode(m))!!.text)
    }

    @Test
    fun `rubbish is dropped, not crashed on`() {
        assertNull(Wire.decode("hello"))
        assertNull(Wire.decode("v9|a|b|INFO|1|1|1||"))
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
