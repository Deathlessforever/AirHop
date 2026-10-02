package com.team.vocalink.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognizerIntent
import android.view.HapticFeedbackConstants
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.team.vocalink.R
import com.team.vocalink.core.ChatMessage
import com.team.vocalink.core.DisasterPhraseCodebook
import com.team.vocalink.core.MessageStatus
import com.team.vocalink.core.ProtocolConstants
import android.net.Uri
import com.team.vocalink.alert.EmergencySurvivalGuide
import com.team.vocalink.alert.FlashlightStrobeManager
import com.team.vocalink.alert.SituationalTriageDialog
import com.team.vocalink.service.AirHopMeshService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class LanguageOption(
        val name: String,
        val nativeName: String,
        val langByte: Byte,
        val localeTag: String,
        val locale: Locale
    )

    private val supportedLanguages = listOf(
        LanguageOption("English", "English", ProtocolConstants.LANG_ENGLISH, "en-US", Locale.ENGLISH),
        LanguageOption("Kannada", "ಕನ್ನಡ", ProtocolConstants.LANG_KANNADA, "kn-IN", Locale("kn", "IN")),
        LanguageOption("Hindi", "हिंदी", ProtocolConstants.LANG_HINDI, "hi-IN", Locale("hi", "IN")),
        LanguageOption("Tamil", "தமிழ்", ProtocolConstants.LANG_TAMIL, "ta-IN", Locale("ta", "IN")),
        LanguageOption("Telugu", "తెలుగు", ProtocolConstants.LANG_TELUGU, "te-IN", Locale("te", "IN")),
        LanguageOption("Malayalam", "മലയാളം", ProtocolConstants.LANG_MALAYALAM, "ml-IN", Locale("ml", "IN")),
        LanguageOption("Bengali", "বাংলা", ProtocolConstants.LANG_BENGALI, "bn-IN", Locale("bn", "IN")),
        LanguageOption("Marathi", "मराठी", ProtocolConstants.LANG_MARATHI, "mr-IN", Locale("mr", "IN")),
        LanguageOption("Gujarati", "ગુજરાતી", ProtocolConstants.LANG_GUJARATI, "gu-IN", Locale("gu", "IN")),
        LanguageOption("Punjabi", "ਪੰਜਾਬੀ", ProtocolConstants.LANG_PUNJABI, "pa-IN", Locale("pa", "IN")),
        LanguageOption("Odia", "ଓଡ଼ିଆ", ProtocolConstants.LANG_ODIA, "or-IN", Locale("or", "IN"))
    )

    private var selectedLanguage: LanguageOption = supportedLanguages[0]

    private lateinit var btnSelectLanguage: Button
    private lateinit var btnBatteryMode: Button
    private lateinit var btnNearbyPeers: Button
    private lateinit var btnSos: Button
    private lateinit var btnTriageReport: Button
    private lateinit var btnSurvivalGuide: Button
    private lateinit var tvBleStatus: TextView
    private lateinit var tvWifiAwareStatus: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var tokenVisualizerView: TokenVisualizerView
    private lateinit var btnMicVoice: Button
    private lateinit var etMessageInput: EditText
    private lateinit var btnSendMessage: Button
    private lateinit var btnExportLogs: Button

    // Quick Disaster Chips
    private lateinit var chipPresetFlood: Button
    private lateinit var chipPresetMedical: Button
    private lateinit var chipPresetRubble: Button
    private lateinit var chipPresetWater: Button
    private lateinit var chipPresetEvac: Button

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var flashlightStrobeManager: FlashlightStrobeManager
    private var isSosSirenActive = false
    private var meshService: AirHopMeshService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AirHopMeshService.LocalBinder
            meshService = binder.getService()
            isBound = true
            observeServiceData()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isBound = false
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = spoken?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                etMessageInput.setText(spokenText)
                sendEmergencyMessage(spokenText)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startAndBindMeshService()
        checkBatteryOptimization()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        updatePresetChipLabels()
        requestMeshPermissions()
    }

    private fun initViews() {
        btnSelectLanguage = findViewById(R.id.btnSelectLanguage)
        btnBatteryMode = findViewById(R.id.btnBatteryMode)
        btnNearbyPeers = findViewById(R.id.btnNearbyPeers)
        btnSos = findViewById(R.id.btnSos)
        btnTriageReport = findViewById(R.id.btnTriageReport)
        btnSurvivalGuide = findViewById(R.id.btnSurvivalGuide)
        tvBleStatus = findViewById(R.id.tvBleStatus)
        tvWifiAwareStatus = findViewById(R.id.tvWifiAwareStatus)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        rvChatMessages = findViewById(R.id.rvChatMessages)
        tokenVisualizerView = findViewById(R.id.tokenVisualizerView)
        btnMicVoice = findViewById(R.id.btnMicVoice)
        etMessageInput = findViewById(R.id.etMessageInput)
        btnSendMessage = findViewById(R.id.btnSendMessage)
        btnExportLogs = findViewById(R.id.btnExportLogs)

        chipPresetFlood = findViewById(R.id.chipPresetFlood)
        chipPresetMedical = findViewById(R.id.chipPresetMedical)
        chipPresetRubble = findViewById(R.id.chipPresetRubble)
        chipPresetWater = findViewById(R.id.chipPresetWater)
        chipPresetEvac = findViewById(R.id.chipPresetEvac)

        flashlightStrobeManager = FlashlightStrobeManager(this)

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager

        chatAdapter = ChatAdapter(
            onSpeakClicked = { msg ->
                meshService?.offlineTtsEngine?.speak(msg.text, selectedLanguage.langByte)
            },
            onNavigateClicked = { msg ->
                navigateToCoordinates(msg.lat, msg.lon)
            }
        )
        rvChatMessages.adapter = chatAdapter
    }

    private fun setupListeners() {
        // Multi-Language Selector Dialog
        btnSelectLanguage.setOnClickListener {
            showLanguageSelectionDialog()
        }

        // 72-Hour Disaster Battery Saver Mode Toggle
        btnBatteryMode.setOnClickListener {
            toggleBatteryMode()
        }

        // Nearby Relays & Zero-Contact Explanation Dialog
        btnNearbyPeers.setOnClickListener {
            showRelayExplanationDialog()
        }

        // Emergency SOS Siren & Optical Strobe Beacon Toggle
        btnSos.setOnClickListener {
            toggleSosSirenAndStrobe()
        }

        // Quick Situational Triage Report
        btnTriageReport.setOnClickListener {
            SituationalTriageDialog.show(this) { reportText, isSos ->
                sendEmergencyMessage(reportText, isSos = isSos)
                Toast.makeText(this, "Emergency Situational Report Broadcasted!", Toast.LENGTH_SHORT).show()
            }
        }

        // Offline First-Aid & Emergency Survival Guide
        btnSurvivalGuide.setOnClickListener {
            EmergencySurvivalGuide.showGuideDialog(this)
        }

        // Send Button
        btnSendMessage.setOnClickListener {
            val text = etMessageInput.text.toString().trim()
            if (text.isNotBlank()) {
                sendEmergencyMessage(text)
                etMessageInput.setText("")
            } else {
                val defaultMsg = DisasterPhraseCodebook.getPhrase(1, selectedLanguage.langByte)
                sendEmergencyMessage(defaultMsg, phraseIdOverride = 1)
            }
        }

        // Voice Microphone (Speech-To-Text in selected language)
        btnMicVoice.setOnClickListener {
            launchVoiceRecognizer()
        }

        // Quick Preset Chips (in selected language)
        chipPresetFlood.setOnClickListener {
            val msg = DisasterPhraseCodebook.getPhrase(1, selectedLanguage.langByte)
            sendEmergencyMessage(msg, phraseIdOverride = 1)
        }
        chipPresetEvac.setOnClickListener {
            val msg = DisasterPhraseCodebook.getPhrase(2, selectedLanguage.langByte)
            sendEmergencyMessage(msg, phraseIdOverride = 2)
        }
        chipPresetMedical.setOnClickListener {
            val msg = DisasterPhraseCodebook.getPhrase(3, selectedLanguage.langByte)
            sendEmergencyMessage(msg, phraseIdOverride = 3)
        }
        chipPresetRubble.setOnClickListener {
            val msg = DisasterPhraseCodebook.getPhrase(4, selectedLanguage.langByte)
            sendEmergencyMessage(msg, phraseIdOverride = 4)
        }
        chipPresetWater.setOnClickListener {
            val msg = DisasterPhraseCodebook.getPhrase(5, selectedLanguage.langByte)
            sendEmergencyMessage(msg, phraseIdOverride = 5)
        }

        // Export Logs as CSV
        btnExportLogs.setOnClickListener {
            exportTriageLogs()
        }
    }

    private fun toggleSosSirenAndStrobe() {
        val service = meshService ?: return
        isSosSirenActive = !isSosSirenActive

        if (isSosSirenActive) {
            btnSos.text = "⏹️ STOP SIREN"
            btnSos.setBackgroundColor(android.graphics.Color.parseColor("#D50000"))

            // 1. Play continuous tactical emergency siren
            service.dndAlertManager.triggerSosAlarm(loopContinuous = true)

            // 2. Start optical SOS Morse strobe on camera LED
            flashlightStrobeManager.startSosStrobe()

            // 3. Broadcast high-priority SOS emergency packet
            val sosMsg = "🚨 EMERGENCY SOS BROADCAST: IMMEDIATE LIFE DANGER!"
            sendEmergencyMessage(sosMsg, isSos = true, phraseIdOverride = 1)

            window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            Toast.makeText(this, "🚨 RESCUE SIREN & SOS STROBE ACTIVE! Audible and visible to rescue teams.", Toast.LENGTH_LONG).show()
        } else {
            btnSos.text = "🚨 SOS SIREN & STROBE"
            btnSos.setBackgroundResource(R.drawable.bg_sos_button)
            service.dndAlertManager.stopAlarm()
            flashlightStrobeManager.stopSosStrobe()
            Toast.makeText(this, "Siren and flashlight strobe stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleBatteryMode() {
        val service = meshService ?: return
        val currentEco = service.bleMeshEngine.isPowerSaveMode.value
        val newEco = !currentEco
        service.bleMeshEngine.setPowerSaveMode(newEco)

        if (newEco) {
            btnBatteryMode.text = "🔋 72h ECO"
            btnBatteryMode.setTextColor(android.graphics.Color.parseColor("#00E676"))
            Toast.makeText(this, "🔋 72-Hour Disaster Battery Mode ACTIVE: BLE duty-cycled to save phone power.", Toast.LENGTH_LONG).show()
        } else {
            btnBatteryMode.text = "⚡ 100% PWR"
            btnBatteryMode.setTextColor(android.graphics.Color.parseColor("#FFD600"))
            Toast.makeText(this, "⚡ Full Power Mode ACTIVE: Continuous low-latency packet relay.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToCoordinates(lat: Double, lon: Double) {
        try {
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Trapped Survivor)")
            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(mapIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Survivor Location: $lat, $lon (No map app found)", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLanguageSelectionDialog() {
        val items = supportedLanguages.map { "${it.nativeName} (${it.name})" }.toTypedArray()
        val currentIndex = supportedLanguages.indexOf(selectedLanguage).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(this)
            .setTitle("🌐 Select Language / ಭಾಷೆಯನ್ನು ಆಯ್ಕೆಮಾಡಿ")
            .setSingleChoiceItems(items, currentIndex) { dialog, which ->
                val chosen = supportedLanguages[which]
                applyLanguage(chosen)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyLanguage(lang: LanguageOption) {
        selectedLanguage = lang
        btnSelectLanguage.text = "🌐 ${lang.nativeName} ▼"
        updatePresetChipLabels()
        Toast.makeText(this, "Language set to ${lang.nativeName} (${lang.name})", Toast.LENGTH_SHORT).show()
    }

    private fun updatePresetChipLabels() {
        val b = selectedLanguage.langByte
        chipPresetFlood.text = "🚨 " + DisasterPhraseCodebook.getPhrase(1, b)
        chipPresetEvac.text = "🏃 " + DisasterPhraseCodebook.getPhrase(2, b)
        chipPresetMedical.text = "🩺 " + DisasterPhraseCodebook.getPhrase(3, b)
        chipPresetRubble.text = "🏚️ " + DisasterPhraseCodebook.getPhrase(4, b)
        chipPresetWater.text = "💧 " + DisasterPhraseCodebook.getPhrase(5, b)
    }

    private fun showRelayExplanationDialog() {
        val activeCount = meshService?.bleMeshEngine?.activePeerCount?.value ?: 0

        AlertDialog.Builder(this)
            .setTitle("📡 AirHop Mesh: Zero Contacts Needed")
            .setMessage(
                "WHY ARE THERE NO PHONE CONTACTS?\n" +
                "In severe disasters (floods, earthquakes, cyclones), cellular towers & internet grids completely fail. You cannot dial phone numbers or look up SIM contacts.\n\n" +
                "HOW DOES SHARING WORK?\n" +
                "Any nearby phone with AirHop installed automatically discovers other phones and acts as an autonomous relay node.\n\n" +
                "HOW DOES THE PACKET HOP?\n" +
                "Your spoken voice is converted to a compact 40-byte neural packet. Nearby phones automatically hop it forward over supported offline transports and relay nodes; practical range depends on the phone hardware, environment, and transport availability.\n\n" +
                "DELIVERY CONFIRMATION (✓✓):\n" +
                "A receiving AirHop node can return an acknowledgment packet. The message is marked delivered only when a real acknowledgment matching the message ID reaches this device.\n\n" +
                "ACTIVE AIRHOP RELAYS OBSERVED:\n" +
                "• Active nodes observed by this device: $activeCount"
            )
            .setPositiveButton("📡 PING ALL RELAYS") { _, _ ->
                pingMeshRelays()
            }
            .setNegativeButton("GOT IT", null)
            .show()
    }

    private fun pingMeshRelays() {
        val service = meshService ?: return
        val pingText = "AirHop Mesh Ping: Link verification heartbeat"
        service.chatManager.sendMessage(
            text = pingText,
            phraseId = 7,
            isSos = false,
            lang = selectedLanguage.langByte
        )
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        Toast.makeText(this, "AirHop link-check frame broadcast to all nearby relays!", Toast.LENGTH_SHORT).show()
    }

    private fun launchVoiceRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLanguage.localeTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLanguage.localeTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(selectedLanguage.localeTag, "en-US"))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak in ${selectedLanguage.nativeName} (${selectedLanguage.name})...")
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognizer not available, please type message", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendEmergencyMessage(text: String, isSos: Boolean = false, phraseIdOverride: Int? = null) {
        val service = meshService ?: run {
            Toast.makeText(this, "Mesh service initializing...", Toast.LENGTH_SHORT).show()
            return
        }

        val loc = service.geofenceManager.currentLocation.value
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0

        val phraseId = phraseIdOverride ?: DisasterPhraseCodebook.getPhraseIdForText(text)

        service.chatManager.sendMessage(
            text = text,
            phraseId = phraseId,
            isSos = isSos,
            lang = selectedLanguage.langByte,
            lat = lat,
            lon = lon
        )

        tokenVisualizerView.updateAudioRms(0.75f, com.team.vocalink.core.VadState.ACTIVE)
        window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun observeServiceData() {
        val service = meshService ?: return

        // 1. Observe Chat Messages with auto-scroll
        lifecycleScope.launch {
            service.chatManager.messages.collectLatest { list ->
                chatAdapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        rvChatMessages.smoothScrollToPosition(list.size - 1)
                    }
                }
            }
        }

        // 2. Observe Delivery ACK events for Blue Tick haptics
        lifecycleScope.launch {
            service.chatManager.deliveryEvent.collectLatest { _ ->
                window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
        }

        // 3. Observe Peer Counts and update the Zero-Contact banner
        lifecycleScope.launch {
            service.bleMeshEngine.isCodedPhySupported.collectLatest { supported ->
                tvBleStatus.text = if (supported) "Bluetooth: extended + coded" else "Bluetooth: extended unavailable"
            }
        }

        lifecycleScope.launch {
            service.wifiAwareEngine.isAwareAvailable.collectLatest { available ->
                tvWifiAwareStatus.text = if (available) "Wi-Fi Aware: available" else "Wi-Fi Aware: unavailable"
            }
        }

        lifecycleScope.launch {
            service.bleMeshEngine.activePeerCount.collectLatest { count ->
                btnNearbyPeers.text = if (count == 0) {
                    "No nearby AirHop nodes observed"
                } else {
                    "🟢 $count nearby AirHop nodes observed"
                }
            }
        }

        // 4. Observe Audio RMS Waveform
        lifecycleScope.launch {
            service.audioIngestEngine.currentRms.collectLatest { rms ->
                tokenVisualizerView.updateAudioRms(rms, service.audioIngestEngine.vadState.value)
            }
        }

        // 5. Observe GPS/NavIC
        lifecycleScope.launch {
            service.geofenceManager.currentLocation.collectLatest { loc ->
                if (loc != null) {
                    tvGpsStatus.text = "NAVIC: %.4f°N".format(loc.latitude)
                    chatAdapter.currentLat = loc.latitude
                    chatAdapter.currentLon = loc.longitude
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun startAndBindMeshService() {
        val intent = Intent(this, AirHopMeshService::class.java).apply {
            action = AirHopMeshService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun exportTriageLogs() {
        val service = meshService ?: return
        val csv = service.disasterLogStore.exportAsCsv()
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, csv)
            putExtra(Intent.EXTRA_SUBJECT, "AirHop Disaster Triage Log - " + System.currentTimeMillis())
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, "Export Disaster Triage Log"))
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val pkg = packageName
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestMeshPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            flashlightStrobeManager.stopSosStrobe()
            meshService?.dndAlertManager?.stopAlarm()
        } catch (_: Exception) {}
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
