package com.jointheparty.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jointheparty.app.JoinThePartyApplication
import com.jointheparty.app.MainActivity
import com.jointheparty.app.R
import com.jointheparty.app.debug.DebugLog
import com.jointheparty.app.ui.session.SessionPhase
import com.jointheparty.app.ui.session.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * INT-06b (technical-requirements.md §2.5, arch §9): a mic-type foreground
 * service owning ONLY foreground lifetime + notification. It never builds or
 * holds [com.jointheparty.app.session.SessionGraph] and is never the thing
 * that decides the session is over — it just reflects [SyncState] that
 * `SessionGraph`'s [com.jointheparty.app.ui.session.SessionViewModel]
 * already produces, and calls `SessionViewModel.reset()` on it when the user
 * taps the notification's Stop action. `close()`-the-engine remains solely
 * `SessionGraph`'s call (single-owner rule, §2.5) — this class has no
 * reference to the graph beyond `viewModel`.
 *
 * A plain [Service], not `LifecycleService`: one manual collector doesn't
 * justify pulling in `androidx.lifecycle:lifecycle-service` (§4 dependency
 * policy — no new dependency for INT-06). The service's own
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` stands in for a
 * lifecycle scope and is cancelled in [onDestroy].
 *
 * Start/stop is a one-way push, one-way pull split: [com.jointheparty.app
 * .session.SessionGraph] is the only caller of [start] (pushed on the
 * idle/error → active phase transition); this service is the only caller of
 * [stopSelf] (pulled from observing [SyncState.phase] return to `IDLE` or
 * `ERROR`). That keeps exactly one stop decision point instead of two
 * components racing to decide the session is over.
 */
class SessionForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one live syncState collector. A start intent can land on a
     * still-running instance (a rejoin racing the previous stopSelf), in
     * which case onStartCommand runs again on the same instance — cancel
     * the old collector before launching its replacement, or they pile up.
     */
    private var stateJob: kotlinx.coroutines.Job? = null

    /** Whether THIS instance reached startForeground — see ACTION_STOP. */
    private var foregrounded = false

    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // On a running instance the phase collector below observes the
            // resulting IDLE state and stops this service itself — see the
            // start/stop split in the class doc. But a Stop tap on a stale
            // notification can also spawn a FRESH instance just to deliver
            // this action; that instance has no collector and never went
            // foreground, so it must end itself here or it lingers.
            (application as JoinThePartyApplication).sessionGraph.viewModel.reset()
            if (!foregrounded) stopSelf()
            return START_NOT_STICKY
        }

        val graph = (application as JoinThePartyApplication).sessionGraph
        val started = tryStartForeground(buildNotification(graph.viewModel.syncState.value))
        if (!started) {
            stopSelf()
            // A restarted service with no session to show would be a lie —
            // the session state lives in SessionGraph, and if the process
            // died the session died with it (§2.5).
            return START_NOT_STICKY
        }
        foregrounded = true

        stateJob?.cancel()
        stateJob = graph.viewModel.syncState
            .distinctUntilChangedBy { it.phase to (it.track?.let { t -> t.title to t.artist }) }
            .onEach { state ->
                if (state.phase == SessionPhase.IDLE || state.phase == SessionPhase.ERROR) {
                    // ERROR is terminal per the state machine (§2.4) — the
                    // notification disappearing plus the in-app error screen
                    // is the intended UX, not a retrying notification.
                    stopSelf()
                } else {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
            .launchIn(scope)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * RECORD_AUDIO defensive check: starting a microphone-type foreground
     * service without the permission throws `SecurityException` on API 34+.
     * In practice `MainActivity` guarantees the permission before a session
     * can start, so this is a belt-and-suspenders catch, not the primary
     * gate.
     */
    private fun tryStartForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        true
    } catch (e: SecurityException) {
        DebugLog.log("SessionForegroundService: startForeground denied (${e.message})")
        false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /**
     * Content per the §2.5 phase→text mapping table, exactly. Track title/
     * artist are only shown for the phases where a track is actually being
     * chased/held (aiming/converging/locked/drifting) — see the table.
     */
    private fun buildNotification(state: SyncState): Notification {
        val text = when (state.phase) {
            SessionPhase.IDLE, SessionPhase.LISTENING -> "Listening for a track…"
            SessionPhase.MATCHING -> "Matching…"
            SessionPhase.AIMING, SessionPhase.CONVERGING ->
                "Syncing — ${state.track?.title} · ${state.track?.artist}"
            SessionPhase.LOCKED -> "Synced — ${state.track?.title} · ${state.track?.artist}"
            SessionPhase.DRIFTING -> "Re-syncing — ${state.track?.title} · ${state.track?.artist}"
            SessionPhase.LOST -> "Lost the track — retrying"
            SessionPhase.NEEDS_SPOTIFY, SessionPhase.NEEDS_PREMIUM, SessionPhase.ERROR ->
                "Action needed — open JoinTheParty"
        }

        // Manifest launch intent pattern: reuses MainActivity's declared
        // LAUNCHER intent-filter (action MAIN, category LAUNCHER) rather
        // than constructing an ad hoc Activity intent by hand, and brings
        // the existing task forward instead of spawning a new one.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
            ?: Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, SessionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JoinTheParty")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.jointheparty.app.service.action.STOP"
        private const val CHANNEL_ID = "session"

        /** Started only from a foreground Join tap (see SessionGraph). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionForegroundService::class.java))
        }
    }
}
