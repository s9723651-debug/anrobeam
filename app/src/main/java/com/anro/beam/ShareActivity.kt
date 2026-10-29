package com.anro.beam

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
// NetworkInfo intentionally omitted — deprecated API 29; using requestConnectionInfo() instead
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences
import android.view.LayoutInflater
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * ANRO-BEAM v0.1.8.9
 *
 * NFC-free, internet-free file transfer.
 * Discovery  : BLE advertising (receiver) + BLE scan (sender)
 * Transport  : Wi-Fi Direct TCP socket — always (max speed)
 * Max files  : 10 per batch, up to 5 GB total
 *
 * No Android Beam, no HCE, no deprecated APIs.
 */
class ShareActivity : AppCompatActivity() {

    enum class AppState {
        MODE_SELECTION, SHARE_HOME, RECEIVE_HOME, CONNECTING, TRANSFERRING, FINISHED
    }

    // ─── Constants ────────────────────────────────────────────────
    private val RFCOMM_UUID      = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
    private val BLE_SERVICE_UUID = UUID.fromString("0000fa87-0000-1000-8000-00805f9b34fb")

    /** Maximum total batch size: 5 GB */
    private val MAX_BATCH_BYTES = 5L * 1024L * 1024L * 1024L  // 5 GB
    /** Maximum number of files per batch */
    private val MAX_FILES = 10

    /** TCP port used when transferring over Wi-Fi Direct */
    private val WIFIP2P_PORT = 8988

    // ─── NFC ───────────────────────────────────────────────────
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    @Suppress("DEPRECATION")
    private var bluetoothAdapter: BluetoothAdapter? = null

    // ─── Wi-Fi Direct ─────────────────────────────────────────────
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var wifiP2pReceiver: BroadcastReceiver? = null
    private var wifiDirectGroupOwnerAddress: String? = null
    private var isWifiDirectGroupOwner = false
    /** true when we have chosen Wi-Fi Direct for this session */
    private var useWifiDirect = false
    /** true while discoverPeers is active so the broadcast receiver can call requestPeers */
    private var isDiscoveringPeers = false

    // Preferences
    private val PREFS_NAME = "anro_prefs"
    private val KEY_DEV_MODE = "dev_mode_enabled"
    private val KEY_USE_WIFI = "use_wifi_transfer"
    private val KEY_VERBOSE_LOG = "verbose_logging_enabled"
    private val KEY_TUTORIAL_SEEN = "first_run_tutorial_seen"
    private val UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/s9723651-debug/anrobeam/main/update.json"

    private data class AppUpdate(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val releaseNotes: String
    )

    // ─── UI References ────────────────────────────────────────────
    private lateinit var layoutModeSelection: View
    private lateinit var layoutShareHome: View
    private lateinit var layoutReceiveHome: View
    private lateinit var layoutConnecting: View
    private lateinit var layoutTransfer: View
    private lateinit var layoutFinished: View

    private lateinit var txtBluetoothStatusVal: TextView
    private lateinit var btnPermissionSettings: TextView
    private lateinit var btnSettings: TextView

    private lateinit var btnChooseFileText: TextView
    private lateinit var selectedFileSubtext: TextView

    private lateinit var txtReceiveStatus: TextView
    private lateinit var receiveWave1: View
    private lateinit var receiveWave2: View

    private lateinit var txtConnectingState: TextView
    private lateinit var waveCircle1: View
    private lateinit var waveCircle2: View

    private lateinit var txtTransferDirection: TextView
    private lateinit var txtTransferFileName: TextView
    private lateinit var txtTransferFileSize: TextView
    private lateinit var progressBarTransfer: ProgressBar
    private lateinit var txtTransferSpeed: TextView
    private lateinit var txtTransferPercent: TextView
    private lateinit var txtTransferTimeRemaining: TextView
    private lateinit var txtFinishedMessage: TextView

    // ─── State ────────────────────────────────────────────────────
    private var currentState = AppState.MODE_SELECTION

    data class FileInfo(val uri: Uri, val name: String, val size: Long)
    private val selectedFiles = mutableListOf<FileInfo>()

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var isTransmissionActive = false
    private var btServerThread: Thread? = null

    private val animationHandler = Handler(Looper.getMainLooper())
    private val mainHandler     = Handler(Looper.getMainLooper())

