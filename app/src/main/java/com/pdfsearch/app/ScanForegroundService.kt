package com.pdfsearch.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs PdfSearchEngine.runScan() as a foreground service so the scan keeps going
 * even if the user leaves the app, locks the phone, or Android reclaims the Activity.
 * Progress/results are published to ScanRepository, which MainActivity observes
 * whenever it's visible. Progress is also checkpointed to disk after every file via
 * CheckpointManager (for crash recovery mid-scan), and the full result set is saved
 * via LastScanManager once a scan completes (so finished results also survive the
 * app being killed before you get around to exporting them).
 */
class ScanForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Cooperative stop flag — see PdfSearchEngine's doc comment for why this is used
    // instead of actually cancelling the coroutine.
    @Volatile private var stopRequested = false

    companion object {
        const val CHANNEL_ID = "pdf_scan_channel"
        const val NOTIF_ID = 1001

        const val ACTION_START = "com.pdfsearch.app.action.START"
        const val ACTION_RESUME = "com.pdfsearch.app.action.RESUME"
        const val ACTION_STOP = "com.pdfsearch.app.action.STOP"

        const val EXTRA_FOLDER_URI = "extra_folder_uri"
        const val EXTRA_KEYWORDS = "extra_keywords"     // raw tokens, "=phrase" means exact mode
        const val EXTRA_IGNORE = "extra_ignore"
        const val EXTRA_CASE_SENSITIVE = "extra_case_sensitive"
        const val EXTRA_RECURSIVE = "extra_recursive"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                val checkpoint = CheckpointManager.load(applicationContext)
                if (checkpoint == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                beginScan(
                    folderUri = Uri.parse(checkpoint.folderUri),
                    rawKeywords = checkpoint.keywords,
                    ignore = checkpoint.ignore,
                    caseSensitive = checkpoint.caseSensitive,
                    recursive = checkpoint.recursive,
                    resumeDoneUris = checkpoint.doneUris.toSet(),
                    resumeRecords = checkpoint.records,
                    elapsedSoFar = checkpoint.elapsedSec
                )
            }
            else -> { // ACTION_START or no action (treat as a fresh start)
                val folderUriStr = intent?.getStringExtra(EXTRA_FOLDER_URI)
                if (folderUriStr == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                CheckpointManager.clear(applicationContext) // a fresh scan invalidates any old checkpoint
                beginScan(
                    folderUri = Uri.parse(folderUriStr),
                    rawKeywords = intent.getStringArrayExtra(EXTRA_KEYWORDS)?.toList().orEmpty(),
                    ignore = intent.getStringArrayExtra(EXTRA_IGNORE)?.toList().orEmpty(),
                    caseSensitive = intent.getBooleanExtra(EXTRA_CASE_SENSITIVE, false),
                    recursive = intent.getBooleanExtra(EXTRA_RECURSIVE, false),
                    resumeDoneUris = emptySet(),
                    resumeRecords = emptyList(),
                    elapsedSoFar = 0.0
                )
            }
        }
        return START_NOT_STICKY
    }

    private fun beginScan(
        folderUri: Uri,
        rawKeywords: List<String>,
        ignore: List<String>,
        caseSensitive: Boolean,
        recursive: Boolean,
        resumeDoneUris: Set<String>,
        resumeRecords: List<PdfResult>,
        elapsedSoFar: Double
    ) {
        val keywords = rawKeywords.map { token ->
            if (token.startsWith("=")) {
                KeywordEntry(token.removePrefix("=").trim(), MatchMode.EXACT)
            } else {
                KeywordEntry(token, MatchMode.FLEXIBLE)
            }
        }

        stopRequested = false
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildProgressNotification("Starting scan…", 0, 0))

        if (resumeRecords.isNotEmpty() || resumeDoneUris.isNotEmpty()) {
            ScanRepository.resume(
                initialResults = resumeRecords,
                matchedSoFar = resumeRecords.count { it.hasMatches },
                elapsedSoFar = elapsedSoFar
            )
        } else {
            ScanRepository.start()
        }

        val cfg = ScanConfig(folderUri, keywords, caseSensitive, ignore, recursive)
        val t0 = System.currentTimeMillis()

        serviceScope.launch {
            val outcome = PdfSearchEngine.runScan(
                context = applicationContext,
                cfg = cfg,
                resumeDoneUris = resumeDoneUris,
                resumeRecords = resumeRecords,
                isStopRequested = { stopRequested },
                onProgress = { progress ->
                    ScanRepository.updateProgress(progress)
                    notifySafely(buildProgressNotification(
                        "${progress.current}/${progress.total} — ${progress.currentFileName}",
                        progress.current, progress.total
                    ))
                },
                onRecord = { record -> ScanRepository.addResult(record) },
                onCheckpoint = { doneUris, matchedRecords ->
                    val elapsedNow = elapsedSoFar + (System.currentTimeMillis() - t0) / 1000.0
                    CheckpointManager.save(
                        context = applicationContext,
                        folderUri = folderUri,
                        rawKeywordTokens = rawKeywords,
                        ignore = ignore,
                        caseSensitive = caseSensitive,
                        recursive = recursive,
                        doneUris = doneUris,
                        elapsedSec = elapsedNow,
                        matchedRecords = matchedRecords
                    )
                }
            )

            val elapsed = elapsedSoFar + (System.currentTimeMillis() - t0) / 1000.0
            val matched = outcome.records.count { it.hasMatches }

            if (outcome.stoppedEarly) {
                ScanRepository.pause(elapsed, matched)
                notifySafely(buildPausedNotification(outcome.doneUris.size, matched))
            } else {
                ScanRepository.finish(elapsed, matched)
                CheckpointManager.clear(applicationContext) // completed — no need to resume anymore
                LastScanManager.save(applicationContext, folderUri, elapsed, matched, outcome.records)
                notifySafely(buildDoneNotification(outcome.records.size, matched))
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "PDF scan progress", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopActionIntent(): PendingIntent {
        val intent = Intent(this, ScanForegroundService::class.java).apply { action = ACTION_STOP }
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildProgressNotification(text: String, progress: Int, max: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_scanning_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max.coerceAtLeast(1), progress, max == 0)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.notif_action_stop), stopActionIntent())
            .build()
    }

    private fun buildPausedNotification(doneCount: Int, matched: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_paused_title))
            .setContentText(getString(R.string.notif_paused_text, doneCount, matched))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun buildDoneNotification(total: Int, matched: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_done_title))
            .setContentText(getString(R.string.notif_done_text, matched, total))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    /** Posting a notification needs POST_NOTIFICATIONS on Android 13+; skip quietly if denied — the scan still runs either way. */
    private fun notifySafely(notification: Notification) {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
        }
    }
}
