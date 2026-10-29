package com.anro.beam

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Helper class to manage Wi‑Fi Direct (Wi‑P2P) operations.
 *
 * It abstracts peer discovery, connection negotiation, and socket creation.
 * All callbacks are posted on the main thread for UI safety.
 */
class WiFiDirectHelper(
    private val context: Context,
    private val listener: WiFiDirectListener
) {
    companion object {
        private const val TAG = "WiFiDirectHelper"
        const val WIFIP2P_PORT = 8988 // Standard port used in many examples
    }

    private val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = manager.initialize(context, Looper.getMainLooper(), null)
    private val handler = Handler(Looper.getMainLooper())

    private val peerList = mutableListOf<WifiP2pDevice>()
    private var isDiscovering = false
    private var connectionInfo: WifiP2pInfo? = null

    /** Public interface for the activity to receive events. */
    interface WiFiDirectListener {
        fun onPeersAvailable(peers: List<WifiP2pDevice>)
        fun onConnectionInfoAvailable(info: WifiP2pInfo)
        fun onDiscoveryStarted()
        fun onDiscoveryStopped()
        fun onError(message: String)
    }

    /** Start peer discovery. */
    fun startDiscovery() {
        if (isDiscovering) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = true
                listener.onDiscoveryStarted()
                Log.d(TAG, "Peer discovery started")
            }
            override fun onFailure(reason: Int) {
                listener.onError("Peer discovery failed: $reason")
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
    }

    /** Stop peer discovery. */
    fun stopDiscovery() {
        if (!isDiscovering) return
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isDiscovering = false
                listener.onDiscoveryStopped()
                Log.d(TAG, "Peer discovery stopped")
            }
            override fun onFailure(reason: Int) {
                listener.onError("Stop discovery failed: $reason")
                Log.e(TAG, "Stop discovery failed: $reason")
            }
        })
    }

    /** Register a broadcast receiver to listen for Wi‑Fi Direct events. */
    fun registerReceiver(receiver: android.content.BroadcastReceiver) {
        val intentFilter = android.content.IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
    }

    /** Unregister the broadcast receiver. */
    fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {
        try { context.unregisterReceiver(receiver) } catch (e: IllegalArgumentException) { /* ignore */ }
    }

    /** Handle the peers‑changed broadcast. */
    fun requestPeers() {
        manager.requestPeers(channel) { peers ->
            peerList.clear()
            peerList.addAll(peers.deviceList)
            listener.onPeersAvailable(peerList)
            Log.d(TAG, "Peers updated: ${peerList.size}")
        }
    }

    /** Initiate a connection to a selected device. */
    fun connectToDevice(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Use group owner intent to prefer this device as group owner when appropriate.
            // 15 = highest chance to become GO, 0 = lowest.
            groupOwnerIntent = 15
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiation successful")
            }
            override fun onFailure(reason: Int) {
                listener.onError("Connection failed: $reason")
                Log.e(TAG, "Connection failed: $reason")
            }
        })
    }

    /** Called when the connection state changes – retrieve group info. */
    fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            connectionInfo = info
            listener.onConnectionInfoAvailable(info)
            Log.d(TAG, "ConnectionInfo: isGroupOwner=${info.isGroupOwner}, groupOwnerAddress=${info.groupOwnerAddress.hostAddress}")
        }
    }

    /** Helper to create a client socket (used by the non‑group‑owner). */
    fun createClientSocket(): java.net.Socket {
        val info = connectionInfo ?: throw IllegalStateException("ConnectionInfo not available")
        val host = if (info.isGroupOwner) "127.0.0.1" else info.groupOwnerAddress.hostAddress
        return java.net.Socket(host, WIFIP2P_PORT)
    }

    /** Helper to create a server socket (used by the group owner). */
    fun createServerSocket(): java.net.ServerSocket {
        return java.net.ServerSocket(WIFIP2P_PORT)
    }
}
