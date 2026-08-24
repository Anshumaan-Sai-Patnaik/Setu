package com.example.meshrelay

/**
 * THE DECISION LAYER.
 *
 * This file is deliberately self-contained: no Android imports, no UI, no Bluetooth.
 * It is the part of the project that is actually interesting, and it is kept separate
 * so it can be hand-ported line-for-line into the browser simulator (Plan.md 17.3).
 *
 * On stage: "the same rules, implemented twice" - NOT "the same code running".
 *
 * Anyone can make two phones talk over Bluetooth. The engineering is here: what a phone
 * does when it is holding sixty messages, can forward three, and one is a heart attack.
 */

// ---------------------------------------------------------------------------
// Message types. The APP assigns priority from the type; the user never types a
// priority. Priority is NOT sent over the wire - every node recomputes it from the
// type - so an edited build cannot inject a "priority 99" message.
// ---------------------------------------------------------------------------
enum class MsgType(
    val priority: Int,
    val copyBudget: Int,
    val needsSignature: Boolean,
    val label: String
) {
    // copyBudget is how much of the crowd's radio time this kind of message is allowed
    // to spend. An emergency may spread widely. A question about the food stall may not.
    MEDICAL(10, 16, false, "Medical"),
    AUTHORITY(10, 16, true, "OFFICIAL"),   // evacuation / crowd direction - signed only
    MISSING(9, 12, false, "Missing person"),
    FIRE(8, 12, false, "Fire"),
    SECURITY(8, 12, false, "Security"),
    CROWD(5, 8, false, "Crowding"),
    INFO(1, 3, false, "Info");

    companion object {
        fun from(name: String): MsgType? = entries.firstOrNull { it.name == name }
    }
}

// ---------------------------------------------------------------------------
// The message. Kept small on purpose: Bluetooth in a packed crowd moves a few kB/s
// and encounters last seconds. Text only, max 180 chars. Plan.md 8.1.
// ---------------------------------------------------------------------------
data class MeshMessage(
    val id: String,                 // "<nodeId>-<counter>" - unique with no coordination
    val origin: String,             // node that created it
    val type: MsgType,
    val text: String,
    val createdAt: Long,            // epoch millis
    var ttl: Int,                   // hops remaining
    var copies: Int,                // copy budget (spray-and-wait) - Plan.md 17.5
    val path: MutableList<String>,  // for demo visualisation only
    val sig: String?                // present only on AUTHORITY messages
) {
    val priority: Int get() = type.priority
}

// ---------------------------------------------------------------------------
// Wire format. Pipe-delimited, not JSON: shorter on a slow radio, and it ports to
// JavaScript in ten lines. "v1" first so a mixed-version demo fails loudly rather
// than silently misreading fields.
//
//   v1|id|origin|TYPE|ttl|copies|createdAt|a,b,c|sig|text
// ---------------------------------------------------------------------------
object Wire {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p")
    private fun unesc(s: String) = s.replace("\\p", "|").replace("\\\\", "\\")

    fun encode(m: MeshMessage): String = listOf(
        "v1",
        m.id,
        m.origin,
        m.type.name,
        m.ttl.toString(),
        m.copies.toString(),
        m.createdAt.toString(),
        m.path.joinToString(","),
        m.sig ?: "",
        esc(m.text)
    ).joinToString("|")

    /** Returns null for anything malformed. A bad packet is dropped, never crashes a node. */
    fun decode(raw: String): MeshMessage? {
        val f = raw.split("|")
        if (f.size != 10 || f[0] != "v1") return null
        val type = MsgType.from(f[3]) ?: return null
        return MeshMessage(
            id = f[1],
            origin = f[2],
            type = type,
            text = unesc(f[9]),
            createdAt = f[6].toLongOrNull() ?: return null,
            ttl = f[4].toIntOrNull() ?: return null,
            copies = f[5].toIntOrNull() ?: return null,
            path = if (f[7].isEmpty()) mutableListOf() else f[7].split(",").toMutableList(),
            sig = f[8].ifEmpty { null }
        )
    }
}

/** What the rules decided to do with an incoming message. */
enum class Verdict { DUPLICATE, UNSIGNED_AUTHORITY, ACCEPTED }

