package com.gatorforge.sidecar.trackb

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.io.Closeable

class SidecarSenderActivity : AppCompatActivity() {
    // DESIGN INTENT: Provide a visible, editable module workflow, accept shared recipe images for
    // on-device OCR, and send eligible two-page bundles only after the X3 advertises protocol v2.

    companion object {
        private const val TAG = "SidecarSender"
        private const val PERMISSION_REQUEST_CODE = 42
        private const val CALENDAR_PERMISSION_REQUEST_CODE = 43
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val OPERATION_TIMEOUT_MS = 20000L
        private const val MTU_REQUEST = 517

        // The owner's remembered safe gap is about 375 ms. This is the documented minimum for any
        // future queued multi-page flow; normal v1 module sends contain one page and do not wait.
        private const val PAGE_BEGIN_GUARD_MS = 2_000L
        private const val PAGE_END_GUARD_MS = 375L

        // Settle time after the INFO read before the FIRST frame. See the note at its use site.
        private const val CONNECT_SETTLE_MS = 5_000L
    }

    private val SERVICE_UUID = UUID.fromString("6e6b1a00-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val RX_CHAR_UUID = UUID.fromString("6e6b1a01-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val INFO_CHAR_UUID = UUID.fromString("6e6b1a02-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val STATUS_CHAR_UUID = UUID.fromString("6e6b1a03-9f1d-4b7a-9c2e-1f0a2d3c4b5a")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val PEER_NAME = "EinkSidecar"

    private lateinit var root: LinearLayout
    private lateinit var dashboardPanel: LinearLayout
    private lateinit var detailPanel: LinearLayout
    private lateinit var moduleRows: LinearLayout
    private lateinit var selectionLabel: TextView
    private lateinit var pageLabel: TextView
    private lateinit var freshnessLabel: TextView
    private lateinit var previousPageButton: Button
    private lateinit var nextPageButton: Button
    private lateinit var editText: EditText
    private lateinit var settingsPanel: LinearLayout
    private lateinit var recipeImportButton: Button
    private lateinit var sendButton: Button
    private lateinit var queueButton: Button
    private lateinit var playlistButton: Button
    private lateinit var collectionCanaryButton: Button
    private lateinit var refreshMinutesInput: EditText
    private lateinit var pageSecondsInput: EditText
    private lateinit var stopRotationButton: Button
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView

    private var bluetoothManager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null

    private val scanning = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanAttempt = 0
    private var pendingScan = false
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutRunnable: Runnable? = null
    private var operationTimeoutRunnable: Runnable? = null
    private var pendingNoteText = ""
    private var writeQueue: ArrayDeque<ByteArray>? = null
    private var transferStartMs: Long = 0L
    private var totalPackets = 0
    private var totalPayloadBytes = 0
    private var negotiatedMtu = 23
    private var deviceInfo: SidecarFrame.DeviceInfo? = null
    private var selectedModule: Module = NoteModule
    private var activeModuleName = ""
    private var selectedPageIndex = 0
    private var activePageIndex = 0
    private var deliveredAsBundle = false
    private var deliveredPageCount = 1
    private var awaitingRenderConfirmation = false
    private var statusBaseline: Long? = null
    private var deliverySucceeded = false
    private var activeRecipeRecognition: Closeable? = null
    private var recipeRecognitionId = 0L
    private val moduleInputs by lazy { getSharedPreferences("sidecar_module_inputs", MODE_PRIVATE) }
    private val pickRecipeImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importRecipeImage(uri) else log("recipe photo selection cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SidecarPlaylist.migrateSettings(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 241, 234))
        }
        window.statusBarColor = Color.rgb(30, 30, 28)
        window.navigationBarColor = Color.rgb(244, 241, 234)
        dashboardPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailPanel.visibility == View.VISIBLE) showDashboard()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        selectionLabel = TextView(this).apply {
            textSize = 16f
        }
        moduleRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        editText = EditText(this).apply {
            isSingleLine = false
            minLines = 3
            maxLines = 10
            doAfterTextChanged {
                selectedPageIndex = 0
                updateSelectionUi()
            }
        }
        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pageLabel = TextView(this).apply { textSize = 14f }
        freshnessLabel = TextView(this).apply { textSize = 14f }
        previousPageButton = Button(this).apply {
            text = "PREVIOUS PAGE"
            setOnClickListener {
                selectedPageIndex = (selectedPageIndex - 1).coerceAtLeast(0)
                updateSelectionUi()
            }
        }
        nextPageButton = Button(this).apply {
            text = "NEXT PAGE"
            setOnClickListener {
                val pages = selectedModule.pageCount(this@SidecarSenderActivity, SidecarFrame.SUPPORTED_PANEL_HEIGHT, SidecarFrame.SUPPORTED_PANEL_WIDTH, editText.text.toString())
                selectedPageIndex = (selectedPageIndex + 1).coerceAtMost(pages - 1)
                updateSelectionUi()
            }
        }
        val pageButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(previousPageButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(nextPageButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        sendButton = Button(this).apply {
            setOnClickListener { onSendClicked() }
        }
        queueButton = Button(this).apply {
            text = "START ON X3"
            setOnClickListener {
                val note = editText.text?.toString().orEmpty()
                moduleInputs.edit().putString(selectedModule.displayName, note).apply()
                if (!selectedModule.autoAdvanceWhenQueued) {
                    SidecarPlaylist.setRotationActive(this@SidecarSenderActivity, false)
                }
                SidecarDeliveryService.queue(this@SidecarSenderActivity, selectedModule.displayName, note, selectedPageIndex)
                log("queued ${selectedModule.displayName} for background delivery")
            }
        }
        playlistButton = Button(this).apply {
            text = "START ENABLED ROTATION"
            setOnClickListener {
                moduleInputs.edit().putString(selectedModule.displayName, editText.text?.toString().orEmpty()).apply()
                saveRefreshMinutes()
                savePageSeconds(false)
                val enabled = SidecarPlaylist.enabledModules(this@SidecarSenderActivity)
                if (enabled.isEmpty()) {
                    log("turn on at least one ROTATE checkbox")
                } else {
                    SidecarPlaylist.setRotationActive(this@SidecarSenderActivity, true)
                    SidecarDeliveryService.queue(this@SidecarSenderActivity, PlaylistModule.displayName, "", automatic = true)
                    log("queued rotation: ${enabled.joinToString { it.displayName }}")
                }
            }
        }
        collectionCanaryButton = Button(this).apply {
            text = "SEND SELECTED AS V5 CANARY"
            setOnClickListener {
                val note = editText.text?.toString().orEmpty()
                moduleInputs.edit().putString(selectedModule.displayName, note).apply()
                SidecarDeliveryService.queueCollectionCanary(
                    this@SidecarSenderActivity, selectedModule.displayName, note
                )
                log("queued manual V5 canary: ${selectedModule.displayName}")
            }
        }
        refreshMinutesInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(SidecarPlaylist.refreshMinutes(this@SidecarSenderActivity).toString())
            hint = "2"
        }
        val refreshRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SidecarSenderActivity).apply {
                text = "FETCH FRESH INFORMATION\nEVERY … MINUTES"
                textSize = 15f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshMinutesInput, LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(Button(this@SidecarSenderActivity).apply {
                text = "SAVE"
                setOnClickListener { saveRefreshMinutes() }
            })
        }
        pageSecondsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setText(SidecarPlaylist.pageSeconds(this@SidecarSenderActivity).toString())
            hint = "1–300"
        }
        val cadenceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SidecarSenderActivity).apply {
                text = "X3 PAGE PAUSE\nSECONDS"
                textSize = 15f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pageSecondsInput, LinearLayout.LayoutParams(180, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(Button(this@SidecarSenderActivity).apply {
                text = "SAVE"
                setOnClickListener { savePageSeconds(true) }
            })
        }
        stopRotationButton = Button(this).apply {
            text = "STOP X3 ROTATION"
            setOnClickListener {
                SidecarPlaylist.setRotationActive(this@SidecarSenderActivity, false)
                SidecarDeliveryService.cancel(this@SidecarSenderActivity)
                log("background delivery stopped")
            }
        }
        logView = TextView(this).apply {
            text = ""
            textSize = 12f
            visibility = View.GONE
        }
        scrollView = ScrollView(this).apply {
            // This screen is a dashboard, so always open at the controls at the top.
            // Restoring ScrollView state made a fresh launch appear to start halfway down.
            isSaveEnabled = false
            id = View.NO_ID
        }

        dashboardPanel.addView(TextView(this).apply {
            text = "EINK SIDECAR"
            textSize = 30f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(10), dp(6), 0)
        })
        dashboardPanel.addView(TextView(this).apply {
            text = "Choose what appears on the X3. Turn a pane on, then customize its content."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(dp(6), dp(2), dp(6), dp(12))
        })
        dashboardPanel.addView(moduleRows)

        detailPanel.addView(Button(this).apply {
            text = "‹  Back"
            isAllCaps = false
            setOnClickListener { showDashboard() }
        })
        detailPanel.addView(selectionLabel)
        recipeImportButton = Button(this).apply {
            text = "IMPORT RECIPE PHOTO"
            setOnClickListener { pickRecipeImage.launch("image/*") }
        }
        detailPanel.addView(recipeImportButton)
        detailPanel.addView(
            editText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        detailPanel.addView(settingsPanel)
        detailPanel.addView(pageLabel)
        detailPanel.addView(freshnessLabel)
        detailPanel.addView(pageButtons)
        detailPanel.addView(
            sendButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        detailPanel.addView(
            queueButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        dashboardPanel.addView(
            playlistButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        collectionCanaryButton.visibility = View.GONE
        dashboardPanel.addView(cadenceRow)
        dashboardPanel.addView(TextView(this).apply {
            text = "1 second is fastest. Page refresh adds a short delay. Recipes stay fixed.\n" +
                "Fresh information controls online updates, not page turns."
            setTextColor(Color.DKGRAY)
        })
        dashboardPanel.addView(
            refreshRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        dashboardPanel.addView(Button(this).apply {
            text = "EMAIL & DELIVERY NOTIFICATION ACCESS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })
        dashboardPanel.addView(Button(this).apply {
            text = "CALENDAR ACCESS"
            setOnClickListener {
                ActivityCompat.requestPermissions(
                    this@SidecarSenderActivity,
                    arrayOf(Manifest.permission.READ_CALENDAR),
                    CALENDAR_PERMISSION_REQUEST_CODE
                )
            }
        })
        dashboardPanel.addView(
            stopRotationButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(dashboardPanel)
        root.addView(detailPanel)
        root.addView(
            logView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        scrollView.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        selectModule(NoteModule)
        showDashboard()
        setContentView(scrollView)
        supportActionBar?.hide()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        root.isFocusableInTouchMode = true
        root.requestFocus()
        resetDashboardScroll()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = bluetoothManager?.adapter

        handleRotationIntent(intent)
        log("SidecarSender ready")
        handleSharedImageIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRotationIntent(intent)
        handleSharedImageIntent(intent)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showDashboard() {
        if (::editText.isInitialized) {
            moduleInputs.edit().putString(selectedModule.displayName, editText.text.toString()).apply()
        }
        rebuildModuleCards()
        detailPanel.visibility = View.GONE
        dashboardPanel.visibility = View.VISIBLE
        currentFocus?.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        root.requestFocus()
        resetDashboardScroll()
    }

    private fun resetDashboardScroll() {
        scrollView.postDelayed({
            scrollView.fullScroll(View.FOCUS_UP)
            scrollView.scrollTo(0, 0)
        }, 100L)
    }

    private fun openModule(module: Module) {
        selectModule(module)
        dashboardPanel.visibility = View.GONE
        detailPanel.visibility = View.VISIBLE
        scrollView.scrollTo(0, 0)
    }

    private fun rebuildModuleCards() {
        moduleRows.removeAllViews()
        SidecarModules.all.forEach { module ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.rgb(218, 214, 204))
                }
            }
            val headingRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            headingRow.addView(TextView(this).apply {
                text = moduleUiName(module)
                textSize = 18f
                setTextColor(Color.BLACK)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (module.supportsRotation) {
                headingRow.addView(SwitchCompat(this).apply {
                    isChecked = SidecarPlaylist.isEnabled(this@SidecarSenderActivity, module)
                    text = if (isChecked) "On" else "Off"
                    setOnCheckedChangeListener { _, enabled ->
                        SidecarPlaylist.setEnabled(this@SidecarSenderActivity, module, enabled)
                        val accepted = SidecarPlaylist.isEnabled(this@SidecarSenderActivity, module)
                        if (isChecked != accepted) isChecked = accepted
                        text = if (accepted) "On" else "Off"
                    }
                })
            } else {
                headingRow.addView(TextView(this).apply {
                    text = "Fixed"
                    setTextColor(Color.DKGRAY)
                })
            }
            card.addView(headingRow)
            card.addView(TextView(this).apply {
                text = moduleSummary(module)
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(3), 0, dp(6))
            })
            card.addView(Button(this).apply {
                text = "Customize"
                isAllCaps = false
                setOnClickListener { openModule(module) }
            })
            moduleRows.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) })
        }
    }

    private fun moduleUiName(module: Module): String = when (module) {
        HeadlinesModule -> "Daily Brief"
        NoteModule -> "Sticky Cards"
        MarketsModule -> "Markets, FX & Commodities"
        else -> module.displayName.lowercase().split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun moduleSummary(module: Module): String = when (module) {
        NoteModule -> "Rotating reminders and pinned cards separated by ---."
        CalendarModule -> "Upcoming appointments from Android calendars."
        WeatherModule -> "Current conditions, two-hour outlook, and seven-day forecast."
        HeadlinesModule -> "Headline-only briefing from your chosen feeds."
        LocalNewsModule -> "Local headlines from Morning Brief feeds."
        SportsHeadlinesModule -> "Separate college football and NFL headline pages."
        FootballModule -> "NFL scores plus QB, RB, and WR game leaders."
        ApTop25Module -> "AP Top 25, Florida, and college player highlights."
        FantasyLeadersModule -> "Top fantasy performers by position and scoring format."
        StocksModule -> "Your stock symbols, prices, direction, and percent change."
        MarketsModule -> "Custom currency pairs, indexes, and commodities."
        TravelModule -> "Optional live flight status when an API key is configured."
        PackageTrackingModule -> "Amazon and carrier updates captured from notifications."
        EmailModule -> "Recent email subjects grouped by account."
        RecipeModule -> "A fixed recipe screen imported from text or a photo."
        GroceryModule -> "A pageable grocery list."
        TasksModule -> "A compact rotating task list."
        AstronomyModule -> "Two-page daily sky and astronomy briefing."
        else -> module.inputHint ?: "Sidecar pane"
    }

    private fun handleRotationIntent(incoming: Intent) {
        val rotationRequested = incoming.getBooleanExtra("start_rotation", false)
        if (rotationRequested) incoming.removeExtra("start_rotation")
        if (SidecarPlaylist.enabledModules(this).isEmpty()) return
        if (rotationRequested) {
            SidecarPlaylist.setRotationActive(this, true)
            SidecarDeliveryService.queue(this, PlaylistModule.displayName, "", automatic = true)
            log("started enabled rotation")
        } else if (SidecarPlaylist.isRotationActive(this)) {
            SidecarDeliveryService.resumeRotation(this)
            log("resumed enabled rotation")
        }
    }

    private fun handleSharedImageIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") != true) {
            log("shared item is not an image")
            return
        }
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (uri == null) log("shared image is missing its content stream") else importRecipeImage(uri)
    }

    private fun importRecipeImage(uri: Uri) {
        log("reading recipe photo on phone")
        activeRecipeRecognition?.close()
        val requestId = ++recipeRecognitionId
        activeRecipeRecognition = RecipeImageImport.recognize(this, uri) { result ->
            runOnUiThread {
                if (requestId != recipeRecognitionId || isFinishing || isDestroyed) return@runOnUiThread
                activeRecipeRecognition = null
                result.onSuccess { text ->
                    val draft = EditText(this).apply {
                        setText(text)
                        minLines = 12
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Review recipe text")
                        .setView(draft)
                        .setNegativeButton("Cancel") { _, _ -> log("recipe import cancelled; previous recipe kept") }
                        .setPositiveButton("Save") { _, _ ->
                            val saved = draft.text?.toString().orEmpty().trim()
                            if (saved.isBlank()) {
                                log("recipe import not saved: text is empty")
                            } else {
                                selectModule(RecipeModule)
                                editText.setText(saved)
                                moduleInputs.edit().putString(RecipeModule.displayName, saved).apply()
                                selectedPageIndex = 0
                                updateSelectionUi()
                                log("recipe photo saved: ${RecipeModule.pageCount(this, SidecarFrame.SUPPORTED_PANEL_HEIGHT, SidecarFrame.SUPPORTED_PANEL_WIDTH, saved)} page(s)")
                            }
                        }
                        .show()
                }.onFailure { error ->
                    log("recipe photo could not be read: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun savePageSeconds(deliver: Boolean) {
        val seconds = pageSecondsInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 300) ?: 1
        SidecarPlaylist.setPageSeconds(this, seconds)
        pageSecondsInput.setText(seconds.toString())
        if (deliver && SidecarPlaylist.isRotationActive(this)) {
            SidecarDeliveryService.queue(this, PlaylistModule.displayName, "", automatic = true)
            log("page pause $seconds seconds; queued for X3")
        } else {
            log("page pause $seconds seconds; applies when rotation starts")
        }
    }

    private fun saveRefreshMinutes() {
        val minutes = refreshMinutesInput.text?.toString()?.toIntOrNull()?.coerceIn(1, 120) ?: 2
        SidecarPlaylist.setRefreshMinutes(this, minutes)
        refreshMinutesInput.setText(minutes.toString())
        log("live data refresh set to $minutes minute${if (minutes == 1) "" else "s"}")
    }

    private fun updateSelectionUi() {
        val pages = selectedModule.pageCount(this, SidecarFrame.SUPPORTED_PANEL_HEIGHT, SidecarFrame.SUPPORTED_PANEL_WIDTH, editText.text?.toString().orEmpty())
        selectedPageIndex = selectedPageIndex.coerceIn(0, pages - 1)
        selectionLabel.text = if (selectedModule.supportsRotation) {
            "MODULE: ${selectedModule.displayName} - ROTATION ${if (SidecarPlaylist.isEnabled(this, selectedModule)) "ON" else "OFF"}"
        } else "MODULE: ${selectedModule.displayName} - FIXED SCREEN"
        val rotationPages = SidecarPlaylist.rotationPageCount(this)
        pageLabel.text = "PAGE ${selectedPageIndex + 1} OF $pages   •   ROTATION $rotationPages PAGES"
        freshnessLabel.text = if (SidecarLiveData.needsRefresh(selectedModule.displayName)) {
            val refreshed = SidecarLiveData.lastSuccessfulRefreshMillis(this, selectedModule)
            if (refreshed == 0L) "FRESHNESS: waiting for first successful update"
            else "FRESHNESS: last updated ${DateFormat.getDateFormat(this).format(refreshed)} ${DateFormat.getTimeFormat(this).format(refreshed)}"
        } else ""
        previousPageButton.isEnabled = selectedPageIndex > 0
        nextPageButton.isEnabled = selectedPageIndex + 1 < pages
        sendButton.text = if (selectedModule.supportsLocalPages && pages in 1..SidecarFrame.MAX_BUNDLE_PAGES) {
            "SEND ${selectedModule.displayName} - ALL $pages PAGES"
        } else "SEND ${selectedModule.displayName} PAGE ${selectedPageIndex + 1}"
        queueButton.text = if (selectedModule.supportsLocalPages && pages in 1..SidecarFrame.MAX_BUNDLE_PAGES) {
            "STORE ALL $pages PAGES ON X3"
        } else if (selectedModule.autoAdvanceWhenQueued) "START ON X3" else "PIN PAGE ${selectedPageIndex + 1} ON X3"
        queueButton.visibility = if (selectedModule === ApTop25Module) View.GONE else View.VISIBLE
        recipeImportButton.visibility = if (selectedModule === RecipeModule) View.VISIBLE else View.GONE
        val structured = selectedModule === FootballModule || selectedModule === ApTop25Module || selectedModule === FantasyLeadersModule
        editText.visibility = if (selectedModule.inputHint == null || structured) View.GONE else View.VISIBLE
        editText.hint = selectedModule.inputHint
        renderStructuredSettings()
    }

    private fun renderStructuredSettings() {
        settingsPanel.removeAllViews()
        val module = selectedModule
        if (module !== FootballModule && module !== ApTop25Module && module !== FantasyLeadersModule) {
            settingsPanel.visibility = View.GONE
            return
        }
        settingsPanel.visibility = View.VISIBLE
        settingsPanel.addView(TextView(this).apply {
            text = if (module === FantasyLeadersModule) "FANTASY LEADER PAGES" else if (module === FootballModule) "NFL SCORE DAY" else "COLLEGE FOOTBALL SCORE DAY"
            textSize = 18f
        })
        if (module === FootballModule || module === ApTop25Module) {
            val days = listOf("THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY")
            val selected = editText.text.toString().lineSequence().firstOrNull { it.startsWith("Day=", true) }
                ?.substringAfter('=')?.trim()?.uppercase() ?: if (module === FootballModule) "SUNDAY" else "SATURDAY"
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, days)
            spinner.setSelection(days.indexOf(selected).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    saveStructuredConfiguration("Day=${days[position]}")
                }
            }
            settingsPanel.addView(spinner)
            settingsPanel.addView(TextView(this).apply { text = "Shows games from that day in the current Monday–Sunday week." })
            return
        }
        val current = editText.text.toString()
        val enabled = current.lineSequence().firstOrNull { it.startsWith("Positions=", true) }
            ?.substringAfter('=')?.split(',')?.map { it.trim().uppercase() }?.toSet() ?: setOf("QB", "RB", "WR", "TE")
        val checkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("QB", "RB", "WR", "TE").forEach { position ->
            checkRow.addView(CheckBox(this).apply {
                text = position
                isChecked = position in enabled
                setOnCheckedChangeListener { _, _ -> saveFantasyControls(checkRow) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        settingsPanel.addView(checkRow)
        val scoring = listOf("STANDARD", "HALF PPR", "PPR")
        val chosen = current.lineSequence().firstOrNull { it.startsWith("Scoring=", true) }
            ?.substringAfter('=')?.trim()?.uppercase() ?: "HALF PPR"
        val scoringSpinner = Spinner(this).apply {
            tag = "scoring"
            adapter = ArrayAdapter(this@SidecarSenderActivity, android.R.layout.simple_spinner_dropdown_item, scoring)
            setSelection(scoring.indexOf(chosen).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { saveFantasyControls(checkRow) }
            }
        }
        settingsPanel.addView(TextView(this).apply { text = "SCORING"; textSize = 15f })
        settingsPanel.addView(scoringSpinner)
        settingsPanel.addView(TextView(this).apply { text = "Each selected position gets one Top 5 page using the latest completed NFL week." })
    }

    private fun saveFantasyControls(checkRow: LinearLayout) {
        if (selectedModule !== FantasyLeadersModule) return
        val positions = (0 until checkRow.childCount).mapNotNull { index ->
            (checkRow.getChildAt(index) as? CheckBox)?.takeIf { it.isChecked }?.text?.toString()
        }.ifEmpty { listOf("QB") }
        val scoring = (0 until settingsPanel.childCount).mapNotNull { index -> settingsPanel.getChildAt(index) as? Spinner }
            .firstOrNull { it.tag == "scoring" }?.selectedItem?.toString() ?: "HALF PPR"
        saveStructuredConfiguration("Positions=${positions.joinToString(",")}\nScoring=$scoring")
    }

    private fun saveStructuredConfiguration(value: String) {
        if (editText.text.toString() == value) return
        editText.setText(value)
        moduleInputs.edit().putString(selectedModule.displayName, value).apply()
    }

    private fun selectModule(module: Module) {
        if (busy.get()) {
            log("busy; ignoring module switch")
            return
        }
        if (::editText.isInitialized) {
            moduleInputs.edit().putString(selectedModule.displayName, editText.text.toString()).apply()
        }
        selectedModule = module
        selectedPageIndex = 0
        val saved = moduleInputs.getString(module.displayName, null)
        editText.setText(saved ?: module.defaultInput)
        updateSelectionUi()
        log("selected ${module.displayName}")
    }

    private fun onSendClicked() {
        if (busy.get()) {
            log("already running; ignoring second tap")
            return
        }
        if (pendingScan) {
            log("permission request already in progress; ignoring tap")
            return
        }
        log("SEND ${selectedModule.displayName} tapped")
        deliverySucceeded = false
        pageLabel.text = "SENDING ${selectedModule.displayName}..."

        pendingNoteText = editText.text.toString()
        activePageIndex = selectedPageIndex
        moduleInputs.edit().putString(selectedModule.displayName, pendingNoteText).apply()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            log("missing permissions: $missing; requesting")
            pendingScan = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }
        startScanWithPermissions()
    }

    private fun missingPermissions(): List<String> {
        val result = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            result.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            result.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return result
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CALENDAR_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            log(if (granted) "calendar permission granted" else "calendar permission denied; calendar will show permission required")
            if (selectedModule === CalendarModule) updateSelectionUi()
            return
        }
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val denied = permissions.indices.filter {
            grantResults[it] != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isEmpty()) {
            log("permissions granted: ${permissions.joinToString()}")
            if (pendingScan) {
                pendingScan = false
                startScanWithPermissions()
            }
        } else {
            log(
                "permissions denied: ${
                    denied.joinToString { i -> "${permissions[i]}=${grantResults[i]}" }
                }"
            )
            pendingScan = false
            pageLabel.text = "SEND FAILED - BLUETOOTH PERMISSION DENIED"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanWithPermissions() {
        busy.set(true)
        writeQueue = null
        awaitingRenderConfirmation = false
        statusBaseline = null
        val a = adapter
        if (a == null) {
            log("Bluetooth manager/adapter absent")
            pageLabel.text = "SEND FAILED - BLUETOOTH UNAVAILABLE"
            busy.set(false)
            return
        }
        if (!a.isEnabled) {
            log("Bluetooth adapter off")
            pageLabel.text = "SEND FAILED - BLUETOOTH IS OFF"
            busy.set(false)
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            log("no BluetoothLeScanner available")
            pageLabel.text = "SEND FAILED - BLE UNAVAILABLE"
            busy.set(false)
            return
        }

        val oldGatt = gatt
        gatt = null
        oldGatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        cancelOperationTimeout()

        cancelScanTimeout()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning.set(true)
        val attempt = ++scanAttempt
        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanCallback !== this || !scanning.get()) {
                    log("scan result after timeout ignored")
                    return
                }
                val device = result.device
                val name = runCatching { device.name }.getOrNull()
                val uuidMatch = runCatching {
                    result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true
                }.getOrDefault(false)
                val nameMatch = name?.equals(PEER_NAME, ignoreCase = true) == true

                log(
                    "scan result: ${device.address} name=${name ?: "unknown"} " +
                        "uuidMatch=$uuidMatch nameMatch=$nameMatch"
                )
                if (uuidMatch || nameMatch) {
                    if (!scanning.compareAndSet(true, false)) return
                    log("candidate matched; stopping scan and connecting")
                    runCatching { scanner.stopScan(this) }
                    scanCallback = null
                    cancelScanTimeout()
                    connectToDevice(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan failed errorCode=$errorCode")
                scanCallback = null
                scanning.set(false)
                cancelScanTimeout()
                busy.set(false)
                runOnUiThread { pageLabel.text = "SEND FAILED - SCAN ERROR" }
            }
        }

        scanCallback = cb
        try {
            scanner.startScan(emptyList(), settings, cb)
            log("scanning for Sidecar UUID or name '$PEER_NAME'")
        } catch (e: SecurityException) {
            log("startScan denied: ${e.message}")
            scanCallback = null
            scanning.set(false)
            busy.set(false)
            pageLabel.text = "SEND FAILED - SCAN DENIED"
            return
        }

        val timeoutRunnable = Runnable {
            if (attempt == scanAttempt && scanning.get()) {
                log("no peripheral found in ${SCAN_TIMEOUT_MS / 1000}s")
                stopScan()
                busy.set(false)
                pageLabel.text = "SEND FAILED - X3 NOT FOUND"
            }
        }
        scanTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    private fun cancelOperationTimeout() {
        operationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        operationTimeoutRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun restartOperationTimeout() {
        cancelOperationTimeout()
        val runnable = Runnable {
            if (busy.get()) {
                log("operation timed out after ${OPERATION_TIMEOUT_MS / 1000}s; cleaning up")
                val g = gatt
                gatt = null
                writeQueue = null
                if (g != null) {
                    runCatching { g.disconnect() }
                    runCatching { g.close() }
                }
                busy.set(false)
                pageLabel.text = "SEND FAILED - OPERATION TIMED OUT"
            }
        }
        operationTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, OPERATION_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        if (scanning.getAndSet(false)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        }
        cancelScanTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        log("connecting GATT LE to ${device.address} with TRANSPORT_LE")
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("connectGatt denied: ${e.message}")
            gatt = null
            busy.set(false)
            return
        } catch (e: Exception) {
            log("connectGatt failed: ${e.message}")
            gatt = null
            busy.set(false)
            return
        }

        if (gatt == null) {
            log("connectGatt returned null")
            busy.set(false)
            return
        }

        startOperationTimeout()
    }

    private fun startOperationTimeout() {
        restartOperationTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun startFrameTransfer(g: BluetoothGatt, info: SidecarFrame.DeviceInfo) {
        if (info.width <= 0 || info.height <= 0 || info.bpp != 1 || info.maxChunk <= 0 || info.protocolVer !in 1..4) {
            log("unsupported INFO geometry=${info.width}x${info.height} bpp=${info.bpp} chunk=${info.maxChunk} protocol=${info.protocolVer}")
            closeGatt(g)
            return
        }
        activeModuleName = selectedModule.displayName
        val chunk = minOf(info.maxChunk, 512 - 3, negotiatedMtu - 6)
        if (chunk <= 0) {
            log("no usable chunk size (maxChunk=${info.maxChunk} attrCap=509 mtu=$negotiatedMtu); aborting")
            closeGatt(g)
            return
        }
        log("chunk=$chunk (maxChunk=${info.maxChunk} attrCap=509 mtu=$negotiatedMtu)")
        val rxChar = runCatching { g.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) }.getOrNull()
        if (rxChar == null) {
            log("RX characteristic not found")
            closeGatt(g)
            return
        }

        val pageCount = selectedModule.pageCount(this, info.height, info.width, pendingNoteText)
        deliveredPageCount = pageCount
        deliveredAsBundle = selectedModule.supportsLocalPages && when {
            info.protocolVer >= 3 -> pageCount in 1..SidecarFrame.MAX_BUNDLE_PAGES
            info.protocolVer >= 2 -> pageCount == 2
            else -> false
        }
        var payloadBytes = 0
        val queue = ArrayDeque<ByteArray>()
        if (info.supportsPageCadence) {
            queue.add(SidecarFrame.pageCadencePacket(
                if (selectedModule.supportsRotation) SidecarPlaylist.pageSeconds(this) else 0
            ))
        }
        val pages = if (deliveredAsBundle) 0 until pageCount else activePageIndex..activePageIndex
        try {
            pages.forEach { page ->
                val portrait = selectedModule.renderPage(this, info.height, info.width, pendingNoteText, page)
                val bits = SidecarFrame.portraitToPanelBits(portrait, info.width, info.height)
                portrait.recycle()
                payloadBytes += bits.size
                log("rendered $activeModuleName page=${page + 1}/$pageCount -> panel=${info.width}x${info.height} bytes=${bits.size}")
                queue.add(
                    if (deliveredAsBundle) SidecarFrame.beginPagePacket(
                        0, 0, info.width, info.height, bits, page, pageCount, activePageIndex
                    ) else SidecarFrame.beginPacket(0, 0, info.width, info.height, bits.size)
                )
                queue.addAll(SidecarFrame.dataPackets(bits, chunk))
                queue.add(SidecarFrame.endPacket())
            }
        } catch (error: Exception) {
            log("render/encode failed: ${error.message ?: error.javaClass.simpleName}")
            closeGatt(g)
            return
        }
        totalPackets = queue.size
        totalPayloadBytes = payloadBytes
        transferStartMs = System.currentTimeMillis()
        log("sending $activeModuleName bundle=$deliveredAsBundle: ${queue.size} packets ($payloadBytes payload bytes)")
        writeQueue = queue
        drainQueue(g, rxChar)
        restartOperationTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun drainQueue(g: BluetoothGatt, rxChar: BluetoothGattCharacteristic) {
        val queue = writeQueue ?: return
        if (queue.isEmpty()) {
            val elapsed = System.currentTimeMillis() - transferStartMs
            log("module=$activeModuleName writes acknowledged packets=$totalPackets bytes=$totalPayloadBytes ms=$elapsed; awaiting device completion")
            writeQueue = null
            awaitingRenderConfirmation = true
            restartOperationTimeout()
            return
        }
        val packet = queue.peekFirst() ?: run {
            log("write queue unexpectedly empty")
            writeQueue = null
            closeGatt(g)
            return
        }

        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val rc = try {
            g.writeCharacteristic(rxChar, packet, writeType)
        } catch (e: SecurityException) {
            log("writeCharacteristic denied: ${e.message}")
            writeQueue = null
            closeGatt(g)
            return
        } catch (e: Exception) {
            log("writeCharacteristic failed: ${e.message}")
            writeQueue = null
            closeGatt(g)
            return
        }
        if (rc != BluetoothGatt.GATT_SUCCESS && rc != 0) {
            log("writeCharacteristic returned non-success $rc")
            writeQueue = null
            closeGatt(g)
            return
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        private fun isStale(g: BluetoothGatt): Boolean {
            val stale = gatt !== g
            if (stale) log("stale gatt callback ignored")
            return stale
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("GATT connection state error status=$status")
                closeGatt(g)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("GATT connected; requesting MTU $MTU_REQUEST")
                    val mtuOk = runCatching { g.requestMtu(MTU_REQUEST) }.getOrDefault(false)
                    if (!mtuOk) {
                        log("requestMtu returned false; no MTU callback expected")
                        closeGatt(g)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected mid-sequence")
                    closeGatt(g)
                }
                else -> {
                    log("GATT unexpected state $newState")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("MTU change failed status=$status")
                closeGatt(g)
                return
            }
            negotiatedMtu = mtu
            log("MTU negotiated: $mtu")

            val prioOk = runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                .getOrDefault(false)
            log("requestConnectionPriority(HIGH) -> $prioOk")
            runCatching {
                g.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED
                )
            }

            val discOk = runCatching { g.discoverServices() }.getOrDefault(false)
            if (!discOk) {
                log("discoverServices returned false")
                closeGatt(g)
            } else {
                log("service discovery started")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("service discovery failed status=$status")
                closeGatt(g)
                return
            }
            log("services discovered")

            val service = runCatching { g.getService(SERVICE_UUID) }.getOrNull()
            if (service == null) {
                log("service not found: $SERVICE_UUID")
                closeGatt(g)
                return
            }
            log("service found")

            val statusChar = runCatching { service.getCharacteristic(STATUS_CHAR_UUID) }.getOrNull()
            if (statusChar == null) {
                log("STATUS characteristic not found: $STATUS_CHAR_UUID — incompatible firmware")
                closeGatt(g)
                return
            }
            val notifyOk = runCatching { g.setCharacteristicNotification(statusChar, true) }.getOrDefault(false)
            if (!notifyOk) {
                log("setCharacteristicNotification returned false")
                closeGatt(g)
                return
            }
            val cccd = statusChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                log("STATUS CCCD not found")
                closeGatt(g)
                return
            }
            log("STATUS characteristic found; enabling completion notifications")
            val descriptorRc = runCatching {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }.getOrDefault(BluetoothGatt.GATT_FAILURE)
            if (descriptorRc != BluetoothGatt.GATT_SUCCESS) {
                log("STATUS CCCD write returned $descriptorRc")
                closeGatt(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (isStale(g)) return
            if (descriptor.uuid != CCCD_UUID || descriptor.characteristic.uuid != STATUS_CHAR_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("STATUS CCCD write failed status=$status")
                closeGatt(g)
                return
            }

            val service = runCatching { g.getService(SERVICE_UUID) }.getOrNull()
            val statusChar = runCatching { service?.getCharacteristic(STATUS_CHAR_UUID) }.getOrNull()
            if (statusChar == null) {
                log("STATUS characteristic vanished before freshness read")
                closeGatt(g)
                return
            }
            log("reading STATUS freshness barrier")

            val readOk = runCatching { g.readCharacteristic(statusChar) }.getOrDefault(false)
            if (!readOk) {
                log("readCharacteristic returned false; no read callback expected")
                closeGatt(g)
            }
        }

        private fun handleStatusNotification(g: BluetoothGatt, value: ByteArray) {
            if (isStale(g)) return
            val code = value.firstOrNull()?.toInt()?.and(0xFF)
            if (code != 1 || !awaitingRenderConfirmation || writeQueue != null || transferStartMs == 0L) {
                log("STATUS notification ignored code=${code ?: "empty"}")
                return
            }
            val counter = SidecarFrame.statusCounter(value)
            val expectedCounter = statusBaseline?.let { (it + 1L) and 0xffffffffL }
            if (counter == null || counter != expectedCounter) {
                log("STATUS notification ignored stale counter=$counter expected=$expectedCounter")
                return
            }
            awaitingRenderConfirmation = false
            deliverySucceeded = true
            val elapsed = System.currentTimeMillis() - transferStartMs
            log("module=$activeModuleName ${if (deliveredAsBundle) "$deliveredPageCount pages stored; active page rendered" else "device rendered"} ms=$elapsed")
            closeGatt(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == STATUS_CHAR_UUID) handleStatusNotification(g, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == STATUS_CHAR_UUID) {
                handleStatusNotification(g, characteristic.value ?: ByteArray(0))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (isStale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("characteristic read failed status=$status")
                closeGatt(g)
                return
            }
            if (characteristic.uuid == STATUS_CHAR_UUID) {
                val initial = value.firstOrNull()?.toInt()?.and(0xff)
                val counter = SidecarFrame.statusCounter(value)
                if (initial != 0 || counter == null) {
                    log("STATUS freshness barrier failed value=${initial ?: "empty"}")
                    closeGatt(g)
                    return
                }
                statusBaseline = counter
                val infoChar = runCatching { g.getService(SERVICE_UUID)?.getCharacteristic(INFO_CHAR_UUID) }.getOrNull()
                if (infoChar == null || !runCatching { g.readCharacteristic(infoChar) }.getOrDefault(false)) {
                    log("INFO characteristic read rejected")
                    closeGatt(g)
                }
                return
            }
            if (characteristic.uuid != INFO_CHAR_UUID) return
            val info = SidecarFrame.parseInfo(value)
            if (info == null) {
                log("INFO parse failed: got ${value.size} bytes, need >= 10")
                closeGatt(g)
                return
            }
            log("info w=${info.width} h=${info.height} bpp=${info.bpp} rotation=${info.rotation} maxChunk=${info.maxChunk} ver=${info.protocolVer}")

            deviceInfo = info
            val gLocal = g
            log("connect settle: waiting ${CONNECT_SETTLE_MS}ms before frame")
            mainHandler.postDelayed({
                if (gatt == null || gatt !== gLocal) {
                    log("connect-settle runnable: gatt stale or null; aborting")
                    return@postDelayed
                }
                startFrameTransfer(gLocal, info)
            }, CONNECT_SETTLE_MS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (isStale(g)) return
            if (characteristic.uuid != RX_CHAR_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("write failed status=$status")
                writeQueue = null
                closeGatt(g)
                return
            }

            val queue = writeQueue
            if (queue == null) {
                closeGatt(g)
                return
            }

            val sent = queue.pollFirst()

            if (queue.isEmpty()) {
                val elapsed = System.currentTimeMillis() - transferStartMs
                log("module=$activeModuleName writes acknowledged packets=$totalPackets bytes=$totalPayloadBytes ms=$elapsed; awaiting device completion")
                writeQueue = null
                awaitingRenderConfirmation = true
                restartOperationTimeout()
                return
            }

            restartOperationTimeout()

            val rxChar = runCatching { g.getService(SERVICE_UUID)?.getCharacteristic(RX_CHAR_UUID) }.getOrNull()
            if (rxChar == null) {
                log("RX characteristic vanished mid-transfer")
                writeQueue = null
                closeGatt(g)
                return
            }
            val sentOpcode = sent?.firstOrNull()?.toInt()?.and(0xff)
            val guardMs = if (!deliveredAsBundle) 0L else when (sentOpcode) {
                SidecarFrame.OP_BEGIN_PAGE -> PAGE_BEGIN_GUARD_MS
                SidecarFrame.OP_END -> PAGE_END_GUARD_MS
                else -> 0L
            }
            if (guardMs > 0L) {
                mainHandler.postDelayed({ if (gatt === g) drainQueue(g, rxChar) }, guardMs)
            } else {
                drainQueue(g, rxChar)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(g: BluetoothGatt) {
        if (gatt === g) {
            gatt = null
            writeQueue = null
            awaitingRenderConfirmation = false
            statusBaseline = null
        }
        runCatching { g.disconnect() }
        runCatching { g.close() }
        cancelOperationTimeout()
        busy.set(false)
        runOnUiThread {
            pageLabel.text = if (deliverySucceeded) "DELIVERED TO X3" else "SEND FAILED - RETRY"
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        ++recipeRecognitionId
        activeRecipeRecognition?.close()
        activeRecipeRecognition = null
        cancelScanTimeout()
        cancelOperationTimeout()
        stopScan()

        val oldGatt = gatt
        gatt = null
        writeQueue = null
        awaitingRenderConfirmation = false
        statusBaseline = null
        oldGatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        super.onDestroy()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            val lines = (logView.text.toString() + message).lines().takeLast(80)
            logView.text = lines.joinToString("\n") + "\n"
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