    private var incomingDialog: AlertDialog? = null
    private var updateCheckInProgress = false
    private var updateWaitingForPermission: AppUpdate? = null
    private var apkWaitingForInstallPermission: File? = null
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = apkWaitingForInstallPermission
        val update = updateWaitingForPermission
        apkWaitingForInstallPermission = null
        updateWaitingForPermission = null
        if (canInstallUnknownPackages() && update != null) {
            downloadUpdate(update)
        } else if (apk != null && canInstallUnknownPackages()) {
            installDownloadedUpdate(apk)
        } else if (apk != null || update != null) {
            Toast.makeText(this, "Allow ANRO-BEAM to install updates, then tap Check for Updates again.", Toast.LENGTH_LONG).show()
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BeamTransferService.ACTION_TRANSFER_REQUEST -> {
                    val sender = intent.getStringExtra("sender") ?: "Device"
                    val count = intent.getIntExtra("filesCount", 0)
                    val totalSize = intent.getLongExtra("totalSize", 0L)
                    val filesMeta = intent.getStringExtra("filesMeta") ?: ""
                    
                    val filesList = mutableListOf<Pair<String, Long>>()
                    if (filesMeta.isNotEmpty()) {
                        filesMeta.split(";").forEach { item ->
                            val ip = item.split(":")
                            if (ip.size >= 2) filesList.add(Pair(ip[0], ip[1].toLongOrNull() ?: 0L))
                        }
                    }
                    showIncomingTransferDialog(sender, count, totalSize, filesList)
                }
                BeamTransferService.ACTION_TRANSFER_PROGRESS -> {
                    val fileName = intent.getStringExtra("fileName") ?: ""
                    val fileIndex = intent.getIntExtra("fileIndex", 0)
                    val filesCount = intent.getIntExtra("filesCount", 0)
                    val percentage = intent.getIntExtra("percentage", 0)
                    val speed = intent.getDoubleExtra("speed", 0.0)
                    val eta = intent.getStringExtra("eta") ?: ""

                    runOnUiThread {
                        updateUiState(AppState.TRANSFERRING)
                        txtTransferDirection.text = "Receiving (${fileIndex + 1} of $filesCount):"
                        txtTransferFileName.text  = fileName
                        txtTransferFileSize.text  = ""
                        progressBarTransfer.progress = percentage
                        txtTransferPercent.text       = "$percentage%"
                        txtTransferSpeed.text         = "%.2f MB/s".format(speed)
                        txtTransferTimeRemaining.text = eta
                    }
                }
                BeamTransferService.ACTION_TRANSFER_COMPLETE -> {
                    val count = intent.getIntExtra("filesCount", 0)
                    val firstName = intent.getStringExtra("firstFileName") ?: ""
                    runOnUiThread {
                        updateUiState(AppState.FINISHED)
                        txtFinishedMessage.text = if (count == 1)
                            "Saved to Downloads:\n$firstName"
                        else "Saved $count files to Downloads!"
                    }
                }
                BeamTransferService.ACTION_TRANSFER_ERROR -> {
                    val msg = intent.getStringExtra("message") ?: "Transfer failed"
                    runOnUiThread {
                        Toast.makeText(this@ShareActivity, msg, Toast.LENGTH_LONG).show()
                        resetTransmission()
                    }
                }
                BeamTransferService.ACTION_DECISION_MADE -> {
                    runOnUiThread {
                        incomingDialog?.dismiss()
                        incomingDialog = null
                    }
                }
            }
        }
    }

    // ─── BLE callbacks ────────────────────────────────────────────
    private val bleAdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("AnroBeam", "BLE advertising started.")
            runOnUiThread { txtReceiveStatus.text = "Discoverable via Bluetooth" }
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("AnroBeam", "BLE advertise failed: $errorCode")
            runOnUiThread { txtReceiveStatus.text = "BLE advertising failed ($errorCode)" }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BLE_SERVICE_UUID))
            if (serviceData != null) {
                val payload = String(serviceData, Charsets.UTF_8).trim()
                Log.d("AnroBeam", "Found ANRO-BEAM device: ${device.address} payload=$payload")
                stopBleScan()
                connectToReceiver(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("AnroBeam", "BLE scan failed: $errorCode")
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Scan failed ($errorCode). Make sure Bluetooth is on.", Toast.LENGTH_LONG).show()
                updateUiState(AppState.SHARE_HOME)
            }
        }
    }

    // Connect to receiver via Wi‑Fi Direct
    private fun connectToReceiver(device: BluetoothDevice) {
        Log.d("AnroBeam", "Connecting to receiver ${device.address}")
        useWifiDirect = isWifiPreferred()
        initWifiDirectAsSender()
    }

    // ─── Permissions ──────────────────────────────────────────────
    private val requiredPermissions: Array<String>
        get() {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list += android.Manifest.permission.BLUETOOTH_SCAN
                list += android.Manifest.permission.BLUETOOTH_CONNECT
                list += android.Manifest.permission.BLUETOOTH_ADVERTISE
            } else {
                @Suppress("DEPRECATION")
                list += android.Manifest.permission.BLUETOOTH
                @Suppress("DEPRECATION")
                list += android.Manifest.permission.BLUETOOTH_ADMIN
                list += android.Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += android.Manifest.permission.NEARBY_WIFI_DEVICES
                list += android.Manifest.permission.POST_NOTIFICATIONS
            } else {
                list += android.Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list += android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                list += android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return list.distinct().toTypedArray()
        }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? -> uris?.let { handleSelectedFiles(it) } }

    // ═════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireClicks()

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        // Initialise NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        val nfcIntent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0, nfcIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Initialise Wi-Fi Direct manager
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        wifiP2pChannel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)

        if (!hasRequiredPermissions()) {
            btnPermissionSettings.visibility = View.VISIBLE
            checkAndRequestPermissions()
        }

        updateStatusIndicators()
        setupBackPressHandler()
        updateUiState(AppState.MODE_SELECTION)
        handleIncomingIntent(intent)

        if (!prefs().getBoolean(KEY_TUTORIAL_SEEN, false)) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) showTutorial()
            }, 450L)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        registerWifiDirectReceiver()

        val filter = IntentFilter().apply {
            addAction(BeamTransferService.ACTION_TRANSFER_REQUEST)
            addAction(BeamTransferService.ACTION_TRANSFER_PROGRESS)
            addAction(BeamTransferService.ACTION_TRANSFER_COMPLETE)
            addAction(BeamTransferService.ACTION_TRANSFER_ERROR)
            addAction(BeamTransferService.ACTION_DECISION_MADE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        if (currentState == AppState.CONNECTING) {
            enableNfcForegroundDispatch()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterWifiDirectReceiver()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (ignored: Exception) {}
        disableNfcForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null && currentState == AppState.CONNECTING) {
                handleNfcTag(tag)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        resetTransmission()
    }

    // ─── Wi-Fi Direct receiver ────────────────────────────────────
    private fun registerWifiDirectReceiver() {
        val manager  = wifiP2pManager ?: return
        val channel  = wifiP2pChannel ?: return
        val receiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        if (isDiscoveringPeers) {
                            isDiscoveringPeers = false
                            manager.requestPeers(channel) { peers ->
                                val target = peers.deviceList.firstOrNull()
                                if (target != null) {
                                    Log.d("AnroBeam", "Peers changed: connecting to ${target.deviceAddress}")
                                    connectWifiDirectPeer(target, manager, channel)
                                } else {
                                    Log.w("AnroBeam", "PEERS_CHANGED but list is empty")
                                    runOnUiThread {
                                        Toast.makeText(this@ShareActivity,
                                            "No Wi-Fi Direct receiver found nearby. Make sure the other device is in Receive Mode.",
                                            Toast.LENGTH_LONG).show()
                                        resetTransmission()
                                    }
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager.requestConnectionInfo(channel, connectionInfoListener)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(receiver, filter)
        wifiP2pReceiver = receiver
    }

    private fun unregisterWifiDirectReceiver() {
        try { wifiP2pReceiver?.let { unregisterReceiver(it) } } catch (ignored: Exception) {}
        wifiP2pReceiver = null
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
        wifiDirectGroupOwnerAddress = info.groupOwnerAddress?.hostAddress
        isWifiDirectGroupOwner     = info.isGroupOwner
        Log.d("AnroBeam", "Wi-Fi Direct connected. GO=$isWifiDirectGroupOwner addr=$wifiDirectGroupOwnerAddress")
    }

    // ─── View binding ─────────────────────────────────────────────
    private fun bindViews() {
        layoutModeSelection = findViewById(R.id.layoutModeSelection)
        layoutShareHome     = findViewById(R.id.layoutShareHome)
        layoutReceiveHome   = findViewById(R.id.layoutReceiveHome)
        layoutConnecting    = findViewById(R.id.layoutConnecting)
        layoutTransfer      = findViewById(R.id.layoutTransfer)
        layoutFinished      = findViewById(R.id.layoutFinished)

        txtBluetoothStatusVal = findViewById(R.id.txtBluetoothStatusVal)
        btnPermissionSettings = findViewById(R.id.btnPermissionSettings)
        btnSettings           = findViewById(R.id.btnSettings)

        btnChooseFileText   = findViewById(R.id.btnChooseFileText)
        selectedFileSubtext = findViewById(R.id.selectedFileSubtext)

        txtReceiveStatus = findViewById(R.id.txtReceiveStatus)
        receiveWave1     = findViewById(R.id.receiveWave1)
        receiveWave2     = findViewById(R.id.receiveWave2)

        txtConnectingState = findViewById(R.id.txtConnectingState)
        waveCircle1        = findViewById(R.id.waveCircle1)
        waveCircle2        = findViewById(R.id.waveCircle2)

        txtTransferDirection     = findViewById(R.id.txtTransferDirection)
        txtTransferFileName      = findViewById(R.id.txtTransferFileName)
        txtTransferFileSize      = findViewById(R.id.txtTransferFileSize)
        progressBarTransfer      = findViewById(R.id.progressBarTransfer)
        txtTransferSpeed         = findViewById(R.id.txtTransferSpeed)
        txtTransferPercent       = findViewById(R.id.txtTransferPercent)
        txtTransferTimeRemaining = findViewById(R.id.txtTransferTimeRemaining)
        txtFinishedMessage       = findViewById(R.id.txtFinishedMessage)
    }

    private fun wireClicks() {
        findViewById<View>(R.id.cardShareMode).setOnClickListener   { navigateToShareHome() }
        findViewById<View>(R.id.cardReceiveMode).setOnClickListener { navigateToReceiveHome() }
        findViewById<View>(R.id.cardChooseFile).setOnClickListener  {
            if (hasRequiredPermissions()) filePickerLauncher.launch("*/*")
            else checkAndRequestPermissions()
        }
        // Send button: tapping is a fallback; auto-start fires on file selection
        findViewById<View>(R.id.btnSendFile).setOnClickListener { onSendTapped() }

        findViewById<View>(R.id.btnShareBack).setOnClickListener    { navigateBack() }
        findViewById<View>(R.id.btnReceiveBack).setOnClickListener  { navigateBack() }
        findViewById<View>(R.id.btnCancelConnecting).setOnClickListener { navigateBack() }
        findViewById<View>(R.id.btnCancelTransfer).setOnClickListener   { resetTransmission() }
        findViewById<View>(R.id.btnFinishedDone).setOnClickListener     { resetTransmission() }
        btnPermissionSettings.setOnClickListener { checkAndRequestPermissions() }
        btnSettings.setOnClickListener           { showSettingsDialog() }
    }

    // ─── Settings dialog ──────────────────────────────────────────
    private fun showSettingsDialog() {
        val options = mutableListOf(
            "📜 View Transfer History",
            "🧹 Clear Transfer History",
            "✨ Interactive Tutorial",
            "⬆ Check for Updates",
            "ℹ App Info",
            "🔁 Toggle Wi‑Fi Transfer"
        )
        if (isDevModeEnabled()) options.add("🛠 Developer Options")
        AlertDialog.Builder(this)
            .setTitle("⚙ Settings")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showHistoryDialog()
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Clear History")
                            .setMessage("Are you sure you want to clear all transfer history?")
                            .setPositiveButton("Yes") { _, _ ->
                                TransferHistoryManager.clearHistory(this)
                                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("No", null)
                            .show()
                    }
                    2 -> showTutorial()
                    3 -> checkForUpdates()
                    4 -> showAboutDialog()
                    5 -> showToggleWifiDialog()
                    6 -> showDeveloperOptionsDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showTutorial() {
        val titles = arrayOf(
            "Pick what you want to do",
            "Choose files and send",
            "Stay offline. Share fast."
        )
        val descriptions = arrayOf(
            "Tap Share to choose files, or Receive to make this phone ready for an incoming transfer.",
            "Select up to 10 files. ANRO-BEAM finds the nearby device and shows transfer progress as your files move.",
            "Transfers use a direct Wi‑Fi connection between devices. No mobile data or internet connection is needed."
        )
        var page = 0

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val illustration = TutorialIllustrationView(this)
        panel.addView(illustration, LinearLayout.LayoutParams(-1, dp(190)))

        val progress = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(27, 128, 190))
            setPadding(0, dp(16), 0, dp(5))
        }
        panel.addView(progress)

        val title = TextView(this).apply {
            textSize = 21f
            setTextColor(Color.rgb(18, 38, 67))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        }
        panel.addView(title)

        val description = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(82, 98, 119))
            gravity = Gravity.START
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        panel.addView(description)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Welcome to ANRO-BEAM")
            .setView(panel)
            .setNegativeButton("Skip", null)
            .setPositiveButton("Next", null)
            .create()

        dialog.setOnDismissListener {
            prefs().edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()
        }
        dialog.setOnShowListener {
            val nextButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }

            fun showPage() {
                progress.text = "STEP ${page + 1} OF ${titles.size}   ${"● ".repeat(page + 1)}${"○ ".repeat(titles.size - page - 1)}"
                title.text = titles[page]
                description.text = descriptions[page]
                illustration.setTutorialStep(page)
                illustration.alpha = 0.65f
                illustration.translationY = dp(7).toFloat()
                illustration.animate().alpha(1f).translationY(0f).setDuration(260L).start()
                nextButton.text = if (page == titles.lastIndex) "Start sharing" else "Next"
            }

            nextButton.setOnClickListener {
                if (page < titles.lastIndex) {
                    page++
                    showPage()
                } else {
                    prefs().edit().putBoolean(KEY_TUTORIAL_SEEN, true).apply()
                    dialog.dismiss()
                }
            }
            showPage()
        }
        dialog.show()
    }

    private fun checkForUpdates() {
        if (updateCheckInProgress) return
        updateCheckInProgress = true

        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val checking = AlertDialog.Builder(this)
            .setTitle("Checking for updates")
            .setView(spinner)
            .setCancelable(false)
            .create()
        checking.show()

        Thread {
            try {
                val connection = URL(UPDATE_MANIFEST_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                val update = try {
                    if (connection.responseCode !in 200..299) {
                        throw java.io.IOException("Update server returned ${connection.responseCode}")
                    }
                    val manifest = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val apkUrl = manifest.getString("apkUrl")
                    val parsedUrl = Uri.parse(apkUrl)
                    if (parsedUrl.scheme != "https" || parsedUrl.host != "github.com") {
                        throw java.io.IOException("The update link is not a trusted HTTPS GitHub link")
                    }
                    val checksum = manifest.getString("sha256").lowercase()
                    if (!checksum.matches(Regex("[a-f0-9]{64}"))) {
                        throw java.io.IOException("The update checksum is missing or invalid")
                    }
                    AppUpdate(
                        versionCode = manifest.getInt("versionCode"),
                        versionName = manifest.getString("versionName"),
                        apkUrl = apkUrl,
                        sha256 = checksum,
                        releaseNotes = manifest.optString("releaseNotes", "")
                    )
                } finally {
                    connection.disconnect()
                }

                runOnUiThread {
                    updateCheckInProgress = false
                    checking.dismiss()
                    if (update.versionCode <= BuildConfig.VERSION_CODE) {
                        AlertDialog.Builder(this)
                            .setTitle("You're up to date")
                            .setMessage("ANRO-BEAM v${BuildConfig.VERSION_NAME} is the latest version.")
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        val notes = update.releaseNotes.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty()
                        AlertDialog.Builder(this)
                            .setTitle("ANRO-BEAM ${update.versionName} is ready")
                            .setMessage("Download and install the update now?$notes")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Update") { _, _ -> downloadUpdate(update) }
                            .show()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateCheckInProgress = false
                    checking.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Couldn't check for updates")
                        .setMessage("Check your internet connection and try again. ${error.message.orEmpty()}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun downloadUpdate(update: AppUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallUnknownPackages()) {
            updateWaitingForPermission = update
            AlertDialog.Builder(this)
                .setTitle("Allow ANRO-BEAM updates")
                .setMessage("Android needs your permission before ANRO-BEAM can install an update. Allow installs from this app, then the update will continue.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                    installPermissionLauncher.launch(settingsIntent)
                }
                .show()
            return
        }

        val status = TextView(this).apply {
            text = "Downloading the verified update…"
            setPadding(0, 0, 0, dp(12))
            textSize = 14f
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(status)
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
        }
        val downloading = AlertDialog.Builder(this)
            .setTitle("Updating ANRO-BEAM")
            .setView(content)
            .setCancelable(false)
            .create()
        downloading.show()

        Thread {
            val folder = File(cacheDir, "updates")
            val temporaryApk = File(folder, "anrobeam-update.apk.part")
            val finalApk = File(folder, "anrobeam-update.apk")
            try {
                if (!folder.exists() && !folder.mkdirs()) throw java.io.IOException("Could not prepare update storage")
                val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode !in 200..299) {
                        throw java.io.IOException("Download failed (${connection.responseCode})")
                    }
                    val totalBytes = connection.contentLengthLong
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        FileOutputStream(temporaryApk).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                downloaded += count
                                if (totalBytes > 0L) {
                                    val percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    runOnUiThread {
                                        progressBar.isIndeterminate = false
                                        progressBar.progress = percent
                                        status.text = "Downloading update… $percent%"
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                val actualHash = digestHex(digest.digest())
                if (!actualHash.equals(update.sha256, ignoreCase = true)) {
                    throw java.io.IOException("The downloaded file failed its security check")
                }
                if (temporaryApk.length() < 512_000L) throw java.io.IOException("The downloaded APK is incomplete")
                if (finalApk.exists() && !finalApk.delete()) throw java.io.IOException("Could not replace the previous download")
                if (!temporaryApk.renameTo(finalApk)) throw java.io.IOException("Could not finish saving the update")

                runOnUiThread {
                    downloading.dismiss()
                    installDownloadedUpdate(finalApk)
                }
            } catch (error: Exception) {
                temporaryApk.delete()
                runOnUiThread {
                    downloading.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Update download failed")
                        .setMessage(error.message ?: "Please try again later.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun canInstallUnknownPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun installDownloadedUpdate(apk: File) {
        if (!apk.isFile) {
            Toast.makeText(this, "The update file is missing. Please download it again.", Toast.LENGTH_LONG).show()
            return
        }
        if (!canInstallUnknownPackages()) {
            apkWaitingForInstallPermission = apk
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            installPermissionLauncher.launch(settingsIntent)
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(installIntent)
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Couldn't open the installer")
                .setMessage(error.message ?: "Please try the update again.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun digestHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte -> "%02x".format(byte) }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isDevModeEnabled(): Boolean = prefs().getBoolean(KEY_DEV_MODE, false)

    private fun setDevModeEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_DEV_MODE, enabled).apply()
        Toast.makeText(this, if (enabled) "Developer mode enabled" else "Developer mode disabled", Toast.LENGTH_SHORT).show()
    }

    private fun isWifiPreferred(): Boolean = prefs().getBoolean(KEY_USE_WIFI, true)

    private fun setWifiPreferred(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_USE_WIFI, enabled).apply()
        Toast.makeText(this, if (enabled) "Wi‑Fi transfer preferred" else "Wi‑Fi transfer disabled", Toast.LENGTH_SHORT).show()
    }

    private fun showToggleWifiDialog() {
        val current = isWifiPreferred()
        val chk = CheckBox(this).apply { text = "Prefer Wi‑Fi Direct for transfers"; isChecked = current; setPadding(40,20,40,20) }
        AlertDialog.Builder(this)
            .setTitle("Wi‑Fi Transfer")
            .setView(chk)
            .setPositiveButton("Save") { _, _ -> setWifiPreferred(chk.isChecked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeveloperOptionsDialog() {
        val devEnabled = isDevModeEnabled()
        val options = mutableListOf<String>()
        options.add(if (devEnabled) "☑ Disable Developer Mode" else "☐ Enable Developer Mode")
        if (devEnabled) {
            options.add("📲 Simulate Incoming Transfer Request")
            options.add("📊 Simulate Transfer Progress")
            options.add("✅ Simulate Transfer Completed")
            options.add("⚠️ Simulate Transfer Error")
            options.add("🚫 Simulate Transfer Decline")
            options.add("🛑 Simulate Transfer Cancel")
            options.add("📝 Toggle Verbose Logging")
            options.add("📦 Simulate Large Transfer")
            options.add("📁 Simulate Many Files")
            options.add("🔌 Simulate Interrupted Transfer")
        }

        AlertDialog.Builder(this)
            .setTitle("🔧 Developer Options")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.contains("Enable Developer Mode") || choice.contains("Disable Developer Mode") -> setDevModeEnabled(!devEnabled)
                    choice.contains("Simulate Incoming Transfer") -> simulateIncomingTransfer()
                    choice.contains("Simulate Transfer Progress") -> simulateTransferProgress()
                    choice.contains("Simulate Transfer Completed") -> simulateTransferCompleted()
                    choice.contains("Simulate Transfer Error") -> simulateTransferError()
                    choice.contains("Simulate Transfer Decline") -> simulateTransferDecline()
                    choice.contains("Simulate Transfer Cancel") -> simulateTransferCancel()
                    choice.contains("Toggle Verbose Logging") -> toggleVerboseLogging()
                    choice.contains("Simulate Large Transfer") -> simulateLargeTransfer()
                    choice.contains("Simulate Many Files") -> simulateManyFiles()
                    choice.contains("Simulate Interrupted Transfer") -> simulateInterruptedTransfer()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun isVerboseEnabled(): Boolean = prefs().getBoolean(KEY_VERBOSE_LOG, false)

    private fun toggleVerboseLogging() {
        val enabled = !isVerboseEnabled()
        prefs().edit().putBoolean(KEY_VERBOSE_LOG, enabled).apply()
        Toast.makeText(this, if (enabled) "Verbose logging enabled" else "Verbose logging disabled", Toast.LENGTH_SHORT).show()
    }

    private fun simulateLargeTransfer() {
        val intent = Intent(BeamTransferService.ACTION_TRANSFER_REQUEST).apply {
            putExtra("sender", "Dev-Simulated Phone (LargeFile)")
            putExtra("filesCount", 1)
            putExtra("totalSize", 4_800_000_000L) // ~4.8 GB
            putExtra("filesMeta", "huge_backup.img:4800000000")
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Simulating large incoming transfer...", Toast.LENGTH_SHORT).show()
    }

    private fun simulateManyFiles() {
        // Build a filesMeta string with many small files
        val parts = mutableListOf<String>()
        for (i in 1..25) parts.add("file_$i.txt:1024")
        val meta = parts.joinToString(";")
        val intent = Intent(BeamTransferService.ACTION_TRANSFER_REQUEST).apply {
            putExtra("sender", "Dev-Simulated Phone (ManyFiles)")
            putExtra("filesCount", 25)
            putExtra("totalSize", 25L * 1024L)
            putExtra("filesMeta", meta)
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Simulating incoming transfer with many files...", Toast.LENGTH_SHORT).show()
    }

    private fun simulateInterruptedTransfer() {
        Thread {
            for (p in 0..60 step 10) {
                val intent = Intent(BeamTransferService.ACTION_TRANSFER_PROGRESS).apply {
                    putExtra("fileName", "unstable_video.mp4")
                    putExtra("fileIndex", 0)
                    putExtra("filesCount", 1)
                    putExtra("percentage", p)
                    putExtra("speed", 5.0)
                    putExtra("eta", "${(100 - p) / 10}s remaining")
                }
                sendBroadcast(intent)
                Thread.sleep(300)
            }
            val err = Intent(BeamTransferService.ACTION_TRANSFER_ERROR).apply {
                putExtra("message", "Connection lost (simulated)")
            }
            sendBroadcast(err)
        }.start()
        Toast.makeText(this, "Simulating interrupted transfer...", Toast.LENGTH_SHORT).show()
    }

    private fun simulateIncomingTransfer() {
        val intent = Intent(BeamTransferService.ACTION_TRANSFER_REQUEST).apply {
            putExtra("sender", "Dev-Simulated Phone (Pixel 8 Pro)")
            putExtra("filesCount", 2)
            putExtra("totalSize", 145_700_000L) // ~145.7 MB
            putExtra("filesMeta", "presentation_deck.pdf:45700000;sample_video.mp4:100000000")
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Simulating incoming transfer request...", Toast.LENGTH_SHORT).show()
    }

    private fun simulateTransferProgress() {
        Thread {
            for (p in 0..100 step 10) {
                val intent = Intent(BeamTransferService.ACTION_TRANSFER_PROGRESS).apply {
                    putExtra("fileName", "sample_video.mp4")
                    putExtra("fileIndex", 0)
                    putExtra("filesCount", 2)
                    putExtra("percentage", p)
                    putExtra("speed", 18.5)
                    putExtra("eta", "${(100 - p) / 10}s remaining")
                }
                sendBroadcast(intent)
                Thread.sleep(400)
            }
        }.start()
    }

    private fun simulateTransferError() {
        val intent = Intent(BeamTransferService.ACTION_TRANSFER_ERROR).apply {
            putExtra("message", "Simulated transfer error: I/O failure")
        }
        sendBroadcast(intent)
        Toast.makeText(this, "Simulating transfer error...", Toast.LENGTH_SHORT).show()
    }

    private fun simulateTransferCompleted() {
        val intent = Intent(BeamTransferService.ACTION_TRANSFER_COMPLETE).apply {
            putExtra("filesCount", 2)
            putExtra("firstFileName", "presentation_deck.pdf")
        }
        sendBroadcast(intent)
    }

    private fun simulateTransferDecline() {
        // Simulate user declining an incoming transfer request
        val intent = Intent(this, BeamTransferService::class.java).apply {
            action = BeamTransferService.ACTION_DECLINE_TRANSFER
        }
        // Use startService to trigger service handling
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Simulated transfer decline", Toast.LENGTH_SHORT).show()
    }

    private fun simulateTransferCancel() {
        // Simulate user cancelling an active transfer
        val intent = Intent(this, BeamTransferService::class.java).apply {
            action = BeamTransferService.ACTION_CANCEL_TRANSFER
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Simulated transfer cancel", Toast.LENGTH_SHORT).show()
    }


    private fun showAboutDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_about, null)
        val txtVersion = view.findViewById<TextView>(R.id.txtAboutVersion)
        txtVersion.text = getString(R.string.app_version)

        // hidden tap activation: 7 taps to enable dev mode
        var tapCount = 0
        txtVersion.setOnClickListener {
            tapCount++
            if (tapCount >= 7) {
                setDevModeEnabled(true)
            }
        }

        val msg = view.findViewById<TextView>(R.id.txtAboutDetails)
        msg.text = buildString {
            append("Transport\n")
            append("Wi‑Fi Direct (maximum speed when available)")
            append("\n\n")
            append("Transfer Buffer\n1 MB per chunk")
            append("\n\n")
            append("Max files per batch\n10")
            append("\n\n")
            append("Max total batch size\n5 GB")
        }

        AlertDialog.Builder(this)
            .setTitle("ℹ App Info")
            .setView(view)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showHistoryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val layoutList = dialogView.findViewById<android.widget.LinearLayout>(R.id.layoutHistoryList)
        val txtNoHistory = dialogView.findViewById<TextView>(R.id.txtNoHistory)
        val btnClose = dialogView.findViewById<TextView>(R.id.btnDialogClose)
        val btnClear = dialogView.findViewById<View>(R.id.btnClearHistory)

        btnClose.setOnClickListener { dialog.dismiss() }
        
        fun populateList() {
            layoutList.removeAllViews()
            val history = TransferHistoryManager.getHistory(this)
            if (history.isEmpty()) {
                txtNoHistory.visibility = View.VISIBLE
                layoutList.addView(txtNoHistory)
            } else {
                txtNoHistory.visibility = View.GONE
                val sdf = java.text.SimpleDateFormat("HH:mm · dd MMM", java.util.Locale.getDefault())
                for (entry in history) {
                    val itemView = layoutInflater.inflate(R.layout.item_history, layoutList, false)
                    val txtIcon = itemView.findViewById<TextView>(R.id.txtHistoryIcon)
                    val txtDir = itemView.findViewById<TextView>(R.id.txtHistoryDir)
                    val txtStatus = itemView.findViewById<TextView>(R.id.txtHistoryStatus)
                    val txtFiles = itemView.findViewById<TextView>(R.id.txtHistoryFiles)
                    val txtSize = itemView.findViewById<TextView>(R.id.txtHistorySize)
                    val txtTime = itemView.findViewById<TextView>(R.id.txtHistoryTime)

                    val isSend = entry.direction == "SEND"
                    txtIcon.text = if (isSend) "📤" else "📥"
                    txtDir.text = if (isSend) "Sent" else "Received"
                    
                    txtStatus.text = entry.status
                    when (entry.status) {
                        "SUCCESS" -> {
                            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
                        }
                        "DECLINED" -> {
                            txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                        }
                        else -> {
                            txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                        }
                    }

                    txtFiles.text = entry.fileNames.joinToString(", ")
                    txtSize.text = formatFileSize(entry.totalSize)
                    txtTime.text = sdf.format(java.util.Date(entry.timestamp))

                    layoutList.addView(itemView)
                }
            }
        }

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear transfer history?")
                .setPositiveButton("Yes") { _, _ ->
                    TransferHistoryManager.clearHistory(this)
                    populateList()
                }
                .setNegativeButton("No", null)
                .show()
        }

        populateList()
        dialog.show()
    }

    private fun showIncomingTransferDialog(
        sender: String,
        filesCount: Int,
        totalSize: Long,
        filesList: List<Pair<String, Long>>
    ) {
        incomingDialog?.dismiss()

        val msg = buildString {
            append("From:\n$sender\n\n")
            if (filesList.size == 1) {
                append("File:\n${filesList[0].first}\n\n")
            } else {
                append("Files (${filesList.size}):\n")
                filesList.forEach { append("· ${it.first} (${formatFileSize(it.second)})\n") }
                append("\n")
            }
            append("Total Size:\n${formatFileSize(totalSize)}")
        }

        incomingDialog = AlertDialog.Builder(this)
            .setTitle("Incoming Files")
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                val acceptIntent = Intent(this, BeamTransferService::class.java).apply {
                    action = BeamTransferService.ACTION_ACCEPT_TRANSFER
                }
                startService(acceptIntent)
            }
            .setNegativeButton("Decline") { _, _ ->
                val declineIntent = Intent(this, BeamTransferService::class.java).apply {
                    action = BeamTransferService.ACTION_DECLINE_TRANSFER
                }
                startService(declineIntent)
                resetTransmission()
            }
            .show()
    }

    // ═════════════════════════════════════════════════════════════
    // NAVIGATION
    // ═════════════════════════════════════════════════════════════

    private fun navigateToShareHome()   { updateUiState(AppState.SHARE_HOME) }

    private fun navigateToReceiveHome() {
        updateUiState(AppState.RECEIVE_HOME)
        startReceiverService()
    }

    private fun startReceiverService() {
        val intent = Intent(this, BeamTransferService::class.java).apply {
            action = BeamTransferService.ACTION_START_RECEIVE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun navigateBack() { resetTransmission() }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when (currentState) {
                        AppState.SHARE_HOME, AppState.RECEIVE_HOME -> navigateBack()
                        AppState.CONNECTING, AppState.TRANSFERRING -> { /* locked — use Cancel */ }
                        else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                    }
                }
            })
    }

    // ═════════════════════════════════════════════════════════════
    // INTENT HANDLING (Share Sheet)
    // ═════════════════════════════════════════════════════════════

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("START_RECEIVE", false)) {
            navigateToReceiveHome()
            return
        }

        if (intent.action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            uri?.let { navigateToShareHome(); handleSelectedFiles(listOf(it)) }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris?.let { navigateToShareHome(); handleSelectedFiles(it) }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // FILE SELECTION  (max 10 files / 5 GB total, auto-start)
    // ═════════════════════════════════════════════════════════════

    private fun handleSelectedFiles(uris: List<Uri>) {
        selectedFiles.clear()
        if (uris.isEmpty()) return

        val targetUris = if (uris.size > MAX_FILES) {
            Toast.makeText(this,
                "Maximum $MAX_FILES files allowed. Only the first $MAX_FILES will be sent.",
                Toast.LENGTH_LONG).show()
            uris.take(MAX_FILES)
        } else uris

        for (uri in targetUris) {
            var name = "Unknown"
            var size = 0L
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (ni != -1) name = cursor.getString(ni)
                if (si != -1) size = cursor.getLong(si)
                cursor.close()
            } else {
                name = uri.lastPathSegment ?: "File"
            }
            selectedFiles.add(FileInfo(uri, name, size))
        }

        if (selectedFiles.isNotEmpty()) {
            val totalSize = selectedFiles.sumOf { it.size }
            // Enforce 5 GB total cap
            if (totalSize > MAX_BATCH_BYTES) {
                Toast.makeText(this,
                    "Total size exceeds 5 GB limit. Please select fewer/smaller files.",
                    Toast.LENGTH_LONG).show()
                selectedFiles.clear()
                return
            }
            if (selectedFiles.size == 1) {
                btnChooseFileText.text   = "File Selected ✓"
                selectedFileSubtext.text = "${selectedFiles[0].name}  ·  ${formatFileSize(selectedFiles[0].size)}"
            } else {
                btnChooseFileText.text   = "${selectedFiles.size} Files Selected ✓"
                selectedFileSubtext.text = "${selectedFiles.size} files  ·  Total: ${formatFileSize(totalSize)}"
            }
            // Show Send button instead of auto-starting
            findViewById<View>(R.id.btnSendFile).visibility = View.VISIBLE
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SENDER FLOW — BLE scan → connect → Transfer
    // ═════════════════════════════════════════════════════════════

    private fun onSendTapped() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Please choose a file first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasRequiredPermissions()) { checkAndRequestPermissions(); return }

        isTransmissionActive = true
        useWifiDirect = isWifiPreferred()
        updateUiState(AppState.CONNECTING)
        txtConnectingState.text = "Tap phones together to share\n(Ensure both devices have NFC enabled)"

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not supported on this device. Trying Wi-Fi Direct scanning directly.", Toast.LENGTH_LONG).show()
            initWifiDirectAsSender()
        } else {
            if (!nfcAdapter!!.isEnabled) {
                Toast.makeText(this, "Please enable NFC on this device to share via tap.", Toast.LENGTH_LONG).show()
            }
            enableNfcForegroundDispatch()
        }
    }

    private fun enableNfcForegroundDispatch() {
        try {
            nfcAdapter?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
            Log.d("AnroBeam_NFC", "NFC Foreground Dispatch enabled")
        } catch (e: Exception) {
            Log.e("AnroBeam_NFC", "Failed to enable NFC foreground dispatch", e)
        }
    }

    private fun disableNfcForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
            Log.d("AnroBeam_NFC", "NFC Foreground Dispatch disabled")
        } catch (e: Exception) {
            Log.e("AnroBeam_NFC", "Failed to disable NFC foreground dispatch", e)
        }
    }

    private fun handleNfcTag(tag: Tag) {
        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.w("AnroBeam_NFC", "Tag is not IsoDep compatible")
            return
        }
        Thread {
            try {
                isoDep.connect()
                val selectAidCommand = byteArrayOf(
                    0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte(),
                    0x08.toByte(),
                    0xF0.toByte(), 0x41.toByte(), 0x4E.toByte(), 0x52.toByte(), 0x4F.toByte(), 0x42.toByte(), 0x45.toByte(), 0x41.toByte()
                )
                Log.d("AnroBeam_NFC", "Sending Select AID command...")
                val response = isoDep.transceive(selectAidCommand)
                isoDep.close()

                if (response != null && response.size >= 14) {
                    val payload = String(response.sliceArray(0 until 14), Charsets.UTF_8)
                    if (payload == "ANROBEAM_READY") {
                        Log.d("AnroBeam_NFC", "NFC Handshake Success!")
                        runOnUiThread {
                            txtConnectingState.text = "NFC Tapped! Connecting via Wi-Fi Direct..."
                            disableNfcForegroundDispatch()
                            initWifiDirectAsSender()
                        }
                    } else {
                        Log.w("AnroBeam_NFC", "Invalid payload response: $payload")
                    }
                } else {
                    Log.w("AnroBeam_NFC", "Invalid or empty NFC response")
                }
            } catch (e: Exception) {
                Log.e("AnroBeam_NFC", "Error communicating via NFC", e)
            }
        }.start()
    }

    private fun stopBleScan() {
        // BLE scanning removed, using NFC tap-to-share
    }

    // ═════════════════════════════════════════════════════════════
    // RECEIVER FLOW — BT server + BLE advertise
    // ═════════════════════════════════════════════════════════════

    private fun startBtServerAndAdvertise() {
        if (isTransmissionActive) return
        isTransmissionActive = true

        startBleAdvertising()

        btServerThread = Thread {
            try {
                val server = bluetoothAdapter
                    ?.listenUsingRfcommWithServiceRecord("ANRO-BEAM", RFCOMM_UUID)
                serverSocket = server
                Log.d("AnroBeam", "BT server listening…")
                val socket = server?.accept()
                server?.close(); serverSocket = null
                stopBleAdvertising()

                if (socket != null && isTransmissionActive) {
                    activeSocket = socket
                    handleReceiverSocket(socket)
                }
            } catch (e: Exception) {
                Log.e("AnroBeam", "BT server error: ", e)
            }
        }.also { it.start() }

        // Also start Wi-Fi Direct group so we can handle large-file senders
        startWifiDirectGroup()
        runOnUiThread { txtReceiveStatus.text = "Ready to receive. Tap phones together." }
    }

    private fun startBleAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser ?: return
        val pUuid = ParcelUuid(BLE_SERVICE_UUID)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false).setTimeout(0).build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(pUuid)
            .addServiceData(pUuid, "ANROBEAM".toByteArray(Charsets.UTF_8))
            .build()
        try { advertiser.startAdvertising(settings, data, bleAdvertiseCallback) }
        catch (e: SecurityException) { Log.e("AnroBeam", "BLE advertise error: ", e) }
    }

    private fun stopBleAdvertising() {
        try { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(bleAdvertiseCallback) }
        catch (ignored: Exception) {}
    }

    // ═════════════════════════════════════════════════════════════
    // WI-FI DIRECT — RECEIVER SIDE (group owner / TCP server)
    // ═════════════════════════════════════════════════════════════

    private fun startWifiDirectGroup() {
        val manager = wifiP2pManager ?: return
        val channel = wifiP2pChannel ?: return
        try {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("AnroBeam", "Wi-Fi Direct group created (receiver is GO).")
                    isWifiDirectGroupOwner = true
                    // Start TCP server to accept large-file transfer
                    startWifiDirectTcpServer()
                }
                override fun onFailure(reason: Int) {
                    Log.w("AnroBeam", "Wi-Fi Direct group creation failed ($reason). Large-file transfers may not work.")
                }
            })
        } catch (e: SecurityException) {
            Log.e("AnroBeam", "Wi-Fi Direct security error: ", e)
        }
    }

    /** Receiver TCP server: listens for Wi-Fi Direct connections on WIFIP2P_PORT */
    private fun startWifiDirectTcpServer() {
        Thread {
            try {
                val tcpServer = ServerSocket()
                tcpServer.reuseAddress = true
                tcpServer.receiveBufferSize = 2_097_152 // 1 MB buffer
                tcpServer.bind(InetSocketAddress(WIFIP2P_PORT))
                Log.d("AnroBeam", "Wi-Fi Direct TCP server listening on port $WIFIP2P_PORT")
                val client = tcpServer.accept()   // blocks
                client.tcpNoDelay = true
                client.receiveBufferSize = 2_097_152 // 1 MB buffer
                client.sendBufferSize = 2_097_152 // 1 MB buffer
                tcpServer.close()
                Log.d("AnroBeam", "Wi-Fi Direct client connected: ${client.inetAddress}")
                handleWifiReceiverSocket(client.inputStream, client.outputStream)
                client.close()
            } catch (e: Exception) {
                if (isTransmissionActive) Log.e("AnroBeam", "Wi-Fi Direct TCP server error: ", e)
            }
        }.start()
    }

    // ═════════════════════════════════════════════════════════════
    // WI-FI DIRECT — SENDER SIDE (TCP client)
    // ═════════════════════════════════════════════════════════════

    /**
     * Start Wi-Fi Direct peer discovery.
     */
    private fun initWifiDirectAsSender() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) {
            Log.e("AnroBeam", "Wi-Fi Direct not available on this device")
            runOnUiThread {
                Toast.makeText(this,
                    "Wi-Fi Direct is not available on this device. Cannot transfer.",
                    Toast.LENGTH_LONG).show()
                resetTransmission()
            }
            return
        }

        runOnUiThread { txtConnectingState.text = "Searching for Wi-Fi Direct receiver…" }

        isDiscoveringPeers = true

        // Timeout: if no peers found in 20 seconds, show error
        mainHandler.postDelayed({
            if (isDiscoveringPeers) {
                isDiscoveringPeers = false
                runOnUiThread {
                    Toast.makeText(this,
                        "No Wi-Fi Direct receiver found nearby. Make sure the other device is in Receive Mode.",
                        Toast.LENGTH_LONG).show()
                    resetTransmission()
                }
            }
        }, 20_000)

        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("AnroBeam", "Wi-Fi Direct peer discovery started — waiting for PEERS_CHANGED broadcast")
                    runOnUiThread { txtConnectingState.text = "Scanning for receiver…" }
                }
                override fun onFailure(reason: Int) {
                    isDiscoveringPeers = false
                    Log.w("AnroBeam", "Wi-Fi Direct peer discovery failed ($reason)")
                    runOnUiThread {
                        Toast.makeText(this@ShareActivity,
                            "Wi-Fi Direct discovery failed ($reason). Make sure Wi-Fi is enabled and retry.",
                            Toast.LENGTH_LONG).show()
                        resetTransmission()
                    }
                }
            })
        } catch (e: SecurityException) {
            isDiscoveringPeers = false
            Log.e("AnroBeam", "Wi-Fi Direct security: ", e)
            runOnUiThread {
                Toast.makeText(this, "Wi-Fi Direct permission denied.", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    private fun connectWifiDirectPeer(
        device: WifiP2pDevice,
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel
    ) {
        runOnUiThread { txtConnectingState.text = "Connecting to ${device.deviceName ?: device.deviceAddress}…" }
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("AnroBeam", "Wi-Fi Direct connect requested to ${device.deviceAddress}")
                    // Wait for connection to form, then open TCP
                    // The GO always has IP 192.168.49.1 on Android — use it directly
                    mainHandler.postDelayed({
                        manager.requestConnectionInfo(channel) { info ->
                            if (info?.groupFormed == true) {
                                val goAddr = if (info.isGroupOwner) "127.0.0.1" else "192.168.49.1"
                                Log.d("AnroBeam", "Group formed. isGO=${info.isGroupOwner}, connecting to $goAddr")
                                openWifiDirectTcpAndSend(goAddr)
                            } else {
                                Log.w("AnroBeam", "Group not formed after 3s, retrying requestConnectionInfo…")
                                mainHandler.postDelayed({
                                    manager.requestConnectionInfo(channel) { info2 ->
                                        if (info2?.groupFormed == true) {
                                            val goAddr = if (info2.isGroupOwner) "127.0.0.1" else "192.168.49.1"
                                            openWifiDirectTcpAndSend(goAddr)
                                        } else {
                                            runOnUiThread {
                                                Toast.makeText(this@ShareActivity,
                                                    "Wi-Fi Direct group failed to form. Please retry.",
                                                    Toast.LENGTH_LONG).show()
                                                resetTransmission()
                                            }
                                        }
                                    }
                                }, 4000)
                            }
                        }
                    }, 3000)
                }
                override fun onFailure(reason: Int) {
                    Log.w("AnroBeam", "Wi-Fi Direct connect failed ($reason)")
                    runOnUiThread {
                        Toast.makeText(this@ShareActivity,
                            "Wi-Fi Direct connect failed ($reason). Please retry.",
                            Toast.LENGTH_LONG).show()
                        resetTransmission()
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("AnroBeam", "Wi-Fi Direct connect security: ", e)
            runOnUiThread {
                Toast.makeText(this, "Wi-Fi Direct permission denied.", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    private fun openWifiDirectTcpAndSend(targetAddr: String) {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d("AnroBeam", "Opening TCP to $targetAddr:$WIFIP2P_PORT")
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.sendBufferSize = 4 * 1024 * 1024
                sock.receiveBufferSize = 4 * 1024 * 1024
                sock.bind(null)
                sock.connect(InetSocketAddress(targetAddr, WIFIP2P_PORT), 15_000)
                runOnUiThread { txtConnectingState.text = "Wi-Fi Direct connected — sending…" }
                handleWifiSenderSocket(sock.inputStream, sock.outputStream)
                sock.close()
            } catch (e: Exception) {
                Log.e("AnroBeam", "Wi-Fi Direct TCP connect failed: ", e)
                runOnUiThread {
                    Toast.makeText(this, "Wi-Fi Direct failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    resetTransmission()
                }
            }
        }.start()
    }

    // ═════════════════════════════════════════════════════════════
    // DATA TRANSFER — SENDER SIDE (BT RFCOMM)
    // ═════════════════════════════════════════════════════════════

    private fun handleSenderSocket(socket: BluetoothSocket) {
        val out = java.io.BufferedOutputStream(socket.outputStream, 262_144)
        val inp = java.io.BufferedInputStream(socket.inputStream,  262_144)
        try {
            sendFilesOverStreams(inp, out)
            socket.close()
        } catch (e: Exception) {
            Log.e("AnroBeam", "Sender TX error: ", e)
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Send failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DATA TRANSFER — SENDER SIDE (Wi-Fi Direct)
    // ═════════════════════════════════════════════════════════════

    private fun handleWifiSenderSocket(rawInp: InputStream, rawOut: OutputStream) {
        val out = java.io.BufferedOutputStream(rawOut, 2_097_152)
        val inp = java.io.BufferedInputStream(rawInp, 2_097_152)
        try {
            sendFilesOverStreams(inp, out)
        } catch (e: Exception) {
            Log.e("AnroBeam", "Wi-Fi sender TX error: ", e)
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Send failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
            try {
                TransferHistoryManager.addEntry(
                    this, HistoryEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        direction = "SEND",
                        fileNames = selectedFiles.map { it.name },
                        totalSize = selectedFiles.sumOf { it.size },
                        status = "FAILED"
                    )
                )
            } catch (ignored: Exception) {}
        }
    }

    /** Core send logic used by both BT and Wi-Fi Direct sender paths */
    private fun sendFilesOverStreams(inp: InputStream, out: OutputStream) {
        val senderName = getLocalBtName()
        val filesMeta  = selectedFiles.joinToString(";") { "${it.name}:${it.size}" }
        val header     = "ANROBEAM_HEADER|${selectedFiles.size}|${senderName}|${filesMeta}\n"
        out.write(header.toByteArray(Charsets.UTF_8)); out.flush()

        // Wait for ACCEPT / DECLINE
        runOnUiThread { txtConnectingState.text = "Waiting for receiver to accept…" }
        val respBuf = ByteArray(32)
        val n = inp.read(respBuf)
        val response = if (n > 0) String(respBuf, 0, n, Charsets.UTF_8).trim() else ""
        if (response != "ACCEPT") {
            runOnUiThread {
                Toast.makeText(this@ShareActivity, "Transfer declined by receiver.", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
            try {
                TransferHistoryManager.addEntry(
                    this, HistoryEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        direction = "SEND",
                        fileNames = selectedFiles.map { it.name },
                        totalSize = selectedFiles.sumOf { it.size },
                        status = "DECLINED"
                    )
                )
            } catch (ignored: Exception) {}
            return
        }

        for ((index, file) in selectedFiles.withIndex()) {
            runOnUiThread {
                updateUiState(AppState.TRANSFERRING)
                txtTransferDirection.text = "Sending (${index + 1} of ${selectedFiles.size}):"
                txtTransferFileName.text  = file.name
                txtTransferFileSize.text  = formatFileSize(file.size)
                progressBarTransfer.progress = 0
            }

            val fileHeader = "FILE_INFO|${file.name}|${file.size}\n"
            out.write(fileHeader.toByteArray(Charsets.UTF_8)); out.flush()

            val fileStream = java.io.BufferedInputStream(
                contentResolver.openInputStream(file.uri)!!, 2_097_152)
            streamBytes(fileStream, out, file.size, isReceiver = false)
            fileStream.close(); out.flush()

            val confirmBuf = ByteArray(32)
            val nRead = inp.read(confirmBuf)
            val confirm = if (nRead > 0) String(confirmBuf, 0, nRead, Charsets.UTF_8).trim() else ""
            if (confirm != "FILE_DONE") {
                throw java.io.IOException("Receiver failed to acknowledge file ($confirm)")
            }
        }

        val finalBuf = ByteArray(32)
        val nFinal = inp.read(finalBuf)
        Log.d("AnroBeam", "Final confirmation: ${if (nFinal > 0) String(finalBuf, 0, nFinal, Charsets.UTF_8).trim() else "<none>"}")

        if (isTransmissionActive) runOnUiThread {
            updateUiState(AppState.FINISHED)
            txtFinishedMessage.text = if (selectedFiles.size == 1)
                "File sent successfully!" else "All ${selectedFiles.size} files sent!"
        }

        try {
            TransferHistoryManager.addEntry(
                this, HistoryEntry(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    direction = "SEND",
                    fileNames = selectedFiles.map { it.name },
                    totalSize = selectedFiles.sumOf { it.size },
                    status = "SUCCESS"
                )
            )
        } catch (ignored: Exception) {}
    }

    // ═════════════════════════════════════════════════════════════
    // DATA TRANSFER — RECEIVER SIDE (BT RFCOMM)
    // ═════════════════════════════════════════════════════════════

    private fun handleReceiverSocket(socket: BluetoothSocket) {
        val inp = java.io.BufferedInputStream(socket.inputStream,  262_144)
        val out = java.io.BufferedOutputStream(socket.outputStream, 262_144)
        try {
            receiveFilesOverStreams(inp, out) { socket.close() }
        } catch (e: Exception) {
            Log.e("AnroBeam", "Receiver socket error: ", e)
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Connection error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // DATA TRANSFER — RECEIVER SIDE (Wi-Fi Direct)
    // ═════════════════════════════════════════════════════════════

    private fun handleWifiReceiverSocket(rawInp: InputStream, rawOut: OutputStream) {
        val inp = java.io.BufferedInputStream(rawInp,  2_097_152)
        val out = java.io.BufferedOutputStream(rawOut, 2_097_152)
        try {
            receiveFilesOverStreams(inp, out) { /* TCP socket closed by caller */ }
        } catch (e: Exception) {
            Log.e("AnroBeam", "Wi-Fi receiver error: ", e)
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Receive error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    /** Core receive logic shared by BT and Wi-Fi Direct receiver paths */
    private fun receiveFilesOverStreams(
        inp: InputStream,
        out: OutputStream,
        closeSocket: () -> Unit
    ) {
        // 1. Read header
        val headerBuf = StringBuilder()
        val oneByte   = ByteArray(1)
        while (true) {
            val read = inp.read(oneByte)
            if (read == -1) break
            val ch = oneByte[0].toInt().toChar()
            if (ch == '\n') break
            headerBuf.append(ch)
        }
        val headerStr = headerBuf.toString().trim()
        if (!headerStr.startsWith("ANROBEAM_HEADER|")) throw Exception("Unexpected header: $headerStr")

        val parts      = headerStr.removePrefix("ANROBEAM_HEADER|").split("|")
        val fileCount  = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0
        val senderName = parts.getOrElse(1) { "ANRO-BEAM Device" }.ifBlank { "ANRO-BEAM Device" }
        val filesMeta  = parts.getOrElse(2) { "" }

        val filesList = mutableListOf<Pair<String, Long>>()
        if (filesMeta.isNotEmpty()) {
            filesMeta.split(";").forEach { item ->
                val ip = item.split(":")
                if (ip.size >= 2) filesList.add(Pair(ip[0], ip[1].toLongOrNull() ?: 0L))
            }
        }

        // 2. Show Accept / Decline dialog
        runOnUiThread {
            val totalSize = filesList.sumOf { it.second }
            val msg = buildString {
                append("From:\n$senderName\n\n")
                if (filesList.size == 1) {
                    append("File:\n${filesList[0].first}\n\n")
                } else {
                    append("Files (${filesList.size}):\n")
                    filesList.forEach { append("· ${it.first} (${formatFileSize(it.second)})\n") }
                    append("\n")
                }
                append("Total Size:\n${formatFileSize(totalSize)}")
            }
            AlertDialog.Builder(this@ShareActivity)
                .setTitle("Incoming Files")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Accept") { _, _ ->
                    Thread {
                        try {
                            out.write("ACCEPT\n".toByteArray(Charsets.UTF_8)); out.flush()
                            runOnUiThread {
                                updateUiState(AppState.TRANSFERRING)
                                txtTransferDirection.text = "Receiving (1 of $fileCount):"
                                txtTransferFileName.text  = filesList.getOrNull(0)?.first ?: ""
                                txtTransferFileSize.text  = formatFileSize(filesList.sumOf { it.second })
                                progressBarTransfer.progress = 0
                            }
                            receiveFiles(inp, out, filesList)
                            closeSocket()
                        } catch (e: Exception) {
                            Log.e("AnroBeam", "Accept/receive error: ", e)
                            resetTransmission()
                        }
                    }.start()
                }
                .setNegativeButton("Decline") { _, _ ->
                    Thread {
                        try { out.write("DECLINE\n".toByteArray(Charsets.UTF_8)); out.flush() }
                        catch (ignored: Exception) {}
                        resetTransmission()
                    }.start()
                }
                .show()
        }
    }

    private fun receiveFiles(
        inp: InputStream,
        out: OutputStream,
        filesList: List<Pair<String, Long>>
    ) {
        try {
            for ((index, fileInfo) in filesList.withIndex()) {
                val fileName = fileInfo.first
                val fileSize = fileInfo.second

                // Read FILE_INFO header
                val hb = StringBuilder()
                val ob = ByteArray(1)
                while (true) {
                    val r = inp.read(ob); if (r == -1) break
                    val c = ob[0].toInt().toChar(); if (c == '\n') break; hb.append(c)
                }
                if (!hb.toString().trim().startsWith("FILE_INFO|"))
                    throw Exception("Expected FILE_INFO, got: ${hb.toString().trim()}")

                runOnUiThread {
                    updateUiState(AppState.TRANSFERRING)
                    txtTransferDirection.text = "Receiving (${index + 1} of ${filesList.size}):"
                    txtTransferFileName.text  = fileName
                    txtTransferFileSize.text  = formatFileSize(fileSize)
                    progressBarTransfer.progress = 0
                }

                var fileOut: OutputStream? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    if (uri != null) fileOut = java.io.BufferedOutputStream(
                        contentResolver.openOutputStream(uri)!!, 2_097_152)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!dir.exists()) dir.mkdirs()
                    fileOut = java.io.BufferedOutputStream(FileOutputStream(File(dir, fileName)), 2_097_152)
                }
                if (fileOut == null) throw Exception("Cannot open output stream.")

                streamBytes(inp, fileOut, fileSize, isReceiver = true)
                fileOut.flush(); fileOut.close()

                out.write("FILE_DONE\n".toByteArray(Charsets.UTF_8)); out.flush()
            }

            out.write("DONE\n".toByteArray(Charsets.UTF_8)); out.flush()

            if (isTransmissionActive) runOnUiThread {
                updateUiState(AppState.FINISHED)
                txtFinishedMessage.text = if (filesList.size == 1)
                    "Saved to Downloads:\n${filesList[0].first}"
                else "Saved ${filesList.size} files to Downloads!"
            }
        } catch (e: Exception) {
            Log.e("AnroBeam", "Receive files error: ", e)
            runOnUiThread {
                Toast.makeText(this@ShareActivity,
                    "Download failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                resetTransmission()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // SHARED STREAMING  (256 KB chunks for max speed)
    // ═════════════════════════════════════════════════════════════

    private fun streamBytes(src: InputStream, dst: OutputStream, totalBytes: Long, isReceiver: Boolean) {
        val buffer = ByteArray(8_388_608)   // 8 MB chunks
        var totalTransferred = 0L
        var lastTime  = System.currentTimeMillis()
        var lastBytes = 0L

        while (isTransmissionActive) {
            if (isReceiver && totalBytes > 0 && totalTransferred >= totalBytes) break

            val toRead = if (isReceiver && totalBytes > 0) {
                val remaining = totalBytes - totalTransferred
                if (remaining < buffer.size) remaining.toInt() else buffer.size
            } else buffer.size

            val bytesRead = src.read(buffer, 0, toRead)
            if (bytesRead == -1) {
                if (isReceiver && totalBytes > 0 && totalTransferred < totalBytes)
                    throw java.io.IOException(
                        "Connection lost (received $totalTransferred of $totalBytes bytes).")
                break
            }

            dst.write(buffer, 0, bytesRead)
            totalTransferred += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastTime >= 500) {
                val elapsed  = (now - lastTime) / 1000.0
                val delta    = totalTransferred - lastBytes
                val speedBps = delta / elapsed
                val pct      = if (totalBytes > 0) ((totalTransferred * 100) / totalBytes).toInt() else 0
                val etaSec   = if (speedBps > 0) ((totalBytes - totalTransferred) / speedBps).toLong() else 0L

                runOnUiThread {
                    progressBarTransfer.progress = pct
                    txtTransferPercent.text = "$pct%"
                    txtTransferSpeed.text   = "%.2f MB/s".format(speedBps / (1024.0 * 1024.0))
                    txtTransferTimeRemaining.text = formatEta(etaSec)
                }
                lastTime  = now
                lastBytes = totalTransferred
            }
        }
        dst.flush()
    }

    // ═════════════════════════════════════════════════════════════
    // UI STATE MACHINE
    // ═════════════════════════════════════════════════════════════

    private fun updateUiState(state: AppState) {
        currentState = state
        listOf(layoutModeSelection, layoutShareHome, layoutReceiveHome,
            layoutConnecting, layoutTransfer, layoutFinished)
            .forEach { it.visibility = View.GONE }
        when (state) {
            AppState.MODE_SELECTION -> {
                layoutModeSelection.visibility = View.VISIBLE
                arrayOf(R.id.cardShareMode, R.id.cardReceiveMode).forEach { id ->
                    findViewById<View>(id)?.let { card ->
                        card.alpha = 0f
                        card.scaleX = 0.9f
                        card.scaleY = 0.9f
                        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400)
                            .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                    }
                }
            }
            AppState.SHARE_HOME     -> {
                layoutShareHome.visibility = View.VISIBLE
                findViewById<View>(R.id.cardChooseFile)?.let { card ->
                    card.alpha = 0f
                    card.scaleX = 0.9f
                    card.scaleY = 0.9f
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400)
                        .setInterpolator(android.view.animation.OvershootInterpolator()).start()
                }
            }
            AppState.RECEIVE_HOME   -> { layoutReceiveHome.visibility = View.VISIBLE; startReceiveAnimations() }
            AppState.CONNECTING     -> { layoutConnecting.visibility = View.VISIBLE;  startConnectingAnimations() }
            AppState.TRANSFERRING   -> {
                layoutTransfer.visibility = View.VISIBLE
                findViewById<View>(R.id.transferGlassCard)?.let { card ->
                    card.alpha = 0f
                    card.scaleX = 0.9f
                    card.scaleY = 0.9f
                    card.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                }
            }
            AppState.FINISHED       -> layoutFinished.visibility = View.VISIBLE
        }
        if (state != AppState.RECEIVE_HOME) stopReceiveAnimations()
        if (state != AppState.CONNECTING)   stopConnectingAnimations()
    }

    // ─── Animations ───────────────────────────────────────────────

    private val receiveRunnable = object : Runnable {
        override fun run() {
            if (currentState != AppState.RECEIVE_HOME) return
            pulse(receiveWave1, 1800)
            receiveWave2.postDelayed({ if (currentState == AppState.RECEIVE_HOME) pulse(receiveWave2, 1800) }, 900)
            animationHandler.postDelayed(this, 1800)
        }
    }

    private val connectRunnable = object : Runnable {
        override fun run() {
            if (currentState != AppState.CONNECTING) return
            pulse(waveCircle1, 1500)
            waveCircle2.postDelayed({ if (currentState == AppState.CONNECTING) pulse(waveCircle2, 1500) }, 750)
            animationHandler.postDelayed(this, 1500)
        }
    }

    private fun pulse(v: View, dur: Long) {
        v.scaleX = 0.5f; v.scaleY = 0.5f; v.alpha = 1f
        v.animate().scaleX(2f).scaleY(2f).alpha(0f).setDuration(dur).start()
    }

    private fun startReceiveAnimations()   { animationHandler.post(receiveRunnable) }
    private fun stopReceiveAnimations()    { animationHandler.removeCallbacks(receiveRunnable); try { receiveWave1.animate().cancel(); receiveWave2.animate().cancel() } catch (ignored: Exception) {} }
    private fun startConnectingAnimations(){ animationHandler.post(connectRunnable) }
    private fun stopConnectingAnimations() { animationHandler.removeCallbacks(connectRunnable); try { waveCircle1.animate().cancel(); waveCircle2.animate().cancel() } catch (ignored: Exception) {} }

    // ═════════════════════════════════════════════════════════════
    // RESET
    // ═════════════════════════════════════════════════════════════

    private fun resetTransmission() {
        isTransmissionActive = false
        stopBleScan()
        stopBleAdvertising()
        disableNfcForegroundDispatch()
        try { bluetoothAdapter?.cancelDiscovery() } catch (ignored: SecurityException) {}
        try { serverSocket?.close() }  catch (ignored: Exception) {}; serverSocket = null
        try { clientSocket?.close() }  catch (ignored: Exception) {}; clientSocket = null
        try { activeSocket?.close() }  catch (ignored: Exception) {}; activeSocket = null
        btServerThread?.interrupt(); btServerThread = null

        // Clean up receiver service
        try {
            val serviceIntent = Intent(this, BeamTransferService::class.java)
            stopService(serviceIntent)
        } catch (ignored: Exception) {}

        // Clean up Wi-Fi Direct group
        wifiP2pManager?.removeGroup(wifiP2pChannel, null)
        useWifiDirect = false
        wifiDirectGroupOwnerAddress = null
        isWifiDirectGroupOwner = false

        selectedFiles.clear()

        try {
            btnChooseFileText.text   = "Choose Files"
            selectedFileSubtext.text = "Tap to select up to 10 files (max 5 GB)"
            findViewById<View>(R.id.btnSendFile).visibility = View.GONE
        } catch (ignored: Exception) {}

        updateUiState(AppState.MODE_SELECTION)
        updateStatusIndicators()
    }

    // ═════════════════════════════════════════════════════════════
    // STATUS INDICATORS
    // ═════════════════════════════════════════════════════════════

    private fun updateStatusIndicators() {
        runOnUiThread {
            if (bluetoothAdapter == null) {
                txtBluetoothStatusVal.text = "Not Supported"
                txtBluetoothStatusVal.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            } else if (bluetoothAdapter?.isEnabled == false) {
                txtBluetoothStatusVal.text = "Disabled"
                txtBluetoothStatusVal.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
            } else {
                txtBluetoothStatusVal.text = "✓ Ready"
                txtBluetoothStatusVal.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═════════════════════════════════════════════════════════════

    private fun hasRequiredPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        else btnPermissionSettings.visibility = View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                btnPermissionSettings.visibility = View.GONE
                Toast.makeText(this, "All permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                btnPermissionSettings.visibility = View.VISIBLE
                Toast.makeText(this, "Some permissions missing. Tap Grant Permissions.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    // UTILITIES
    // ═════════════════════════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun getLocalBtName(): String = try {
        bluetoothAdapter?.name ?: Build.MODEL
    } catch (e: SecurityException) { Build.MODEL }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val g = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceAtMost(units.size - 1)
        return "%.2f %s".format(bytes / Math.pow(1024.0, g.toDouble()), units[g])
    }

    private fun formatEta(sec: Long) = when {
        sec <= 0 -> "Calculating…"
        sec < 60 -> "$sec sec"
        else     -> "${sec / 60}m ${sec % 60}s"
    }

    private fun getMimeType(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "*/*"
}
