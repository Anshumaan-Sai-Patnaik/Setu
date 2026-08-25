package com.example.meshrelay

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Must be byte-identical on every device or they will never find each other.
    private val serviceId = "com.example.meshrelay.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER

    private lateinit var connections: ConnectionsClient
    private lateinit var tvName: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var rvInbox: RecyclerView
    private lateinit var tvPaneTitle: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chkLoc: CheckBox
    private lateinit var tvLocState: TextView
    private lateinit var mapView: ReportMapView
    private lateinit var graphView: MeshGraphView
    private lateinit var btnTopology: TextView

    // The banner. One object that both states the mesh's condition and starts it.
    private lateinit var cardMesh: View
    private lateinit var vMeshDot: MeshPulseView
    private lateinit var tvMeshState: TextView
    private lateinit var tvMeshHint: TextView

    /**
     * How far the mesh has got in coming up.
     *
     * Every one of these is a real event, not a step in a scripted animation: the radio
     * genuinely takes a moment to start advertising and discovering, a phone is genuinely
     * found before it is linked, and the two sides genuinely have to agree which of them
     * dials. Showing those moments is the difference between an honest startup sequence
     * and a progress bar that would say the same thing in an empty room.
     */
    private enum class MeshStage { OFF, RADIO, LOOKING, FOUND }

    private var stage = MeshStage.OFF
    private var advertisingUp = false
    private var discoveryUp = false

    /** The banner text last painted, so it only animates when it genuinely changes. */
    private var lastBannerState = ""

    // Counters, promoted from a monospace footnote to something readable across a room.
    private lateinit var statsBlock: View
    private lateinit var tvPeers: TextView
    private lateinit var tvStored: TextView
    private lateinit var tvStoredCaption: TextView
    private lateinit var tvFlight: TextView

    private lateinit var chipRow: LinearLayout
    private lateinit var btnSend: TextView
    private val tabs = mutableListOf<TextView>()

    private val inbox = MessageAdapter()

    /**
     * What this phone can show. Named tabs, not a cycle.
     *
     * GRAPH has no tab chip of its own: it is reached from the LINKS button, which sits
     * outside the tab bar and lights up while it is open. Four chips would not fit
     * legibly on the Nokia 3.2, and LINKS is a demo control that has to stay findable
     * under pressure rather than becoming one of a row of equals.
     */
    private enum class Pane(val title: String) {
        INBOX("Incoming - most urgent first"),
        MAP("Reports by location"),
        LOG("Debug log"),
        GRAPH("Links - who can reach whom")
    }

    private var pane = Pane.INBOX

    /** The pane to come back to when the graph is closed again. */
    private var paneBeforeGraph = Pane.INBOX

    /** What a person may actually send. Excludes app plumbing such as location updates. */
    private val reportableTypes = MsgType.entries.filter { !it.isPlumbing }

    /**
     * Nothing is chosen until it is chosen. A pre-selected type means a panicking
     * person sends "someone is hurt" when they meant "someone is missing", because
     * the first option was already sitting there waiting.
     */
    private var selectedType: MsgType? = null

    // A permanent identity for this phone, created once on first launch and kept
    // afterwards. Used both to break connection ties and to tag messages.
    private val nodeId: String by lazy {
        val prefs = getSharedPreferences("meshrelay", MODE_PRIVATE)
        var id = prefs.getString("nodeId", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            prefs.edit().putString("nodeId", id).apply()
        }
        id
    }

    private val myName: String by lazy { Build.MODEL + "-" + nodeId }

    // The node ID is the part after the last dash, so this works even if a phone
    // model name contains dashes of its own.
    private fun nodeIdOf(endpointName: String) = endpointName.substringAfterLast('-')

    private val connected = mutableSetOf<String>()
    private val dialling = mutableSetOf<String>()
    private val peerNames = mutableMapOf<String, String>()   // endpointId -> endpointName

    // Nearby hands out a fresh endpoint ID every time it rediscovers a phone, so the
    // same physical phone can appear under two IDs at once and get dialled twice.
    // Identity is the node ID, not the endpoint ID - track links by that.
    private val connectedNodes = mutableSetOf<String>()
    private val diallingNodes = mutableSetOf<String>()

    private fun nodeOf(endpointId: String) = nodeIdOf(peerNames[endpointId] ?: "")
    private val handler = Handler(Looper.getMainLooper())
    private var meshRunning = false

    /**
     * Phones this one has heard OF but never linked to, and the links between them.
     *
     * Both come free from something already on every message: `path`, the list of phones
     * it travelled through. A path of A > B > C is direct evidence that A and B were
     * linked and that B and C were, and it is evidence this phone can use even though it
     * only ever spoke to one of them.
     *
     * This is knowledge, not guesswork, and the graph draws it differently from a live
     * link for exactly that reason - a dashed line between two other phones says "a
     * message came this way", not "I can see them".
     */
    private val heardOf = mutableSetOf<String>()

    /** Unordered pairs as "a|b", lower id first, so the same link is never stored twice. */
    private val observedLinks = mutableSetOf<String>()

    private fun linkKey(a: String, b: String) =
        if (a <= b) "$a|$b" else "$b|$a"

    /**
     * Learn the shape of the network from a message that has just arrived.
     *
     * Deliberately additive: a link is remembered once it has been seen, and is not
     * forgotten when the phones move apart. That is the honest reading of the evidence -
     * "a message crossed here" stays true afterwards - and it is also what keeps the
     * far phone on screen during the store-and-forward beat, when the whole point is
     * that this phone still knows about somewhere it cannot currently reach.
     */
    private fun learnTopology(path: List<String>) {
        for (n in path) if (n != nodeId) heardOf += n
        for (i in 0 until path.size - 1) observedLinks += linkKey(path[i], path[i + 1])
    }

    /**
     * Topology lock (Plan.md 11). All three phones sit on one table and can all hear
     * each other, so there would be no hop to demonstrate. Blocking a node ID here cuts
     * that link in software. This is stated out loud on stage, never hidden.
     */
    private val blocked = mutableSetOf<String>()

    // The decision layer. Everything interesting lives in MeshRules.kt.
    private val rules: MeshRules by lazy {
        MeshRules(myNodeId = nodeId, verifySignature = { Authority.verify(it) })
    }

    /**
     * Set only on the designated command phone, and only by pasting the key in by hand.
     * It is deliberately not saved to disk and not in the APK: a judge can decompile
     * this app and still not be able to order a crowd to move.
     */
    private var organiserKey: PrivateKey? = null

    private val askPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isEmpty()) {
            log("All permissions granted")
            startMesh()
        } else {
            log("DENIED: " + denied.joinToString(", ") { it.substringAfterLast('.') })
            log("Grant them in Settings > Apps > MeshRelay > Permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvName = findViewById(R.id.tvName)
        tvStats = findViewById(R.id.tvStats)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        etMessage = findViewById(R.id.etMessage)
        rvInbox = findViewById(R.id.rvInbox)
        tvPaneTitle = findViewById(R.id.tvPaneTitle)
        tvEmpty = findViewById(R.id.tvEmpty)
        chkLoc = findViewById(R.id.chkLoc)
        tvLocState = findViewById(R.id.tvLocState)
        mapView = findViewById(R.id.mapView)
        graphView = findViewById(R.id.graphView)
        btnTopology = findViewById(R.id.btnTopology)

        cardMesh = findViewById(R.id.btnStart)
        vMeshDot = findViewById(R.id.vMeshDot)
        tvMeshState = findViewById(R.id.tvMeshState)
        tvMeshHint = findViewById(R.id.tvMeshHint)

        statsBlock = findViewById(R.id.statsBlock)
        tvPeers = findViewById(R.id.tvPeers)
        tvStored = findViewById(R.id.tvStored)
        tvStoredCaption = findViewById(R.id.tvStoredCaption)
        tvFlight = findViewById(R.id.tvFlight)

        chipRow = findViewById(R.id.chipRow)
        btnSend = findViewById(R.id.btnSend)

        // The teal bar beside the wordmark. Drawn rather than shipped as an asset:
        // the demo runs with no network and every byte in the APK is one we chose.
        findViewById<View>(R.id.vBrandMark).background =
            Palette.pill(Palette.TEAL, 2f * resources.displayMetrics.density)

        // Sharing a position is opt-in and starts OFF. This network copies messages onto
        // strangers phones and holds them for hours; that is the wrong place for anyone
        // exact whereabouts unless they chose it knowingly.
        chkLoc.setOnCheckedChangeListener { _, on ->
            if (on) startLocation() else stopLocation()
            paintLocationState()
        }
        chkLoc.setOnLongClickListener { showSimulatedPositionDialog(); true }

        rvInbox.layoutManager = LinearLayoutManager(this)
        rvInbox.adapter = inbox

        connections = Nearby.getConnectionsClient(this)

        blocked += getSharedPreferences("meshrelay", MODE_PRIVATE)
            .getStringSet("blocked", emptySet()) ?: emptySet()

        buildTypeChips()
        buildTabs()
        showOrdinaryPhone()

        cardMesh.setOnClickListener {
            if (meshRunning) {
                log("Mesh is already running")
            } else {
                log("Requesting permissions...")
                askPermissions.launch(requiredPermissions())
            }
        }

        // LINKS opens the graph, and opens it again into whatever was on screen before.
        // The cut-links control moved to a long-press and onto the node sheet: cutting a
        // link is a stage trick used three times in a demo, whereas looking at the shape
        // of the network is the thing worth putting one tap away.
        btnTopology.setOnClickListener {
            showPane(if (pane == Pane.GRAPH) paneBeforeGraph else Pane.GRAPH)
        }
        btnTopology.setOnLongClickListener { showTopologyDialog(); true }

        graphView.onNodeTap = { node -> if (node != null) showNodeSheet(node) }

        // Long-press the title to turn this phone into the command phone. Hidden
        // because it is a staff action, not something a visitor should ever find.
        tvName.setOnLongClickListener { showKeyDialog(); true }

        // Eviction is invisible with room for 200 messages. Long-press the counters to
        // shrink the store to 10 so "the junk is dropped and the emergency survives"
        // can actually be watched happening (Plan.md 17.2). The whole block is the
        // target, not just the small print - it has to be findable under pressure.
        val shrinkStore = View.OnLongClickListener {
            rules.storeCap = if (rules.storeCap == 200) 10 else 200
            // Rehearsal sends more emergencies per hour than any real person would.
            rules.resetRateLimit()
            log("Store cap now " + rules.storeCap + (if (rules.storeCap == 10) " (DEMO)" else ""))
            log("Rate limit cleared")
            updateStats()
            true
        }
        statsBlock.setOnLongClickListener(shrinkStore)
        tvStats.setOnLongClickListener(shrinkStore)

        btnSend.setOnClickListener { sendWhatIsTyped() }
        etMessage.setOnEditorActionListener { _, _, _ -> sendWhatIsTyped(); true }

        log("Ready. Tap the banner to start the mesh.")
        updateStats()
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return perms.toTypedArray()
    }

    private fun startMesh() {
        meshRunning = true
        stage = MeshStage.RADIO
        advertisingUp = false
        discoveryUp = false
        updateStats()

        connections.startAdvertising(
            myName,
            serviceId,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("ADVERTISING started"); radioUp(advertising = true) }
            .addOnFailureListener { log("ADVERTISING failed: " + it.message) }

        connections.startDiscovery(
            serviceId,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("DISCOVERY started"); radioUp(discovery = true) }
            .addOnFailureListener { log("DISCOVERY failed: " + it.message) }
    }

    /**
     * Both halves have to be up before this phone is really in the network: advertising
     * so others can find it, discovery so it can find them. One without the other is a
     * phone that can only be called or can only call, and saying "looking for phones"
     * then would be a small lie.
     */
    private fun radioUp(advertising: Boolean = false, discovery: Boolean = false) {
        if (advertising) advertisingUp = true
        if (discovery) discoveryUp = true
        if (advertisingUp && discoveryUp && stage == MeshStage.RADIO) {
            stage = MeshStage.LOOKING
        }
        updateStats()
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val theirName = info.endpointName
            val theirNode = nodeIdOf(theirName)

            // Never link to yourself. Nearby can surface this phone's own advertisement
            // if an older client is still running (see the manifest note on rotation),
            // and a self-link inflates the peer count and wastes copy budget on a phone
            // that already has the message.
            if (theirNode == nodeId) return

            peerNames[endpointId] = theirName
            log("FOUND " + theirName)

            if (theirNode in blocked) {
                log("IGNORING " + theirNode + " (topology lock)")
                return
            }
            // Already linked to this phone, or already calling it - possibly under an
            // older endpoint ID. Dialling again is what produced the retry storm.
            if (theirNode in connectedNodes || theirNode in diallingNodes) return
            if (endpointId in connected || endpointId in dialling) return

            // Only ONE side may dial, or the two requests collide (error 8012).
            // Compare the random node IDs, NOT the full names: the name begins with
            // the model, so comparing names would make one model always the dialler.
            // Comparing random IDs spreads the role evenly across devices.
            log("Linking with " + theirName + "...")

            // A real phone, really found, about to be really linked. This is the beat
            // between "looking" and "connected", and it is worth a second on screen.
            if (connected.isEmpty()) {
                stage = MeshStage.FOUND
                updateStats()
            }

            if (nodeId < theirNode) {
                dialling += endpointId
                diallingNodes += theirNode
                dial(endpointId, theirName, 1)
            } else {
                // The other side dials. Safety net: if its call never arrives,
                // dial anyway rather than both sides waiting forever.
                handler.postDelayed({
                    if (theirNode !in connectedNodes && theirNode !in diallingNodes &&
                        theirNode !in blocked
                    ) {
                        log("Still linking with " + theirName + "...")
                        dialling += endpointId
                        diallingNodes += theirNode
                        dial(endpointId, theirName, 1)
                    }
                }, 12000L)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            dialling -= endpointId
            diallingNodes -= nodeOf(endpointId)
            log("LOST " + (peerNames[endpointId] ?: endpointId))
        }
    }

    private fun dial(endpointId: String, theirName: String, attempt: Int) {
        val theirNode = nodeIdOf(theirName)

        // The call may have already been answered from the other direction while this
        // attempt was in flight. Retrying then, and eventually announcing "could not
        // link" about a phone we are talking to, is alarming and wrong.
        if (theirNode in connectedNodes) {
            dialling -= endpointId
            diallingNodes -= theirNode
            return
        }

        connections.requestConnection(myName, endpointId, connectionLifecycle)
            .addOnFailureListener {
                if (theirNode in connectedNodes) {
                    dialling -= endpointId
                    diallingNodes -= theirNode
                } else if (attempt < 5) {
                    // Randomised, growing delay. Genuine radio failures (someone walked
                    // out of range, Bluetooth stuttered) are common; retrying on a fixed
                    // timer would make several phones retry in lockstep.
                    val wait = (1000L * attempt) + (0..1500).random()
                    log("Retrying link with " + theirName + " (" + (attempt + 1) + "/5)")
                    handler.postDelayed({ dial(endpointId, theirName, attempt + 1) }, wait)
                } else {
                    dialling -= endpointId
                    diallingNodes -= theirNode
                    log("Could not link with " + theirName)
                }
            }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (nodeIdOf(info.endpointName) == nodeId) {
                connections.rejectConnection(endpointId)
                return
            }
            peerNames[endpointId] = info.endpointName
            if (nodeIdOf(info.endpointName) in blocked) {
                log("REFUSING " + info.endpointName + " (topology lock)")
                connections.rejectConnection(endpointId)
                return
            }
            log("Handshake with " + info.endpointName + " - accepting")
            connections.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val theirNode = nodeOf(endpointId)
            dialling -= endpointId
            diallingNodes -= theirNode
            if (result.status.isSuccess) {
                connected += endpointId
                connectedNodes += theirNode
                log("*** CONNECTED *** (" + connected.size + " peer(s))")
                flushTo(endpointId)
            } else {
                log("Connect failed: code " + result.status.statusCode)
            }
            updateStats()
        }

        override fun onDisconnected(endpointId: String) {
            val theirNode = nodeOf(endpointId)
            connected -= endpointId
            // Only forget the phone if no other endpoint ID for it is still live.
            if (connected.none { nodeOf(it) == theirNode }) connectedNodes -= theirNode
            log("DISCONNECTED (" + connected.size + " peer(s))")
            // Back to searching, honestly. When a judge walks off with a phone the
            // banner has to admit it - that admission is what proves the rest is real.
            if (connected.isEmpty() && meshRunning) stage = MeshStage.LOOKING
            updateStats()
        }
    }

    // -----------------------------------------------------------------------
    // Message layer
    // -----------------------------------------------------------------------

    private fun originateAndSend(type: MsgType, text: String) {
        val now = System.currentTimeMillis()
        if (!rules.canOriginate(type, now)) {
            log("RATE LIMITED: too many urgent messages from this phone in the last hour")
            return
        }
        // Only the command phone holds a key, so only the command phone can produce an
        // order. Every other phone can still type one - it just goes out unsigned and
        // gets refused by everyone who receives it.
        val key = organiserKey
        val signer: ((MeshMessage) -> String?)? =
            if (type.needsSignature && key != null) { draft -> Authority.sign(draft, key) }
            else null

        val m = rules.originate(type, text, now, signer, positionToAttach(), placeToAttach())
        if (type.needsSignature) {
            log(if (m.sig != null) "    signed as organiser" else "    NO KEY - this will be refused by every phone")
        }
        log("<<< SENT " + type.name + " p" + m.priority + " copies=" + m.copies + ": " + m.text)
        if (expectsReceipt(type)) log("    in flight - waiting for a responder to confirm it")
        broadcast(m)
        rememberForLateLocation(m)
        updateStats()
    }

    /**
     * Offer the message to every connected peer. The rules decide who actually gets it:
     * a peer that already holds it is skipped and costs nothing (Plan.md 8.2).
     */
    private fun broadcast(m: MeshMessage) {
        if (connected.isEmpty()) {
            log("    held - no peers in range (will flush on next contact)")
            return
        }
        var handed = 0
        for (target in connected.toList()) if (handOver(m, target)) handed++
        if (handed == 0) {
            log("    nothing to hand on - peers already have it, or copies are spent")
        }
    }

    /**
     * Hand one copy to one peer, spending half this phone's copy budget on it.
     * Returns false if that peer already has it or the budget is gone.
     */
    private fun handOver(m: MeshMessage, endpointId: String): Boolean {
        val peerNode = nodeIdOf(peerNames[endpointId] ?: return false)
        val give = rules.splitCopiesFor(m, peerNode)
        if (give < 1) return false
        val outgoing = m.copy(path = m.path.toMutableList()).also { it.copies = give }
        val payload = Payload.fromBytes(Wire.encode(outgoing).toByteArray(StandardCharsets.UTF_8))
        connections.sendPayload(listOf(endpointId), payload)
        rules.markForwarded(m)
        // A dot leaves this phone and crosses to that one. Every dot on the graph is a
        // payload that was actually put on the radio - there is no traffic animation
        // that runs when the mesh is quiet, which is the difference between this and
        // something that would look identical in an empty room.
        runOnUiThread { graphView.spark(nodeId, peerNode, Palette.forPriority(m.priority)) }
        return true
    }

    /**
     * Store-and-forward. A new peer just appeared: hand it everything still live,
     * most urgent first. This is what makes the network work with no end-to-end path.
     */
    private fun flushTo(endpointId: String) {
        val peerNode = nodeIdOf(peerNames[endpointId] ?: return)
        val pending = rules.flushOrderFor(peerNode)
        if (pending.isEmpty()) return
        log("FLUSHING " + pending.size + " stored message(s) to new peer")
        for (m in pending) handOver(m, endpointId)
        updateStats()
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val raw = String(bytes, StandardCharsets.UTF_8)
            val m = Wire.decode(raw)
            if (m == null) {
                log("BAD PACKET dropped")
                return
            }
            val fromNode = nodeIdOf(peerNames[endpointId] ?: "")

            // Learn the shape of the network before deciding what to do with the message,
            // so a duplicate still teaches something. A copy arriving by a second route
            // is the clearest evidence there is that the second route exists.
            learnTopology(m.path)
            runOnUiThread {
                graphView.spark(fromNode, nodeId, Palette.forPriority(m.priority))
            }

            when (rules.onReceive(m, fromNode)) {
                Verdict.DUPLICATE ->
                    log("dup blocked: " + m.id)

                Verdict.UNSIGNED_AUTHORITY ->
                    log("REFUSED unsigned OFFICIAL order from " + m.origin + " - not displayed")

                // The answer to "how does anyone ever know it arrived?", arriving.
                Verdict.RECEIPT_FOR_ME -> {
                    val d = rules.deliveryOf(m.ref ?: "")
                    log(
                        "*** DELIVERED - a responder has the report you sent ***" +
                            "\n    " + (d?.hops ?: 0) + " hop(s), confirmed " +
                            (d?.seconds ?: 0) + "s after you sent it, by " +
                            (d?.by ?: m.origin).take(4) +
                            "\n    the receipt stops here - nobody else needs it"
                    )
                }

                Verdict.ACCEPTED -> {
                    if (m.type.needsSignature) {
                        log("*** OFFICIAL ORDER - signature verified ***")
                    }
                    log(
                        ">>> " + m.type.name + " p" + m.priority + ": " + m.text +
                            "\n    ttl=" + m.ttl + " copies=" + m.copies +
                            " path=" + m.path.joinToString(">")
                    )
                    if (rules.shouldForward(m)) {
                        broadcast(m)
                    } else if (m.ttl <= 0) {
                        log("    stops here - travelled its full " + rules.hopLimit + " hops")
                    } else {
                        log("    stops here - last copy, kept but not spread further")
                    }

                    // This phone can act on the report, so it says so. The receipt floods
                    // back the way the report came - there is no return route to follow -
                    // and only the phone that sent the original keeps it.
                    rules.receiptFor(m, System.currentTimeMillis())?.let { receipt ->
                        log("    CONFIRMING - receipt on its way back to " + m.origin.take(4))
                        broadcast(receipt)
                    }
                }
            }
            updateStats()
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // not needed for short text messages
        }
    }

    // -----------------------------------------------------------------------
    // Topology lock + counters
    // -----------------------------------------------------------------------

    /**
     * Turns this phone into the command phone by pasting in the organiser's private key.
     * Nothing is written to disk - close the app and it is an ordinary phone again.
     *
     * Honest limit, worth saying before a judge asks (Plan.md 17.4): a real deployment
     * issues a key per staff member at accreditation and needs a way to cancel a stolen
     * one. That is not built.
     */
    private fun showKeyDialog() {
        val holding = organiserKey != null
        val view = dialogBody(
            body = if (holding)
                "This phone is holding the organiser key. It can issue official " +
                    "instructions, and it is the phone that confirms urgent reports."
            else
                "Paste the organiser's private key to make this the command phone. " +
                    "It can then issue official instructions that every other phone " +
                    "can check, and it becomes the responder that confirms urgent reports.",
            hint = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcE...",
            prefill = "",
            note = "The key is never written to disk and is not inside the app. " +
                "Close the app and this is an ordinary phone again.",
            noteColour = Palette.TEAL
        )
        val input = view.findViewById<EditText>(R.id.etDialogInput)
        dialog()
            .setTitle(if (holding) "Command phone" else "Become the command phone")
            .setView(view)
            .setPositiveButton("Set") { _, _ ->
                // Strip every whitespace character, not just the ends. A key pasted from
                // a chat app or a text file arrives wrapped across lines, and Base64
                // decoding refuses that - which would look like "the key is wrong" on
                // stage when the key is fine.
                val typed = input.text.toString().replace(Regex("\\s"), "")
                val parsed = Authority.parsePrivateKey(typed)
                if (parsed == null) {
                    // Said on screen, not only in the log: the log is behind a tab, and
                    // a silently ignored key is the worst possible way to lose the trust
                    // beat in front of a panel.
                    Toast.makeText(
                        this, "That is not a usable key - still an ordinary phone",
                        Toast.LENGTH_LONG
                    ).show()
                    log("That is not a usable key - still an ordinary phone")
                } else {
                    organiserKey = parsed
                    rules.amResponder = true
                    showCommandPhone()
                    log("This phone can now issue OFFICIAL orders")
                    log("It is also the responder: urgent reports reaching it get confirmed")
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                organiserKey = null
                rules.amResponder = false
                showOrdinaryPhone()
                log("Organiser key cleared - ordinary phone again")
                log("It no longer confirms reports")
            }
            .show()
    }

    private fun showTopologyDialog() {
        val known = peerNames.values.distinct().sorted()
        if (known.isEmpty()) {
            log("No peers discovered yet - start the mesh first")
            return
        }
        val checked = known.map { nodeIdOf(it) in blocked }.toBooleanArray()
        dialog()
            .setTitle("Cut these links")
            // Ticked means "pretend this phone is out of range". Said plainly, because
            // the whole point of the topology lock is that we are open about it on stage.
            .setMessage(
                "Three phones on one table all hear each other, so there is no hop to " +
                    "show. Tick a phone to pretend it is out of range."
            )
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                val n = nodeIdOf(known[which])
                if (isChecked) blocked += n else blocked -= n
            }
            .setPositiveButton("Apply") { _, _ -> applyTopologyLock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Make `blocked` true on the radio.
     *
     * Extracted so the node sheet and the tick-list dialog cannot drift apart: this is
     * the one demo control that must behave identically however it was reached.
     */
    private fun applyTopologyLock() {
        getSharedPreferences("meshrelay", MODE_PRIVATE).edit()
            .putStringSet("blocked", blocked.toSet()).apply()
        // Drop any link that is now blocked, so the change takes effect at once.
        for (id in connected.toList()) {
            val n = nodeOf(id)
            if (n in blocked) {
                connections.disconnectFromEndpoint(id)
                connected -= id
                connectedNodes -= n
            }
        }
        // Un-blocking a phone has to actively call it back. Nearby only reports a
        // peer as FOUND when it first appears, so a link cut while discovered
        // would otherwise stay dead until the peer wandered off and returned.
        for ((id, name) in peerNames) {
            val n = nodeIdOf(name)
            if (n !in blocked && n !in connectedNodes && n !in diallingNodes) {
                log("Restoring link with " + name + "...")
                dialling += id
                diallingNodes += n
                dial(id, name, 1)
            }
        }
        log("Topology lock: ignoring " + blocked.ifEmpty { setOf("nobody") })
        updateStats()
    }

    /**
     * One node on the graph, opened by tapping it.
     *
     * What a person actually wants to know about a dot: which phone it is, whether this
     * one can reach it, and what it has said. The last is the useful part during a demo -
     * "everything that came from that phone" is a question the inbox cannot answer,
     * because the inbox is deliberately sorted by urgency rather than by who sent it.
     *
     * Cutting the link lives here too. On the graph you tap the phone you mean, which is
     * a great deal harder to get wrong in front of a panel than finding the right line in
     * a tick list of similar-looking device names.
     */
    private fun showNodeSheet(node: String) {
        val linked = node in connectedNodes
        val model = deviceModelOf(node)
        val cut = node in blocked

        val fromThem = rules.inboxOrder().filter { it.origin == node }
        val relayedThrough = rules.inboxOrder().count { it.origin != node && node in it.path }

        val state = when {
            cut -> "Link cut by hand - this phone is pretending it is out of range"
            linked -> "Linked now - this phone can hand it a message directly"
            else -> "Known about, not reachable. Seen in the path of a message that " +
                "arrived, so it exists and something got here from it"
        }

        val body = StringBuilder()
        body.append(if (model.isNotEmpty()) model else "Unknown device")
        body.append("\nnode ").append(node)
        body.append("\n\n").append(state)
        body.append("\n\nOriginated ").append(fromThem.size)
            .append(if (fromThem.size == 1) " report" else " reports")
        if (relayedThrough > 0) {
            body.append(", and relayed ").append(relayedThrough)
                .append(if (relayedThrough == 1) " other" else " others")
        }

        if (fromThem.isEmpty()) {
            body.append("\n\nNothing from this phone yet.")
        } else {
            body.append("\n")
            // Most urgent first, same order as the inbox, and capped: a dialog is not a
            // second inbox and a scrolling wall of text on stage helps nobody.
            for (m in fromThem.take(6)) {
                body.append("\n• ").append(m.type.tag).append(" — ").append(m.text)
            }
            if (fromThem.size > 6) {
                body.append("\n• ...and ").append(fromThem.size - 6).append(" more")
            }
        }

        val d = dialog()
            .setTitle(if (model.isNotEmpty()) model else node.take(6))
            .setMessage(body.toString())
            .setPositiveButton("Close", null)

        // Only phones this one has actually discovered can be cut. A phone known only
        // from a message path has no link here to cut in the first place.
        if (peerNames.values.any { nodeIdOf(it) == node }) {
            d.setNeutralButton(if (cut) "Restore link" else "Pretend out of range") { _, _ ->
                if (cut) blocked -= node else blocked += node
                applyTopologyLock()
            }
        }
        d.show()
    }

    // -----------------------------------------------------------------------
    // Location - opt-in, never blocking
    // -----------------------------------------------------------------------

    private var lastFix: Location? = null

    /**
     * A position typed in by hand for the demo. Three phones on one table are all at the
     * same coordinates, and indoors they will not get a fix at all - so the map would show
     * one dot and prove nothing.
     *
     * This is the same kind of stage constraint as the topology lock, and it gets the same
     * treatment: say it out loud. "These three phones are on one table, so their positions
     * are set by hand. The field carries real GPS in the field."
     */
    private var simulatedPosition: Position? = null

    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var announcedFix = false

    private val fixListener = LocationListener { loc ->
        lastFix = loc
        // Say it out loud the first time, so "does GPS work with no internet?" is a thing
        // you can watch happen rather than take on trust.
        if (!announcedFix) {
            announcedFix = true
            log(
                "GPS FIX with no internet: " + Position(loc.latitude, loc.longitude).encode() +
                    "  (+/- " + loc.accuracy.toInt() + " m)"
            )
        }
        sendLateLocations()
        refreshInbox()
    }

    private fun startLocation() {
        if (simulatedPosition != null) return          // already have a demo position
        announcedFix = false
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // A phone set to locate itself only from Wi-Fi and cell towers will never
                // get a position offline, and the failure is silent. Say so.
                log("GPS is switched off for this phone - no position will ever arrive")
            }
            // GPS only. The network provider needs the internet, which is exactly what
            // this whole project assumes is gone.
            lastFix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 2000L, 0f, fixListener
            )
            log(
                if (lastFix != null) "Location on (using last fix while GPS warms up)"
                else "Location on - waiting for a GPS fix. Messages will not wait for it."
            )
            // Tell the user we cannot see satellites BEFORE they need to report something,
            // not while they are trying to.
            handler.postDelayed({
                if (chkLoc.isChecked && currentPosition() == null && typedPlace == null) {
                    askForTypedPlace()
                }
            }, 20_000L)
        } catch (e: SecurityException) {
            log("Location permission missing")
        } catch (e: Exception) {
            log("GPS unavailable on this device")
        }
    }

    private fun stopLocation() {
        try {
            locationManager.removeUpdates(fixListener)
        } catch (e: Exception) {
            // nothing to remove
        }
        log("Location off - messages will carry no position")
    }

    private fun currentPosition(): Position? {
        simulatedPosition?.let { return it }
        val f = lastFix ?: return null
        // A fix from an hour ago is a lie about where someone is now.
        if (System.currentTimeMillis() - f.time > 5 * 60_000L) return null
        return Position(f.latitude, f.longitude)
    }

    /**
     * THE MESSAGE NEVER WAITS FOR A FIX. A cold GPS start can take minutes. A medical
     * report that arrived late because it was waiting for satellites is a failure, so if
     * there is no position ready the message goes without one.
     */
    private fun positionToAttach(): Position? {
        if (!chkLoc.isChecked) return null
        val p = currentPosition()
        if (p == null && typedPlace == null) log("    no fix yet - sent without a location")
        return p
    }

    private fun placeToAttach(): String? = if (chkLoc.isChecked) typedPlace else null

    // -----------------------------------------------------------------------
    // Late location: the message goes now, the location catches up
    // -----------------------------------------------------------------------

    /**
     * Urgent reports this phone sent before it had a position, with the time they were
     * sent. When a fix finally arrives - or the user types where they are - each one gets
     * a small follow-up message carrying the location.
     *
     * Only urgent reports. Chasing a "where is the food stall" with a second message
     * would spend the crowd's radio time on nothing.
     */
    private val awaitingLocation = mutableMapOf<String, Long>()

    /** After this long, a location is no longer worth sending. Stop holding it. */
    private val locationChaseWindow = 10 * 60_000L

    private var typedPlace: String? = null

    private fun rememberForLateLocation(m: MeshMessage) {
        if (m.type.isPlumbing || m.priority < 8) return
        if (m.pos != null || m.place != null) return
        awaitingLocation[m.id] = System.currentTimeMillis()
        log("    location will follow if a fix arrives")
    }

    /**
     * Send the location that was missing when the report went out.
     *
     * It cannot be the same message sent again: every phone has that id in its seen-set
     * and would drop the second copy as a duplicate. So it travels as its own small
     * message pointing back at the original.
     */
    private fun sendLateLocations() {
        if (awaitingLocation.isEmpty()) return
        val now = System.currentTimeMillis()
        val pos = currentPosition()
        val place = typedPlace
        if (pos == null && place == null) return

        awaitingLocation.entries.removeAll { (id, sentAt) ->
            if (now - sentAt > locationChaseWindow) {
                log("Gave up chasing a location for " + id)
                return@removeAll true
            }
            val update = rules.originate(
                MsgType.LOCFIX, "", now, null, pos, place, id
            )
            log("LOCATION FOLLOW-UP for " + id + ": " + (pos?.encode() ?: place))
            broadcast(update)
            true
        }
        refreshInbox()
        updateStats()
    }

    /**
     * Asked when the checkbox is ticked and no fix arrives, NOT when the user is trying to
     * send. Interrupting someone reporting a heart attack to ask for their address is the
     * wrong moment; asking beforehand costs nothing.
     */
    private fun askForTypedPlace() {
        if (isFinishing) return
        val view = dialogBody(
            body = "Your phone cannot get a satellite fix here - usually because of a " +
                "roof. Describe where you are instead, and it will be sent with your reports.",
            hint = "near the big red tent, north side",
            prefill = typedPlace ?: "",
            note = "A description gets a responder the last few metres that GPS cannot. " +
                "Even at five metres accuracy, a crowd holds sixty people.",
            noteColour = Palette.TEAL
        )
        val input = view.findViewById<EditText>(R.id.etDialogInput)
        dialog()
            .setTitle("No GPS signal")
            .setView(view)
            .setPositiveButton("Use this") { _, _ ->
                typedPlace = input.text.toString().trim().take(60).ifEmpty { null }
                if (typedPlace != null) {
                    log("Location typed by hand: " + typedPlace)
                    sendLateLocations()
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun showSimulatedPositionDialog() {
        val view = dialogBody(
            body = "Type where this phone should claim to be, as latitude, longitude.",
            hint = "17.38500, 78.48670",
            prefill = simulatedPosition?.encode() ?: "",
            note = "DEMO ONLY. Three phones on one table share a single position, and " +
                "indoors none of them gets a GPS fix at all, so the map would show one " +
                "dot and prove nothing. Say this out loud on stage rather than letting " +
                "someone find it.",
            noteColour = Palette.ORANGE
        )
        val input = view.findViewById<EditText>(R.id.etDialogInput)
        dialog()
            .setTitle("Set position by hand")
            .setView(view)
            .setPositiveButton("Set") { _, _ ->
                val p = Position.decode(input.text.toString().replace(Regex("\\s"), ""))
                if (p == null) {
                    log("Could not read that as lat,lon")
                } else {
                    simulatedPosition = p
                    chkLoc.isChecked = true
                    log("Position set by hand: " + p.encode() + " (DEMO)")
                    sendLateLocations()
                    refreshInbox()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use real GPS") { _, _ ->
                simulatedPosition = null
                log("Back to real GPS")
                if (chkLoc.isChecked) startLocation()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Screen furniture
    // ------------------------------------------------------------------

    /**
     * Every dialog in the app, on the app's own surface.
     *
     * Material's default alert sheet ignores the activity theme enough to come out pale,
     * square and generic. Two of these dialogs are demo beats in their own right - the
     * organiser key is the trust beat - so they cannot be the one place that looks
     * borrowed from a different app.
     */
    private fun dialog() = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_MeshRelay_Dialog)

    /**
     * The body of an input dialog: what this does, the field, and the caveat.
     *
     * The caveat is not decoration. Both of these dialogs have an honest limit attached -
     * the key is a single shared one with no way to cancel it, the hand-set position is
     * a demo crutch - and putting that on screen where the thing happens is cheaper than
     * remembering to say it, and better than a judge finding it first.
     */
    private fun dialogBody(
        body: String,
        hint: String,
        prefill: String,
        note: String? = null,
        noteColour: Int = Palette.TEAL
    ): View {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        view.findViewById<TextView>(R.id.tvDialogBody).text = body
        view.findViewById<EditText>(R.id.etDialogInput).apply {
            this.hint = hint
            setText(prefill)
            setSelection(text.length)
        }
        val noteView = view.findViewById<TextView>(R.id.tvDialogNote)
        if (note == null) {
            noteView.visibility = View.GONE
        } else {
            noteView.visibility = View.VISIBLE
            noteView.text = note
            noteView.setTextColor(noteColour)
            noteView.background = Palette.pill(
                Palette.tint(noteColour, 26),
                10f * resources.displayMetrics.density,
                Palette.tint(noteColour, 70),
                resources.displayMetrics.density.toInt()
            )
        }
        return view
    }

    /**
     * The report types, as buttons.
     *
     * This was a dropdown until the design pass on 22 Aug. A dropdown hides six of
     * the seven choices behind a tap, and it also quietly contradicts what we tell
     * the judges: that the user never types importance and only picks from fixed
     * options. If that is the claim, the options should be on screen.
     *
     * Each chip wears its own priority colour, so the cost of a choice is visible
     * before it is made rather than explained afterwards.
     */
    private fun buildTypeChips() {
        val dp = resources.displayMetrics.density
        chipRow.removeAllViews()
        for (type in reportableTypes) {
            val chip = TextView(this).apply {
                text = type.label
                textSize = 12f
                setPadding((13 * dp).toInt(), (9 * dp).toInt(), (13 * dp).toInt(), (9 * dp).toInt())
                isSingleLine = true
                setOnClickListener { selectType(type) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (chipRow.childCount > 0) lp.marginStart = (7 * dp).toInt()
            chipRow.addView(chip, lp)
        }
        paintChips()
    }

    private fun selectType(type: MsgType) {
        selectedType = type
        paintChips()
        // Staff-only, and marked as such where it is chosen rather than after it fails.
        if (type.needsSignature && organiserKey == null) {
            log("This phone has no organiser key - that order will go out unsigned")
            log("Every honest phone will refuse it. That is the point.")
        }
    }

    private fun paintChips() {
        val dp = resources.displayMetrics.density
        reportableTypes.forEachIndexed { i, type ->
            val chip = chipRow.getChildAt(i) as? TextView ?: return@forEachIndexed
            val colour = Palette.forPriority(type.priority)
            if (type == selectedType) {
                // Chosen: solid, and dark text on it, so there is no doubt which one is armed.
                chip.background = Palette.pill(colour, 10f * dp)
                chip.setTextColor(Palette.GROUND)
                chip.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                chip.background = Palette.pill(
                    Palette.tint(colour, 26), 10f * dp,
                    Palette.tint(colour, 80), (1 * dp).toInt()
                )
                chip.setTextColor(Palette.TEXT_SECONDARY)
                chip.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        paintSendButton()
    }

    /**
     * SEND stays teal, not the colour of the chosen type. Teal means "the network is
     * about to do something"; the report colours mean "this is how bad it is". Mixing
     * them would make a medical report's SEND button look like an alert in its own right.
     */
    private fun paintSendButton() {
        val dp = resources.displayMetrics.density
        val armed = selectedType != null
        btnSend.background = Palette.pill(
            if (armed) Palette.TEAL else Palette.SURFACE_RAISED, 12f * dp,
            if (armed) Palette.TEAL else Palette.BORDER_STRONG, (1 * dp).toInt()
        )
        btnSend.setTextColor(if (armed) Palette.GROUND else Palette.TEXT_DIM)
    }

    private fun sendWhatIsTyped() {
        val text = etMessage.text.toString().trim()
        val type = selectedType
        if (type == null) {
            log("Pick what is happening first")
            return
        }
        if (text.isEmpty()) return
        originateAndSend(type, text)
        etMessage.setText("")
    }

    private fun buildTabs() {
        tabs.clear()
        tabs += findViewById<TextView>(R.id.tabInbox)
        tabs += findViewById<TextView>(R.id.tabMap)
        tabs += findViewById<TextView>(R.id.tabLog)
        tabs[0].setOnClickListener { showPane(Pane.INBOX) }
        tabs[1].setOnClickListener { showPane(Pane.MAP) }
        tabs[2].setOnClickListener { showPane(Pane.LOG) }
        showPane(Pane.INBOX)
    }

    /**
     * Named tabs rather than one button cycling INBOX -> MAP -> LOG. On stage, tapping
     * past the pane you wanted and having to go round again is a small thing that looks
     * like a large one.
     *
     * The debug log keeps its own tab and stays off the main view: error codes like 8012
     * mean something to us and nothing to a judge (Plan.md 9).
     */
    private fun showPane(target: Pane) {
        if (target == Pane.GRAPH && pane != Pane.GRAPH) paneBeforeGraph = pane
        val changed = target != pane
        pane = target
        fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE
        rvInbox.visibility = vis(pane == Pane.INBOX)
        mapView.visibility = vis(pane == Pane.MAP)
        svLog.visibility = vis(pane == Pane.LOG)
        graphView.visibility = vis(pane == Pane.GRAPH)
        tvPaneTitle.text = pane.title
        // GRAPH has no chip, so its ordinal matches none of them and the whole bar goes
        // unselected - which is right: the lit control is the LINKS button instead.
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == pane.ordinal }
        paintTopologyButton()
        if (changed) fadeInPane()
        refreshInbox()
    }

    /**
     * A short fade as the content area changes hands.
     *
     * Only on a real change of pane, and short enough that it never delays reading what
     * is underneath: switching to the graph in the middle of the demo should feel like
     * one screen becoming another, not like waiting for something to load.
     */
    private fun fadeInPane() {
        val shown: View = when (pane) {
            Pane.INBOX -> rvInbox
            Pane.MAP -> mapView
            Pane.LOG -> svLog
            Pane.GRAPH -> graphView
        }
        shown.animate().cancel()
        shown.alpha = 0f
        shown.animate().alpha(1f).setDuration(160).start()
    }

    /** LINKS is lit while the graph is open, so it reads as a toggle rather than a jump. */
    private fun paintTopologyButton() {
        val on = pane == Pane.GRAPH
        val dp = resources.displayMetrics.density
        // Orange, not teal, when links are cut: the picture on screen is then not the
        // network the room actually has, and that should be uncomfortable to look at.
        val colour = if (blocked.isNotEmpty()) Palette.ORANGE else Palette.TEAL
        btnTopology.text = if (blocked.isEmpty()) "LINKS" else "LINKS " + blocked.size + "✂"
        if (on) {
            btnTopology.setTextColor(colour)
            btnTopology.background = Palette.pill(
                Palette.tint(colour, 34), 10f * dp, colour, (1 * dp).toInt()
            )
        } else {
            btnTopology.setTextColor(
                if (blocked.isEmpty()) Palette.TEXT_SECONDARY else Palette.ORANGE
            )
            btnTopology.setBackgroundResource(R.drawable.bg_ghost_button)
        }
    }

    /**
     * The three tiles, as counters that count rather than numbers that teleport.
     * See TickingNumber - the eviction beat is the reason it exists.
     */
    private val tickers = mutableMapOf<Int, TickingNumber>()

    private fun bumpTile(tile: TextView, value: Int, colour: Int) {
        tickers.getOrPut(tile.id) { TickingNumber(tile) }.set(value, colour)
    }

    /** The identity badge, in its two states. Ordinary phone. */
    private fun showOrdinaryPhone() {
        val dp = resources.displayMetrics.density
        tvName.text = myName
        tvName.setTextColor(Palette.TEXT_SECONDARY)
        tvName.background = Palette.pill(
            Palette.SURFACE, 8f * dp, Palette.BORDER_STRONG, (1 * dp).toInt()
        )
    }

    /**
     * Command phone. Orange because it is an elevated state that someone should notice
     * across a table - "this is the one holding the key" - without claiming an emergency.
     */
    private fun showCommandPhone() {
        val dp = resources.displayMetrics.density
        tvName.text = "COMMAND CENTRE"
        tvName.setTextColor(Palette.ORANGE)
        tvName.background = Palette.pill(
            Palette.tint(Palette.ORANGE, 34), 8f * dp,
            Palette.ORANGE, (1 * dp).toInt()
        )
    }

    /**
     * The banner. Three states, and the middle one matters most: "searching" is not a
     * failure, it is what a mesh looks like between encounters, and saying so stops a
     * silent screen reading as a broken app.
     */
    private fun paintMeshBanner() {
        val dp = resources.displayMetrics.density
        val peers = connected.size
        val colour: Int
        val state: String
        val hint: String

        val puck: MeshPulseView.Mode

        when {
            // Not "OFFLINE". Nothing is broken - it has not been started. On a screen
            // whose entire claim is "this works when everything else is down", the word
            // offline is the wrong first thing a judge reads.
            !meshRunning -> {
                colour = Palette.SLATE
                state = "TAP TO JOIN THE NETWORK"
                hint = "This phone is not carrying messages for anyone yet"
                puck = MeshPulseView.Mode.IDLE
            }
            peers > 0 -> {
                colour = Palette.TEAL
                state = "CONNECTED TO " + peers + " PHONE" + (if (peers == 1) "" else "S")
                hint = "Passing messages on for people around you"
                puck = MeshPulseView.Mode.LINKED
            }
            // Bluetooth advertising and discovery genuinely take a moment to come up,
            // and this is that moment - not a delay inserted to look busy.
            stage == MeshStage.RADIO -> {
                colour = Palette.ORANGE
                state = "STARTING RADIO"
                hint = "Advertising this phone, and listening for others"
                puck = MeshPulseView.Mode.SCANNING
            }
            // A real phone has been discovered and the two sides are settling which of
            // them places the call.
            stage == MeshStage.FOUND -> {
                colour = Palette.ORANGE
                state = "PHONE FOUND"
                hint = "Agreeing which side dials, then linking"
                puck = MeshPulseView.Mode.SCANNING
            }
            // Silence is the normal state of a mesh between encounters, and this is a
            // free chance to narrate store-and-forward before anyone has to explain it.
            else -> {
                colour = Palette.ORANGE
                state = "LOOKING FOR PHONES"
                hint = "No one in range. Anything you send waits here and goes out " +
                    "the moment a phone appears"
                puck = MeshPulseView.Mode.SCANNING
            }
        }

        cardMesh.background = Palette.pill(
            Palette.tint(colour, 30), 14f * dp, Palette.tint(colour, 130), (1 * dp).toInt()
        )
        // One dot per real link, labelled by node so a given phone keeps its position
        // on the dial while others come and go.
        vMeshDot.setState(puck, colour, connected.map { nodeOf(it) }.filter { it.isNotEmpty() })

        tvMeshState.setTextColor(colour)
        tvMeshHint.setTextColor(Palette.TEXT_SECONDARY)

        // Unchanged, or the very first paint: set it and say nothing. Fading the banner
        // in at launch would read as the app still loading, which is the opposite of
        // what this screen is for.
        if (state == lastBannerState || lastBannerState.isEmpty()) {
            lastBannerState = state
            tvMeshState.text = state
            tvMeshHint.text = hint
            return
        }
        lastBannerState = state

        // Crossfade, so the startup sequence reads as one thing progressing rather than
        // three unrelated words flickering. Short: this is punctuation between real
        // events, and it must never be the reason someone waits to see the truth.
        tvMeshState.animate().cancel()
        tvMeshHint.animate().cancel()
        val swap = Runnable {
            tvMeshState.text = state
            tvMeshHint.text = hint
            tvMeshState.animate().alpha(1f).setDuration(170).start()
            tvMeshHint.animate().alpha(1f).setDuration(170).start()
        }
        tvMeshState.animate().alpha(0f).setDuration(110).withEndAction(swap).start()
        tvMeshHint.animate().alpha(0f).setDuration(110).start()
    }

    private fun refreshInbox() {
        runOnUiThread {
            val held = rules.inboxOrder()
            inbox.submit(held, currentPosition(), nodeId) { rules.deliveryOf(it) }
            mapView.show(held, currentPosition())
            // An empty list should say why it is empty. A blank panel during a demo
            // reads as a crash for the two seconds before anyone explains it.
            tvEmpty.visibility =
                if (held.isEmpty() && pane == Pane.INBOX) View.VISIBLE else View.GONE
            paintGraph()
            paintLocationState()
        }
    }

    /**
     * Whether this phone actually knows where it is - not merely whether the box is
     * ticked. A cold GPS fix takes minutes and a phone that leans on Wi-Fi and cell
     * towers gets nothing at all offline, and both failures are otherwise silent.
     *
     * Waiting is orange rather than red: a message never waits for a fix, so no fix
     * is a smaller report, not a broken one.
     */
    private fun paintLocationState() {
        if (!chkLoc.isChecked) {
            tvLocState.text = "off"
            tvLocState.setTextColor(Palette.TEXT_DIM)
            return
        }
        val p = currentPosition()
        if (p == null) {
            tvLocState.text = "waiting for a fix"
            tvLocState.setTextColor(Palette.ORANGE)
        } else {
            tvLocState.text = if (simulatedPosition != null) "set by hand" else "fix ok"
            tvLocState.setTextColor(Palette.TEAL)
        }
    }

    /**
     * The decision layer's only visible output.
     *
     * Split into two ranks. The three tiles are what a person watching from three metres
     * away can actually read, and they are the three that change during the demo. The
     * monospace line underneath holds the rest - still on screen, because a rule the
     * judges cannot see does not exist (Plan.md 17.2), but not competing for attention.
     */
    private fun updateStats() {
        refreshInbox()
        runOnUiThread {
            paintMeshBanner()

            bumpTile(
                tvPeers, connected.size,
                if (connected.isEmpty()) Palette.TEXT_DIM else Palette.TEAL
            )
            // The cap is in the caption rather than the number: "7" reads at a glance,
            // "7/200" does not. When the store is squeezed to 10 for the eviction beat,
            // the caption says DEMO out loud rather than letting it pass unnoticed.
            val squeezed = rules.storeCap != 200
            tvStoredCaption.text =
                if (squeezed) "Carrying / " + rules.storeCap + " · DEMO" else "Carrying"
            val full = rules.storeSize() >= rules.storeCap
            bumpTile(
                tvStored, rules.storeSize(),
                if (full) Palette.AMBER else Palette.TEXT
            )

            val flight = rules.awaitingConfirmation()
            bumpTile(
                tvFlight, flight,
                if (flight > 0) Palette.ORANGE else Palette.TEXT_DIM
            )
            tvFlightCaptionText()

            tvStats.text = "dup blocked " + rules.duplicatesBlocked +
                "   forwards " + rules.forwards +
                "   evicted " + rules.evicted +
                "\nconfirmed " + rules.confirmed +
                "   links cut " + blocked.size

            paintTopologyButton()
        }
    }

    /**
     * Hand the graph everything this phone knows about the network.
     *
     * Two kinds of node and the difference between them is the point: phones linked right
     * now, which this one can hand a message to this second, and phones it has only heard
     * of through the path on a message that arrived. On three phones with one link cut,
     * the far phone is the second kind - visible, and provably not reachable.
     */
    private fun paintGraph() {
        val liveNodes = connected.mapNotNull { id ->
            val node = nodeOf(id)
            if (node.isEmpty()) null
            else MeshGraphView.Peer(node, deviceModelOf(node), linked = true)
        }
        val liveIds = liveNodes.map { it.id }.toSet()

        // Only ever heard of. A phone that is currently linked is not also drawn as a
        // rumour about itself.
        val distant = heardOf
            .filter { it !in liveIds && it != nodeId }
            .map { MeshGraphView.Peer(it, deviceModelOf(it), linked = false) }

        // Every live link is also an observed link - this phone is one end of it.
        val links = observedLinks.toMutableSet()
        for (n in liveIds) links += linkKey(nodeId, n)

        graphView.show(nodeId, liveNodes + distant, links)
    }

    /**
     * The device model for a node, where we happen to know it.
     *
     * We only know it for phones this one has actually linked to, because the model name
     * travels in the Nearby endpoint name and nowhere else - a message carries node ids
     * and nothing about the hardware. So a distant phone shows as its id, which is honest
     * about what this phone actually knows.
     */
    private fun deviceModelOf(node: String): String {
        val name = peerNames.values.firstOrNull { nodeIdOf(it) == node } ?: return ""
        return name.substringBeforeLast('-')
    }

    /** Confirmed is worth its own word once anything has been confirmed. */
    private fun tvFlightCaptionText() {
        val caption = findViewById<TextView>(R.id.tvFlightCaption)
        caption.text = if (rules.confirmed > 0) "In flight · " + rules.confirmed + " done"
        else "In flight"
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            tvLog.append("[" + time + "] " + message + "\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        // A counter half way through a roll must not outlive the view it is writing into.
        for (t in tickers.values) t.stop()
        // Stopping endpoints alone was not enough: advertising and discovery kept
        // running after the Activity died, and the next instance then found them.
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
    }
}
