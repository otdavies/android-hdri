package app.hdri.processing

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.hdri.MainActivity
import app.hdri.R
import app.hdri.data.SessionStore
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProcessingStatus(
    val id: String? = null,
    val stage: String = "",
    val progress: Double = 0.0,
    val running: Boolean = false,
)

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var store: SessionStore
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastReport = 0L

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "HDR processing", NotificationManager.IMPORTANCE_LOW)
            )
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("id") ?: return START_NOT_STICKY
        if (job?.isActive == true) return START_NOT_STICKY
        val notification = notification("Starting on-device processing", 0.0, true)
        val type =
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIFICATION, notification, type)
        mutable.value = ProcessingStatus(id, "Starting on-device processing", 0.0, true)
        job =
            scope.launch {
                val context = currentCoroutineContext()
                val power = getSystemService(PowerManager::class.java)
                try {
                    wakeLock =
                        power
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumaSphere:processing")
                            .apply { acquire(30 * 60 * 1000L) }
                    var p =
                        store.update(id) {
                            it.copy(
                                state = "processing",
                                stage = "Checking saved photos",
                                error = null,
                                progress = 0.0,
                            )
                        }
                    var thermalSince = 0L
                    val check = {
                        context.ensureActive()
                        while (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                            context.ensureActive()
                            if (thermalSince == 0L) thermalSince = SystemClock.elapsedRealtime()
                            report(
                                id,
                                "Cooling the phone · processing will resume",
                                mutable.value.progress,
                            )
                            check(SystemClock.elapsedRealtime() - thermalSince < 10 * 60 * 1000) {
                                "The phone needs more time to cool. Retry processing later; captures are saved."
                            }
                            Thread.sleep(500)
                        }
                        thermalSince = 0L
                    }
                    if (p.sample && p.captures.isEmpty())
                        p =
                            SampleCapture.create(
                                store,
                                p,
                                { stage, value -> report(id, stage, value * .08) },
                                check,
                            )
                    val sampleOffset = if (p.sample) .08 else 0.0
                    HdrPipeline(
                            store,
                            p,
                            { stage, value ->
                                report(id, stage, sampleOffset + (1 - sampleOffset) * value)
                            },
                            check,
                        )
                        .run()
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION, notification("Your HDR sphere is ready", 1.0, false))
                } catch (e: CancellationException) {
                    store.update(id) {
                        it.copy(state = "paused", stage = "Processing paused", error = null)
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(
                            NOTIFICATION,
                            notification(
                                "Processing paused · originals are saved",
                                mutable.value.progress,
                                false,
                            ),
                        )
                } catch (e: Exception) {
                    store.update(id) {
                        it.copy(
                            state = "failed",
                            stage = "Processing needs attention",
                            error =
                                e.message ?: "Processing stopped. Your original captures are saved.",
                        )
                    }
                    getSystemService(NotificationManager::class.java)
                        .notify(
                            NOTIFICATION,
                            notification(
                                "Processing needs attention · tap to open",
                                mutable.value.progress,
                                false,
                            ),
                        )
                } finally {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                    wakeLock = null
                    mutable.value = mutable.value.copy(running = false)
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    private fun report(id: String, stage: String, progress: Double) {
        val now = SystemClock.elapsedRealtime()
        val next = max(mutable.value.progress, progress.coerceIn(0.0, 1.0))
        if (now - lastReport < 250 && stage == mutable.value.stage && next < 1) return
        lastReport = now
        mutable.value = ProcessingStatus(id, stage, next, true)
        store.update(id) { it.copy(stage = stage, progress = next) }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(stage, next, true))
    }

    private fun notification(stage: String, progress: Double, running: Boolean): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ProcessingService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sphere)
            .setContentTitle("Luma Sphere")
            .setContentText(stage)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .apply {
                if (running) {
                    setProgress(100, (progress * 100).roundToInt(), progress == 0.0)
                    addAction(0, "Pause", cancel)
                }
            }
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "processing"
        private const val NOTIFICATION = 42
        private const val CANCEL = "app.hdri.PAUSE"
        private val mutable = MutableStateFlow(ProcessingStatus())
        val status = mutable.asStateFlow()

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProcessingService::class.java).putExtra("id", id),
            )
        }

        fun pause(context: Context) {
            context.startService(Intent(context, ProcessingService::class.java).setAction(CANCEL))
        }
    }
}
