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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var spType: Spinner
    private lateinit var rvInbox: RecyclerView
    private lateinit var tvPaneTitle: TextView
    private lateinit var chkLoc: CheckBox
    private lateinit var mapView: ReportMapView
    private val inbox = MessageAdapter()

    /** INBOX -> MAP -> LOG, cycled by one button. */
    private enum class Pane(val title: String, val next: String) {
        INBOX("INCOMING - most urgent first", "MAP"),
        MAP("COMMAND CENTRE - reports by location", "LOG"),
        LOG("DEBUG LOG", "INBOX")
    }

    private var pane = Pane.INBOX

    /** What a person may actually send. Excludes app plumbing such as location updates. */
    private val reportableTypes = MsgType.entries.filter { !it.isPlumbing }

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
        spType = findViewById(R.id.spType)
        rvInbox = findViewById(R.id.rvInbox)
        tvPaneTitle = findViewById(R.id.tvPaneTitle)
        chkLoc = findViewById(R.id.chkLoc)
        mapView = findViewById(R.id.mapView)

        // Sharing a position is opt-in and starts OFF. This network copies messages onto
        // strangers phones and holds them for hours; that is the wrong place for anyone
        // exact whereabouts unless they chose it knowingly.
        chkLoc.setOnCheckedChangeListener { _, on -> if (on) startLocation() else stopLocation() }
        chkLoc.setOnLongClickListener { showSimulatedPositionDialog(); true }

        rvInbox.layoutManager = LinearLayoutManager(this)
        rvInbox.adapter = inbox

        tvName.text = "I am: " + myName
        connections = Nearby.getConnectionsClient(this)

        blocked += getSharedPreferences("meshrelay", MODE_PRIVATE)
            .getStringSet("blocked", emptySet()) ?: emptySet()

        // The app assigns priority from the type. The user never types "URGENT".
        // Plumbing types are not offered - they are sent by the app, not by a person.
        spType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            reportableTypes.map { it.label + "  (priority " + it.priority + ")" }
        )

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (meshRunning) {
                log("Mesh is already running")
            } else {
                log("Requesting permissions...")
                askPermissions.launch(requiredPermissions())
            }
        }

        findViewById<Button>(R.id.btnTopology).setOnClickListener { showTopologyDialog() }

        // Error codes like 8012 mean something to us and nothing to a judge. They stay
        // one tap away, not on screen during the demo (Plan.md 9, 22 Aug).
        findViewById<Button>(R.id.btnView).setOnClickListener { toggleView() }

        // Long-press the title to turn this phone into the command phone. Hidden
        // because it is a staff action, not something a visitor should ever find.
        tvName.setOnLongClickListener { showKeyDialog(); true }

        // Eviction is invisible with room for 200 messages. Long-press the counters to
        // shrink the store to 10 so "the junk is dropped and the emergency survives"
        // can actually be watched happening (Plan.md 17.2).
        tvStats.setOnLongClickListener {
            rules.storeCap = if (rules.storeCap == 200) 10 else 200
            // Rehearsal sends more emergencies per hour than any real person would.
            rules.resetRateLimit()
            log("Store cap now " + rules.storeCap + (if (rules.storeCap == 10) " (DEMO)" else ""))
            log("Rate limit cleared")
            updateStats()
            true
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                originateAndSend(reportableTypes[spType.selectedItemPosition], text)
                etMessage.setText("")
            }
        }

        log("Ready. Tap START MESH.")
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

        connections.startAdvertising(
            myName,
            serviceId,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("ADVERTISING started") }
            .addOnFailureListener { log("ADVERTISING failed: " + it.message) }

        connections.startDiscovery(
            serviceId,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("DISCOVERY started") }
            .addOnFailureListener { log("DISCOVERY failed: " + it.message) }
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
            when (rules.onReceive(m, nodeIdOf(peerNames[endpointId] ?: ""))) {
                Verdict.DUPLICATE ->
                    log("dup blocked: " + m.id)

                Verdict.UNSIGNED_AUTHORITY ->
                    log("REFUSED unsigned OFFICIAL order from " + m.origin + " - not displayed")

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
        val input = EditText(this).apply {
            hint = "Paste organiser private key"
            setText(organiserKey?.let { "" } ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(if (organiserKey == null) "Become command phone" else "Command phone")
            .setMessage("The key is never stored and is not in the app.")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val parsed = Authority.parsePrivateKey(input.text.toString())
                if (parsed == null) {
                    log("That is not a usable key - still an ordinary phone")
                } else {
                    organiserKey = parsed
                    tvName.text = "COMMAND - " + myName
                    log("This phone can now issue OFFICIAL orders")
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                organiserKey = null
                tvName.text = "I am: " + myName
                log("Organiser key cleared - ordinary phone again")
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
        AlertDialog.Builder(this)
            .setTitle("Cut these links (demo topology)")
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                val n = nodeIdOf(known[which])
                if (isChecked) blocked += n else blocked -= n
            }
            .setPositiveButton("Apply") { _, _ ->
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
                // Un-ticking a phone has to actively call it back. Nearby only reports a
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
            .setNegativeButton("Cancel", null)
            .show()
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
        val input = EditText(this).apply {
            hint = "e.g. near the big red tent, north side"
            setText(typedPlace ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("No GPS signal")
            .setMessage(
                "Your phone cannot get a satellite fix here - usually because of a roof. " +
                    "Describe where you are instead, and it will be sent with your reports."
            )
            .setView(input)
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
        val input = EditText(this).apply {
            hint = "17.38500, 78.48670"
            setText(simulatedPosition?.encode() ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle("Set position by hand (demo)")
            .setMessage(
                "Three phones on one table share one position, and indoors there is no " +
                    "GPS fix at all. Setting it here makes the map meaningful. Say so on stage."
            )
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val p = Position.decode(input.text.toString().replace(" ", ""))
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

    private fun toggleView() {
        pane = when (pane) {
            Pane.INBOX -> Pane.MAP
            Pane.MAP -> Pane.LOG
            Pane.LOG -> Pane.INBOX
        }
        fun vis(on: Boolean) = if (on) android.view.View.VISIBLE else android.view.View.GONE
        rvInbox.visibility = vis(pane == Pane.INBOX)
        mapView.visibility = vis(pane == Pane.MAP)
        svLog.visibility = vis(pane == Pane.LOG)
        tvPaneTitle.text = pane.title
        findViewById<Button>(R.id.btnView).text = pane.next
        refreshInbox()
    }

    private fun refreshInbox() {
        runOnUiThread {
            val held = rules.inboxOrder()
            inbox.submit(held, currentPosition())
            mapView.show(held, currentPosition())
        }
    }

    private fun updateStats() {
        refreshInbox()
        runOnUiThread {
            tvStats.text = "peers " + connected.size +
                "   stored " + rules.storeSize() + "/" + rules.storeCap +
                "   dup blocked " + rules.duplicatesBlocked +
                "\nforwards " + rules.forwards +
                "   evicted " + rules.evicted +
                "   cut " + blocked.size
        }
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
        // Stopping endpoints alone was not enough: advertising and discovery kept
        // running after the Activity died, and the next instance then found them.
        connections.stopAdvertising()
        connections.stopDiscovery()
        connections.stopAllEndpoints()
    }
}
