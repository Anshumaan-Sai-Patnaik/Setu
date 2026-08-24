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
    val label: String,
    /** Plumbing, not a report. Never shown to a human, never rate limited. */
    val isPlumbing: Boolean = false
) {
    // copyBudget is how much of the crowd's radio time this kind of message is allowed
    // to spend. An emergency may spread widely. A question about the food stall may not.
    //
    // FLOOR OF 6, ALWAYS. The budget halves at every handover, and a phone holding the
    // last copy stops spreading it. So a budget of 3 dies after ONE hop: the first relay
    // receives 1 and refuses to pass it on. Found on hardware 21 Aug - INFO never
    // reached the third phone while every other type did, which looked like a radio
    // problem and was arithmetic. Reaching N hops needs roughly 2^N copies.
    MEDICAL(10, 24, false, "Medical"),
    AUTHORITY(10, 24, true, "OFFICIAL"),   // evacuation / crowd direction - signed only
    MISSING(9, 16, false, "Missing person"),
    FIRE(8, 16, false, "Fire"),
    SECURITY(8, 16, false, "Security"),
    CROWD(5, 8, false, "Crowding"),
    INFO(1, 6, false, "Info"),

    /**
     * A location arriving late for a report that was already sent.
     *
     * A message cannot simply be sent again with the position filled in: every phone has
     * its id in the seen-set and would drop the second copy as a duplicate. So the
     * location travels as its own small message pointing back at the original, and each
     * phone attaches it to the copy it is already holding.
     *
     * Priority 9 so it chases the emergency it belongs to rather than queueing behind
     * chatter.
     */
    LOCFIX(9, 16, false, "Location update", isPlumbing = true);

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
    // Both can be filled in after the fact by a LOCFIX, so neither is a val.
    var pos: Position?,             // opt-in coordinates, null if not shared
    var place: String?,             // typed description, when GPS could not be had
    val ref: String?,               // LOCFIX only: the id of the message being amended
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
// JavaScript in ten lines. The version tag comes first so a mixed-version demo fails
// loudly rather than silently misreading fields.
//
//   v4|id|origin|TYPE|lat,lon|place|ref|ttl|copies|createdAt|a,b,c|sig|text
//
// The version is bumped whenever a field changes, and older builds are refused
// outright rather than parsed loosely. Deliberate: a mixed-build demo
// should fail loudly and immediately rather than half-work in a way that looks like a
// radio fault. Every phone gets the same APK (Plan.md 11.5).
// ---------------------------------------------------------------------------
object Wire {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("|", "\\p")
    private fun unesc(s: String) = s.replace("\\p", "|").replace("\\\\", "\\")

    fun encode(m: MeshMessage): String = listOf(
        "v4",
        m.id,
        m.origin,
        m.type.name,
        m.pos?.encode() ?: "",
        esc(m.place ?: ""),
        m.ref ?: "",
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
        if (f.size != 13 || f[0] != "v4") return null
        val type = MsgType.from(f[3]) ?: return null
        return MeshMessage(
            id = f[1],
            origin = f[2],
            type = type,
            text = unesc(f[12]),
            pos = if (f[4].isEmpty()) null else Position.decode(f[4]),
            place = unesc(f[5]).ifEmpty { null },
            ref = f[6].ifEmpty { null },
            createdAt = f[9].toLongOrNull() ?: return null,
            ttl = f[7].toIntOrNull() ?: return null,
            copies = f[8].toIntOrNull() ?: return null,
            path = if (f[10].isEmpty()) mutableListOf() else f[10].split(",").toMutableList(),
            sig = f[11].ifEmpty { null }
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
     * Rehearsing the demo ten times means sending far more emergencies in an hour than
     * any real person would. Clearing the limit is a demo affordance, not a way round
     * the rule - say so if anyone asks.
     */
    fun resetRateLimit() = highPriorityOriginTimes.clear()

    /**
     * Rate limit: one joker with a phone must not be able to bury every real
     * emergency under fake ones. Low-priority chatter is not limited.
     */
    fun canOriginate(type: MsgType, now: Long): Boolean {
        if (type.priority < 8 || type.isPlumbing) return true
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
        signer: ((MeshMessage) -> String?)? = null,
        pos: Position? = null,
        place: String? = null,
        ref: String? = null
    ): MeshMessage {
        if (type.priority >= 8 && !type.isPlumbing) highPriorityOriginTimes.add(now)
        val draft = MeshMessage(
            id = myNodeId + "-" + (++counter),
            origin = myNodeId,
            type = type,
            text = text.take(180),
            pos = pos,
            place = place,
            ref = ref,
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
        reconcileLocationUpdates()
        return Verdict.ACCEPTED
    }

    /**
     * Attach any late-arriving locations to the reports they belong to.
     *
     * Run after every insert, which handles both orders of arrival: the update may reach
     * a phone before the report it amends, or long after. Whichever lands second joins them.
     *
     * **A signed message is never amended.** If a location could be bolted onto a signed
     * evacuation order after the fact, that is the redirect-the-crowd hole straight back
     * open. An order position is fixed at the moment it is signed. Citizen reports carry no
     * signature and are low-trust by design, so amending one is no weaker than the report.
     */
    private fun reconcileLocationUpdates() {
        val updates = store.filter { it.type == MsgType.LOCFIX && it.ref != null }
        if (updates.isEmpty()) return
        for (u in updates) {
            val target = store.firstOrNull { it.id == u.ref } ?: continue
            if (target.sig != null) continue                 // signed: never amended
            if (target.origin != u.origin) continue          // only its author may amend it
            if (target.pos == null && u.pos != null) target.pos = u.pos
            if (target.place == null && u.place != null) target.place = u.place
        }
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
        store.filter { !it.type.isPlumbing }.sortedWith(
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
