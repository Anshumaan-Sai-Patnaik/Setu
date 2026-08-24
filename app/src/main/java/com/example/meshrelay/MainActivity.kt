package com.example.meshrelay

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Every device must use the same SERVICE_ID or they will never find each other.
    private val serviceId = "com.example.meshrelay.SERVICE"
    private val strategy = Strategy.P2P_CLUSTER

    private lateinit var connections: ConnectionsClient
    private lateinit var tvName: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var etMessage: EditText

    private val myName = Build.MODEL + "-" + (100..999).random()
    private val connected = mutableSetOf<String>()

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
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        etMessage = findViewById(R.id.etMessage)

        tvName.text = "I am: $myName"
        connections = Nearby.getConnectionsClient(this)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            log("Requesting permissions...")
            askPermissions.launch(requiredPermissions())
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendToAll(text)
                etMessage.setText("")
            }
        }

        log("Ready. Tap START MESH.")
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
        connections.startAdvertising(
            myName,
            serviceId,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("ADVERTISING started") }
            .addOnFailureListener { log("ADVERTISING failed: ${it.message}") }

        connections.startDiscovery(
            serviceId,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build()
        )
            .addOnSuccessListener { log("DISCOVERY started") }
            .addOnFailureListener { log("DISCOVERY failed: ${it.message}") }
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log("FOUND ${info.endpointName} -> requesting connection")
            connections.requestConnection(myName, endpointId, connectionLifecycle)
                .addOnFailureListener { log("requestConnection failed: ${it.message}") }
        }

        override fun onEndpointLost(endpointId: String) {
            log("LOST $endpointId")
        }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            log("Handshake with ${info.endpointName} - accepting")
            connections.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected += endpointId
                log("CONNECTED (${connected.size} peer(s))")
            } else {
                log("Connection failed: code ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            log("DISCONNECTED (${connected.size} peer(s))")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val text = payload.asBytes()?.toString(StandardCharsets.UTF_8) ?: return
            log(">>> RECEIVED: $text")
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // not needed for short text messages
        }
    }

    private fun sendToAll(text: String) {
        if (connected.isEmpty()) {
            log("Nobody connected yet - nothing sent")
            return
        }
        val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))
        connections.sendPayload(connected.toList(), payload)
        log("<<< SENT: $text")
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            tvLog.append("[$time] $message\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connections.stopAllEndpoints()
    }
}
