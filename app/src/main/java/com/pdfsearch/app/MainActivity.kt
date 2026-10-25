package com.pdfsearch.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvFolder: TextView
    private lateinit var etKeywords: EditText
    private lateinit var etIgnore: EditText
    private lateinit var cbCaseSensitive: CheckBox
    private lateinit var cbRecursive: CheckBox
    private lateinit var btnPickFolder: View
    private lateinit var btnScan: View
    private lateinit var btnStop: View
    private lateinit var btnExportCsv: View
    private lateinit var btnExportTxt: View
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvSummary: TextView
    private lateinit var etFilter: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var resumeBanner: LinearLayout
    private lateinit var tvResumeSubtitle: TextView
    private lateinit var btnResume: View
    private lateinit var btnDiscard: View

    private var folderUri: Uri? = null
    private lateinit var adapter: ResultsAdapter
    private var pendingAction: (() -> Unit)? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            folderUri = uri
            tvFolder.text = uri.path ?: uri.toString()
            btnScan.isEnabled = true
        }
    }

    // Android 13+ requires this permission for the scan's progress notification to show.
    // The scan itself still runs in the background even if this is denied.
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingAction?.invoke()
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContentView(R.layout.activity_main)

        tvFolder = findViewById(R.id.tv_folder)
        etKeywords = findViewById(R.id.et_keywords)
        etIgnore = findViewById(R.id.et_ignore)
        cbCaseSensitive = findViewById(R.id.cb_case_sensitive)
        cbRecursive = findViewById(R.id.cb_recursive)
        btnPickFolder = findViewById(R.id.btn_pick_folder)
        btnScan = findViewById(R.id.btn_scan)
        btnStop = findViewById(R.id.btn_stop)
        btnExportCsv = findViewById(R.id.btn_export_csv)
        btnExportTxt = findViewById(R.id.btn_export_txt)
        progressBar = findViewById(R.id.progress_bar)
        tvProgress = findViewById(R.id.tv_progress)
        tvSummary = findViewById(R.id.tv_summary)
        etFilter = findViewById(R.id.et_filter)
        recyclerView = findViewById(R.id.recycler_view)
        resumeBanner = findViewById(R.id.resume_banner)
        tvResumeSubtitle = findViewById(R.id.tv_resume_subtitle)
        btnResume = findViewById(R.id.btn_resume)
        btnDiscard = findViewById(R.id.btn_discard)

        adapter = ResultsAdapter(
            items = mutableListOf(),
            onStateChanged = { updateSummary() },
            onOpenPdf = { result -> openPdf(result) },
            onUnlockRequested = { result -> showPasswordDialog(result) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnScan.isEnabled = false
        btnExportCsv.isEnabled = false
        btnExportTxt.isEnabled = false

        restoreLastUsedSettings()

        btnPickFolder.setOnClickListener { folderPicker.launch(null) }
        btnScan.setOnClickListener {
            // If a scan is currently paused, this button means "continue it," not "start
            // a new one" — starting fresh here would wipe out the paused results before
            // you got a chance to export them.
            if (ScanRepository.state.value.paused) requestResume() else requestScan()
        }
        btnStop.setOnClickListener { stopScan() }
        btnExportCsv.setOnClickListener { exportAndShare(isCsv = true) }
        btnExportTxt.setOnClickListener { exportAndShare(isCsv = false) }
        btnResume.setOnClickListener { requestResume() }
        btnDiscard.setOnClickListener { discardCheckpoint() }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Observe the shared scan state (written by ScanForegroundService) the whole
        // time this screen is visible, so results/progress stay live whether the scan
        // was started just now or is already running from before this Activity existed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScanRepository.state.collectLatest { state -> renderState(state) }
            }
        }
    }

    /** Fills in the folder/keywords/options remembered from the last scan, if the folder's access is still valid. */
    private fun restoreLastUsedSettings() {
        val savedFolder = PrefsManager.getFolderUri(this)
        if (savedFolder != null) {
            val uri = Uri.parse(savedFolder)
            if (hasValidFolderPermission(this, uri)) {
                folderUri = uri
                tvFolder.text = uri.path ?: uri.toString()
                btnScan.isEnabled = true
            } else {
                // Permission was revoked since last time — don't silently pretend we still have it.
                PrefsManager.clearFolder(this)
            }
        }
        etKeywords.setText(PrefsManager.getKeywordsText(this))
        etIgnore.setText(PrefsManager.getIgnoreText(this))
        cbCaseSensitive.isChecked = PrefsManager.getCaseSensitive(this)
        cbRecursive.isChecked = PrefsManager.getRecursive(this)
    }

    override fun onResume() {
        super.onResume()
        checkForResumableScan()
        loadLastCompletedScanIfNeeded()
        // Folder permission can be revoked at any time (user clears it in system Settings,
        // clears app storage, etc.) — recheck whenever the screen becomes visible again.
        folderUri?.let { uri ->
            if (!hasValidFolderPermission(this, uri)) {
                folderUri = null
                tvFolder.text = getString(R.string.toast_permission_lost)
                btnScan.isEnabled = false
                Toast.makeText(this, R.string.toast_permission_lost, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Looks for a leftover checkpoint from a crash/kill and offers to continue it. */
    private fun checkForResumableScan() {
        // Don't bother the user with a resume prompt if a scan is already running/paused
        // right now — renderState() already covers those cases.
        if (ScanRepository.state.value.isRunning || ScanRepository.state.value.paused) {
            resumeBanner.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val checkpoint = withContext(Dispatchers.IO) { CheckpointManager.load(applicationContext) }
            if (checkpoint != null) {
                resumeBanner.visibility = View.VISIBLE
                val matched = checkpoint.records.count { it.hasMatches }
                tvResumeSubtitle.text = getString(
                    R.string.resume_banner_subtitle_format,
                    Uri.parse(checkpoint.folderUri).lastPathSegment ?: checkpoint.folderUri,
                    checkpoint.doneUris.size,
                    matched
                )
            } else {
                resumeBanner.visibility = View.GONE
            }
        }
    }

    /** If there's no active/paused/finished scan state yet, load the last completed scan back in. */
    private fun loadLastCompletedScanIfNeeded() {
        val current = ScanRepository.state.value
        if (current.isRunning || current.paused || current.finished || current.results.isNotEmpty()) return
        lifecycleScope.launch {
            val last = withContext(Dispatchers.IO) { LastScanManager.load(applicationContext) }
            if (last != null) {
                ScanRepository.loadCompleted(last.records, last.matched, last.elapsedSec)
            }
        }
    }

    private fun discardCheckpoint() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { CheckpointManager.clear(applicationContext) }
            resumeBanner.visibility = View.GONE
            Toast.makeText(this@MainActivity, R.string.toast_discarded, Toast.LENGTH_SHORT).show()
        }
    }

    /** Raw keyword tokens as typed, "=phrase" preserved for exact-mode encoding. */
    private fun parseKeywordTokens(): List<String> =
        etKeywords.text.toString()
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun parseKeywords(): List<KeywordEntry> = parseKeywordTokens().map { token ->
        if (token.startsWith("=")) {
            KeywordEntry(token.removePrefix("=").trim(), MatchMode.EXACT)
        } else {
            KeywordEntry(token, MatchMode.FLEXIBLE)
        }
    }

    private fun requestScan() {
        val folder = folderUri
        if (folder == null) {
            Toast.makeText(this, R.string.toast_pick_folder_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasValidFolderPermission(this, folder)) {
            Toast.makeText(this, R.string.toast_permission_lost, Toast.LENGTH_LONG).show()
            folderUri = null
            btnScan.isEnabled = false
            return
        }
        val keywords = parseKeywords()
        if (keywords.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_keyword, Toast.LENGTH_SHORT).show()
            return
        }
        withNotificationPermission { launchScanService(folder) }
    }

    private fun requestResume() {
        resumeBanner.visibility = View.GONE
        withNotificationPermission { launchResumeService() }
    }

    private fun stopScan() {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_STOP
        }
        startService(intent)
    }

    private fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    /** Starts a brand-new scan as a foreground service — it keeps running even if this screen closes. */
    private fun launchScanService(folder: Uri) {
        ScanRepository.reset()
        adapter.setData(emptyList())
        tvSummary.text = ""

        val keywordsText = etKeywords.text.toString()
        val ignoreText = etIgnore.text.toString()
        PrefsManager.saveLastUsed(
            context = this,
            folderUri = folder.toString(),
            keywordsText = keywordsText,
            ignoreText = ignoreText,
            caseSensitive = cbCaseSensitive.isChecked,
            recursive = cbRecursive.isChecked
        )

        val rawKeywords = parseKeywordTokens().toTypedArray()
        val ignore = ignoreText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()

        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_START
            putExtra(ScanForegroundService.EXTRA_FOLDER_URI, folder.toString())
            putExtra(ScanForegroundService.EXTRA_KEYWORDS, rawKeywords)
            putExtra(ScanForegroundService.EXTRA_IGNORE, ignore)
            putExtra(ScanForegroundService.EXTRA_CASE_SENSITIVE, cbCaseSensitive.isChecked)
            putExtra(ScanForegroundService.EXTRA_RECURSIVE, cbRecursive.isChecked)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /** Resumes a scan from the on-disk checkpoint — ScanForegroundService reads the config from it. */
    private fun launchResumeService() {
        val intent = Intent(this, ScanForegroundService::class.java).apply {
            action = ScanForegroundService.ACTION_RESUME
        }
        ContextCompat.startForegroundService(this, intent)
    }

    /** Opens the actual PDF in whatever viewer the user has installed. */
    private fun openPdf(result: PdfResult) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(result.uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_pdf_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    /** Prompts for a password and re-scans just this one file with it. */
    private fun showPasswordDialog(result: PdfResult) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.dialog_password_hint)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_password_title)
            .setMessage(getString(R.string.dialog_password_message, result.fileName))
            .setView(container)
            .setPositiveButton(R.string.btn_unlock_confirm) { _, _ ->
                retryWithPassword(result, input.text.toString())
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun retryWithPassword(result: PdfResult, password: String) {
        lifecycleScope.launch {
            val patterns = parseKeywords().map { it to PdfSearchEngine.buildPattern(it, cbCaseSensitive.isChecked) }
            val updated = withContext(Dispatchers.IO) {
                val doc = DocumentFile.fromSingleUri(applicationContext, result.uri)
                if (doc == null) {
                    null
                } else {
                    PdfSearchEngine.searchSinglePdf(applicationContext, doc, patterns, maxPages = 800, password = password)
                }
            }
            if (updated != null && updated.status != PdfStatus.PASSWORD_PROTECTED && updated.status != PdfStatus.ERROR) {
                updated.saved = result.saved
                ScanRepository.replaceResult(result.uri.toString(), updated)
                Toast.makeText(this@MainActivity, R.string.toast_unlocked, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.toast_wrong_password, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderState(state: ScanRepository.State) {
        adapter.setData(state.results)

        when {
            state.isRunning -> {
                resumeBanner.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                progressBar.max = state.total.coerceAtLeast(1)
                progressBar.progress = state.current
                tvProgress.text = getString(
                    R.string.scan_progress_format,
                    state.current, state.total, state.currentFileName, state.matched, state.errors
                )
                btnScan.visibility = View.GONE
                btnStop.visibility = View.VISIBLE
                btnExportCsv.isEnabled = false
                btnExportTxt.isEnabled = false
            }
            state.paused -> {
                progressBar.visibility = View.GONE
                btnScan.visibility = View.VISIBLE
                btnScan.isEnabled = true // resuming doesn't need a freshly-picked folder
                (btnScan as? android.widget.Button)?.text = getString(R.string.btn_resume_scan)
                btnStop.visibility = View.GONE
                tvProgress.text = getString(R.string.scan_paused_format, state.current.coerceAtLeast(state.results.size), state.matched)
                btnExportCsv.isEnabled = state.matched > 0
                btnExportTxt.isEnabled = state.matched > 0
            }
            else -> {
                progressBar.visibility = View.GONE
                btnScan.visibility = View.VISIBLE
                btnScan.isEnabled = folderUri != null
                (btnScan as? android.widget.Button)?.text = getString(R.string.btn_scan)
                btnStop.visibility = View.GONE
                if (state.finished) {
                    tvProgress.text = getString(
                        R.string.scan_done_format, state.results.size, state.matched, state.elapsedSec
                    )
                    btnExportCsv.isEnabled = state.matched > 0
                    btnExportTxt.isEnabled = state.matched > 0
                }
            }
        }
        updateSummary()
    }

    private fun updateSummary() {
        val matched = adapter.matchedCount()
        val saved = adapter.savedResults().size
        tvSummary.text = getString(R.string.summary_format, matched, saved)
    }

    private fun exportAndShare(isCsv: Boolean) {
        val results = adapter.savedResults().ifEmpty { ScanRepository.state.value.results.filter { it.hasMatches } }
        val file = if (isCsv) {
            ReportExporter.saveCsvReport(this, results)
        } else {
            ReportExporter.saveTxtReport(
                this, results, tvFolder.text.toString(),
                parseKeywords().map { it.keyword }, ScanRepository.state.value.elapsedSec
            )
        }
        shareFile(file, if (isCsv) "text/csv" else "text/plain")
    }

    /** Hands an exported report to the Android share sheet via FileProvider (app-private files can't be shared as raw file:// paths on modern Android). */
    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
    }
}