// ---------------------------------------------------------------------------
// The rules themselves.
// ---------------------------------------------------------------------------
class MeshRules(
    private val myNodeId: String,
    var storeCap: Int = 200,          // drop to ~10 for the eviction demo (Plan.md 17.2)
    val hopLimit: Int = 6,
    private val highPriorityPerHour: Int = 5,
    private val verifySignature: (MeshMessage) -> Boolean = { false }
) {
    private val seen = LinkedHashSet<String>()
    private val store = mutableListOf<MeshMessage>()
    private val forwardedAtLeastOnce = mutableSetOf<String>()

    // messageId -> node IDs already known to hold it. Peers drop in and out of range
    // constantly, so without this a phone re-hands the same message to the same peer
    // every time the link comes back, and pays copy budget for it each time.
    private val handedTo = mutableMapOf<String, MutableSet<String>>()
    private val highPriorityOriginTimes = mutableListOf<Long>()
    private var counter = 0

    var duplicatesBlocked = 0; private set
    var evicted = 0; private set
    var forwards = 0; private set

    fun storeSize() = store.size

    // -- creating -----------------------------------------------------------

    /**
     * Rate limit: one joker with a phone must not be able to bury every real
     * emergency under fake ones. Low-priority chatter is not limited.
     */
    fun canOriginate(type: MsgType, now: Long): Boolean {
        if (type.priority < 8) return true
        highPriorityOriginTimes.removeAll { now - it > 3_600_000L }
        return highPriorityOriginTimes.size < highPriorityPerHour
    }

    /**
     * The signer is passed in rather than called directly, so this file keeps no
     * knowledge of crypto or of Android and can still be ported to the simulator.
     * A phone with no key signs nothing - and that is the whole point of the demo:
     * it can still *type* an evacuation order, it just cannot make one anybody obeys.
     */
    fun originate(
        type: MsgType,
        text: String,
        now: Long,
        signer: ((MeshMessage) -> String?)? = null
    ): MeshMessage {
        if (type.priority >= 8) highPriorityOriginTimes.add(now)
        val draft = MeshMessage(
            id = myNodeId + "-" + (++counter),
            origin = myNodeId,
            type = type,
            text = text.take(180),
            createdAt = now,
            ttl = hopLimit,
            copies = type.copyBudget,
            path = mutableListOf(myNodeId),
            sig = null
        )
        // Signed after the id and timestamp exist, because both are inside the signature.
        val m = if (signer == null) draft else draft.copy(sig = signer(draft))
        seen.add(m.id)
        insert(m)
        return m
    }

    // -- receiving ----------------------------------------------------------

    /**
     * Rule 1 of the whole system: seen it before, drop it silently.
     * Without this the crowd drowns in duplicates within seconds.
     */
    fun onReceive(m: MeshMessage, fromNode: String?): Verdict {
        // Whoever handed it over obviously has it. Recording that here is what stops
        // the message being sent straight back where it came from - the "forward to
        // everyone except the sender" rule, kept in the rules rather than in the UI.
        if (fromNode != null) handedTo.getOrPut(m.id) { mutableSetOf() }.add(fromNode)

        if (m.id in seen) {
            duplicatesBlocked++
            return Verdict.DUPLICATE
        }
        seen.add(m.id)

        // Identity decides the ceiling. An unsigned order to move a crowd is the one
        // message type that could kill people, so it is never stored and never shown.
        if (m.type.needsSignature && !verifySignature(m)) {
            return Verdict.UNSIGNED_AUTHORITY
        }

        m.path.add(myNodeId)
        m.ttl -= 1
        insert(m)
        return Verdict.ACCEPTED
    }

    /** Still worth handing on? TTL limits how far it travels; copies limits how many exist. */
    fun shouldForward(m: MeshMessage) = m.ttl > 0 && m.copies > 1

    /**
     * Spray-and-wait handover. The budget is not created out of thin air: half is
     * given to the peer and half is kept, so the total number of copies of a message
     * in the whole crowd can never exceed the number it started with.
     *
     * At 1 a phone stops handing it on and only delivers it directly. That is the
     * clean sentence for the pitch: a message is allowed six copies, not infinity.
     *
     * Returns the budget to stamp on the outgoing copy, or 0 if this peer already has
     * it or there is nothing left to give. Handing the same message to the same phone
     * twice must cost nothing, because in a crowd links break and re-form constantly.
     */
    fun splitCopiesFor(m: MeshMessage, peerNode: String): Int {
        if (handedTo[m.id]?.contains(peerNode) == true) return 0
        val give = m.copies / 2
        if (give < 1) return 0
        m.copies -= give
        handedTo.getOrPut(m.id) { mutableSetOf() }.add(peerNode)
        return give
    }

    /** Everything still live that this peer has not already been given. */
    fun flushOrderFor(peerNode: String): List<MeshMessage> =
        flushOrder().filter { handedTo[it.id]?.contains(peerNode) != true }

    fun markForwarded(m: MeshMessage) {
        forwardedAtLeastOnce.add(m.id)
        forwards++
    }

    /**
     * Store-and-forward. When a new peer appears, hand it everything still live,
     * most urgent first, oldest first within the same urgency.
     *
     * This is the most important behaviour in the project: a phone that met nobody
     * for ten minutes still delivers everything the second it meets someone.
     */
    fun flushOrder(): List<MeshMessage> =
        store.filter { shouldForward(it) }
            .sortedWith(compareByDescending<MeshMessage> { it.priority }.thenBy { it.createdAt })

    /** Inbox order: what a human should read first. */
    fun inboxOrder(): List<MeshMessage> =
        store.sortedWith(
            compareByDescending<MeshMessage> { it.priority }.thenByDescending { it.createdAt }
        )

    // -- storage ------------------------------------------------------------

    private fun insert(m: MeshMessage) {
        store.add(m)
        while (store.size > storeCap) {
            // Evict lowest priority first, oldest first within a priority - but never
            // throw away something that has not had a single chance to move yet.
            val victim = store
                .filter { it.id in forwardedAtLeastOnce }
                .minWithOrNull(compareBy<MeshMessage> { it.priority }.thenBy { it.createdAt })
                ?: store.minWithOrNull(compareBy<MeshMessage> { it.priority }.thenBy { it.createdAt })
                ?: return
            store.remove(victim)
            handedTo.remove(victim.id)
            evicted++
        }
    }
}
