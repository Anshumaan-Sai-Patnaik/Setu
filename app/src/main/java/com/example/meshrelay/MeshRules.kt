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
    /** What the person choosing sees. Their words, not a category name. */
    val label: String,
    /** Short form for the inbox and the map, where there is no room for a sentence. */
    val tag: String,
    /** Plumbing, not a report. Never shown to a human, never rate limited. */
    val isPlumbing: Boolean = false
) {
    // Wording matters more here than anywhere else in the app. Someone frightened is
    // scanning, not reading, and they are looking for their own situation described back
    // to them - not for the name of a category. "Medical" is a filing label and it is also
    // ambiguous: does it mean I need a doctor, or I am one?
    //
    // Ordered by how likely someone is to need it, so the common case is at the top.
    //
    // copyBudget is how much of the crowd's radio time this kind of message may spend.
    // FLOOR OF 6, ALWAYS. The budget halves at every handover and a phone holding the last
    // copy stops spreading it, so a budget of 3 dies after ONE hop. Reaching N hops needs
    // roughly 2^N copies.

    MEDICAL(10, 24, false, "Someone is hurt or ill", "MEDICAL"),

    MISSING(9, 16, false, "Someone is missing", "MISSING"),

    // "Fire" alone makes people hesitate when what they can see is smoke.
    FIRE(8, 16, false, "Fire or smoke", "FIRE"),

    // "Security" is vague and sounds like a department. This is what it is for.
    SECURITY(8, 16, false, "Violence or a threat", "THREAT"),

    // "Dangerous" is the whole point - it separates a crush from a place merely being
    // busy, and crowd crush is the thing that actually kills people at these events.
    CROWD(5, 8, false, "Dangerous crowding", "CROWDING"),

    // Naming it as not-an-emergency discourages using it for one, and discourages using
    // an emergency type for this.
    INFO(1, 6, false, "Question - not an emergency", "QUESTION"),

    // Last, and marked, because an ordinary visitor has no business sending one. They can
    // still try: it goes out unsigned and every phone refuses it, which is the demo.
    AUTHORITY(10, 24, true, "Official instruction (staff)", "OFFICIAL"),

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
    LOCFIX(9, 16, false, "Location update", "LOCATION", isPlumbing = true),

    /**
     * "It arrived." The answer to question 6 of the seven: *how does anyone ever know
     * it arrived?*
     *
     * Sent by a responder when a report reaches a phone that can actually act on it,
     * and pointed - by `ref` - back at the report it confirms. It floods home the same
     * way everything else travels, because there is no return route to follow: the only
     * phone that cares is the one that sent the original, and it recognises its own id.
     *
     * `text` carries one number: how many hops the original took to get there. The
     * sender cannot work that out on its own, and it is the number that makes the
     * confirmation mean something.
     *
     * Priority 9 so a confirmation is not stuck behind chatter. Plumbing, so it is never
     * shown as a report and never counted against the rate limit.
     */
    RECEIPT(9, 16, false, "Delivery receipt", "RECEIPT", isPlumbing = true);

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
//   v5|id|origin|TYPE|lat,lon|place|ref|ttl|copies|createdAt|a,b,c|sig|text
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
        "v5",
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
        if (f.size != 13 || f[0] != "v5") return null
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
enum class Verdict {
    DUPLICATE,
    UNSIGNED_AUTHORITY,
    ACCEPTED,

    /**
     * A receipt for something this phone sent, which has therefore reached the one
     * phone in the crowd that was waiting for it. It is recorded and it stops here -
     * not stored, not forwarded. Carrying it further would spend the crowd's radio time
     * telling people something none of them asked about.
     */
    RECEIPT_FOR_ME
}

/**
 * Which reports are worth confirming.
 *
 * Only urgent ones. A receipt is a whole extra message travelling back through the
 * crowd, and confirming "where is the food stall" would spend real radio time on
 * nothing - the same reasoning that already limits location follow-ups to priority 8+.
 *
 * AUTHORITY is excluded for a different reason: an order is a broadcast to everybody,
 * so there is no single arrival to confirm, and the phone that would be confirming it
 * is the phone that sent it.
 */
fun expectsReceipt(type: MsgType): Boolean =
    !type.isPlumbing && type != MsgType.AUTHORITY && type.priority >= 8

/**
 * What this phone knows about one report it sent itself.
 *
 * The clock matters here. Both times are read from THIS phone's clock - when send was
 * pressed, and when the confirmation came back - so nothing depends on two phones
 * agreeing what time it is. That makes the number a round trip rather than a one-way
 * delivery time, and it is the more useful number anyway: how long until the person who
 * reported it knew that somebody had it.
 */
data class Delivery(
    val messageId: String,
    val sentAt: Long,
    var confirmedAt: Long? = null,
    /** Hops the original took to reach the responder, as counted by the responder. */
    var hops: Int = 0,
    /** Node id of the responder that confirmed it. */
    var by: String? = null
) {
    val isDelivered: Boolean get() = confirmedAt != null

    val seconds: Long get() = confirmedAt?.let { (it - sentAt + 500) / 1000 } ?: 0
}

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

    /**
     * Whether this phone is somewhere a report can actually be acted on, and therefore
     * whether it confirms the reports it receives.
     *
     * A report has no address. It is not sent *to* anyone - it is spread until it finds
     * someone who can help. So "delivered" cannot mean "reached the destination node",
     * because there is no destination node. It means **it reached a responder**.
     *
     * On the demo phones that is the command phone, the one holding the organiser key,
     * which is also the honest picture of a real deployment: reports are heading for the
     * control room. A phone that cannot act on a report has no business telling anyone it
     * arrived - if every phone confirmed, "delivered" would only mean "somebody nearby
     * heard it", which is not what the person who sent it is asking.
     */
    var amResponder: Boolean = false

    /**
     * Reports this phone sent itself, and what came back. Insertion-ordered so the
     * oldest is first if this is ever shown as a list.
     */
    private val outbox = LinkedHashMap<String, Delivery>()

    var duplicatesBlocked = 0; private set
    var evicted = 0; private set
    var forwards = 0; private set

    /** Reports this phone sent that a responder has confirmed receiving. */
    var confirmed = 0; private set

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
        // Start the clock on anything worth confirming. Until a receipt comes back this
        // is what the sender's screen has to show as still in flight.
        //
        // Not on a responder's own reports. It is already sitting on the phone that a
        // report is trying to reach, so there is nothing to wait for - and a row reading
        // "in flight" forever on the command phone would look like a fault rather than a
        // fact.
        if (expectsReceipt(type) && !amResponder) outbox[m.id] = Delivery(m.id, now)
        return m
    }

    // -- receiving ----------------------------------------------------------

    /**
     * Rule 1 of the whole system: seen it before, drop it silently.
     * Without this the crowd drowns in duplicates within seconds.
     */
    fun onReceive(
        m: MeshMessage,
        fromNode: String?,
        // Only used to time a confirmation against this phone's own clock. Defaulted so
        // the rules can still be driven with nothing but a message, which is how every
        // test and the simulator use them.
        now: Long = System.currentTimeMillis()
    ): Verdict {
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

        // A confirmation for something this phone sent. It has arrived at the only phone
        // that was waiting for it, so it is recorded and goes no further. Receipts for
        // other people's reports fall through and are relayed like anything else.
        if (m.type == MsgType.RECEIPT && m.ref != null) {
            val mine = outbox[m.ref]
            if (mine != null) {
                // First confirmation wins. A report can reach two responders, and the
                // second one arriving does not make it any more delivered.
                if (mine.confirmedAt == null) {
                    mine.confirmedAt = now
                    mine.hops = m.text.toIntOrNull() ?: 0
                    mine.by = m.origin
                    confirmed++
                }
                return Verdict.RECEIPT_FOR_ME
            }
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

    /**
     * The confirmation to send back, or null if this phone has no business sending one.
     *
     * Called after a message has been accepted, so the hop count is read from a path
     * that already includes this phone. A path of A > B > C is two hops.
     *
     * Deliberately NOT done inside onReceive: originating a message means putting it on
     * the radio, and that decision stays visible at the call site next to every other
     * send, rather than happening as a side effect of receiving something.
     */
    fun receiptFor(m: MeshMessage, now: Long): MeshMessage? {
        if (!amResponder) return null
        if (!expectsReceipt(m.type)) return null
        if (m.origin == myNodeId) return null          // never confirm to yourself
        val hops = (m.path.size - 1).coerceAtLeast(0)
        return originate(MsgType.RECEIPT, hops.toString(), now, ref = m.id)
    }

    /** What came back about one report this phone sent, or null if it was not one worth confirming. */
    fun deliveryOf(messageId: String): Delivery? = outbox[messageId]

    /** How many reports this phone sent are still waiting to be confirmed. */
    fun awaitingConfirmation(): Int = outbox.values.count { !it.isDelivered }

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
