package app.mangalens.capture

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.mangalens.MainActivity
import app.mangalens.MangaLensApp
import app.mangalens.R
import app.mangalens.ocr.OcrEngine
import app.mangalens.overlay.OverlayController
import app.mangalens.overlay.RenderBubble
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.SettingsRepository
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the MediaProjection capture and drives the live
 * translation loop:
 *
 *   SCANNING --(screen stable)--> TRANSLATING --(done)--> SHOWING
 *      ^                                                     |
 *      +----------------(scroll / motion detected)-----------+
 *
 * While SHOWING, overlays sit on top of the page; any scroll clears them
 * instantly so the reader never sees misplaced text. Because overlays are
 * cleared before every capture, the OCR never sees our own English output.
 */
class ScreenCaptureService : Service(), OverlayController.Listener {

    companion object {
        const val ACTION_START = "app.mangalens.action.START"
        const val ACTION_STOP = "app.mangalens.action.STOP"
        const val ACTION_TRANSLATE_NOW = "app.mangalens.action.TRANSLATE_NOW"
        const val ACTION_TOGGLE_PAUSE = "app.mangalens.action.TOGGLE_PAUSE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val NOTIF_ID = 41
        private const val MOTION_THRESHOLD = 3.6

        val running = MutableStateFlow(false)
    }

    private enum class State { SCANNING, TRANSLATING, SHOWING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepo: SettingsRepository

    @Volatile private var settings = AppSettings()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var controller: OverlayController? = null
    private val ocr = OcrEngine()
    private val cache = TranslationCache()
    private val translation = TranslationService(cache)
    private val pipeline = TranslatePipeline(ocr, translation)

    private val frameLock = Any()
    private var latestBitmap: Bitmap? = null
    private var prevThumb: IntArray? = null

    @Volatile private var lastMotionAt = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var suppressUntil = 0L
    @Volatile private var state = State.SCANNING
    @Volatile private var paused = false

    private var translateJob: Job? = null
    private var lastShown: List<RenderBubble> = emptyList()
    private var capW = 0
    private var capH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        scope.launch {
            settingsRepo.flow.collect { s ->
                settings = s
                controller?.bubbleView?.let { v ->
                    v.textScale = s.textScale
                    v.bgOpacity = s.bgOpacity
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (code != Activity.RESULT_OK || data == null) {
                    stopSelf()
                } else {
                    startAsForeground()
                    startProjection(code, data)
                }
            }
            ACTION_STOP -> stopSelf()
            ACTION_TRANSLATE_NOW -> onTranslateNow()
            ACTION_TOGGLE_PAUSE -> onTogglePause()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        )
    }

    private fun startProjection(code: Int, data: Intent) {
        if (projection != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopSelf()
            return
        }
        projection = mp
        val thread = HandlerThread("mangalens-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopSelf() }
            }
        }, captureHandler)
        setupDisplay()
        controller = OverlayController(this, this).also { it.attach() }
        controller?.bubbleView?.let { v ->
            v.textScale = settings.textScale
            v.bgOpacity = settings.bgOpacity
        }
        running.value = true
        startTicker()
        setPill("MangaLens is live — open your manhwa", 2600)
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.configuration.densityDpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    private fun setupDisplay() {
        val (w, h, dpi) = displaySize()
        capW = w
        capH = h
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, captureHandler)
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection?.createVirtualDisplay(
                "mangalens",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )
        } else {
            // Android 14+ allows only one createVirtualDisplay per projection,
            // so rotations are handled by resizing the existing one.
            vd.resize(w, h, dpi)
            vd.surface = reader.surface
        }
        imageReader?.close()
        imageReader = reader
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        val (w, h, _) = displaySize()
        if (w != capW || h != capH) {
            translateJob?.cancel()
            state = State.SCANNING
            controller?.bubbleView?.clear()
            synchronized(frameLock) {
                latestBitmap?.recycle()
                latestBitmap = null
            }
            prevThumb = null
            setupDisplay()
        }
    }

    /** Runs on the capture HandlerThread. */
    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            lastFrameAt = now
            val bmp = imageToBitmap(image)
            val thumb = FrameStability.grayThumb(bmp)
            val diff = FrameStability.meanDiff(prevThumb, thumb)
            prevThumb = thumb
            synchronized(frameLock) {
                latestBitmap?.recycle()
                latestBitmap = bmp
            }
            if (now < suppressUntil) return
            if (diff > MOTION_THRESHOLD) {
                lastMotionAt = now
                if (state != State.SCANNING) scope.launch { onMotion() }
            }
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return bmp
        val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
        bmp.recycle()
        return cropped
    }

    private fun onMotion() {
        when (state) {
            State.TRANSLATING -> {
                translateJob?.cancel()
                state = State.SCANNING
                controller?.bubbleView?.clear()
                setPill(null)
            }
            State.SHOWING -> {
                state = State.SCANNING
                controller?.bubbleView?.clear()
                setPill(null)
            }
            State.SCANNING -> Unit
        }
    }

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                delay(120)
                if (paused || settings.mode == CaptureMode.MANUAL) continue
                if (projection == null || state != State.SCANNING) continue
                val now = SystemClock.uptimeMillis()
                if (lastFrameAt > 0 &&
                    now >= suppressUntil &&
                    now - lastMotionAt >= settings.stabilityMs
                ) {
                    startTranslate(auto = true)
                }
            }
        }
    }

    private fun startTranslate(auto: Boolean) {
        if (state == State.TRANSLATING) return
        state = State.TRANSLATING
        translateJob = scope.launch {
            try {
                val bmp = grabCleanBitmap()
                if (bmp == null) {
                    state = State.SCANNING
                    return@launch
                }
                setPill("translating…")
                val result = withContext(Dispatchers.Default) { pipeline.process(bmp, settings) }
                bmp.recycle()
                if (!isActive) return@launch
                lastShown = result.bubbles
                suppressUntil = SystemClock.uptimeMillis() + 500
                controller?.bubbleView?.setBubbles(result.bubbles)
                state = State.SHOWING
                if (result.bubbles.isEmpty()) {
                    setPill(if (auto) null else "no CJK text found", 1800)
                } else {
                    val extra = result.note?.let { " · $it" } ?: ""
                    setPill("✓ ${result.bubbles.size} · ${result.engineLabel}$extra", 2400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.SHOWING
                setPill("⚠ " + (e.message?.take(90) ?: "translation failed"), 4500)
            }
        }
    }

    /**
     * Returns a copy of the newest frame with our own overlays guaranteed absent.
     * If overlays are visible they are cleared first and we wait for a fresh,
     * clean frame to arrive.
     */
    private suspend fun grabCleanBitmap(): Bitmap? {
        val hadOverlays = controller?.bubbleView?.hasBubbles() == true
        if (hadOverlays) {
            controller?.bubbleView?.clear()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(280)
        }
        return synchronized(frameLock) {
            latestBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun setPill(text: String?, autoHideMs: Long = 0) {
        controller?.setStatus(text, autoHideMs)
    }

    // ---- OverlayController.Listener (all invoked on main thread) ----

    override fun onTranslateNow() {
        if (projection == null || state == State.TRANSLATING) return
        startTranslate(auto = false)
    }

    override fun onTogglePause() {
        paused = !paused
        if (paused) {
            translateJob?.cancel()
            state = State.SCANNING
            controller?.bubbleView?.clear()
            setPill("paused", 1600)
        } else {
            setPill("live", 1200)
        }
        controller?.setPaused(paused)
        updateNotification()
    }

    override fun onToggleMode() {
        val next = if (settings.mode == CaptureMode.AUTO) CaptureMode.MANUAL else CaptureMode.AUTO
        scope.launch { settingsRepo.setMode(next) }
        setPill(if (next == CaptureMode.AUTO) "auto-live mode" else "tap the button to translate", 2200)
    }

    override fun onPeek() {
        val shown = lastShown
        if (shown.isEmpty() || state != State.SHOWING) return
        scope.launch {
            controller?.bubbleView?.clear()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(4000)
            if (state == State.SHOWING) {
                suppressUntil = SystemClock.uptimeMillis() + 900
                controller?.bubbleView?.setBubbles(shown)
            }
        }
    }

    override fun onOpenSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    override fun onStopRequested() {
        stopSelf()
    }

    override fun isPaused() = paused

    override fun isAutoMode() = settings.mode == CaptureMode.AUTO

    // ---- notification ----

    private fun buildNotification(): Notification {
        fun serviceIntent(action: String, req: Int): PendingIntent = PendingIntent.getService(
            this, req,
            Intent(this, ScreenCaptureService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val open = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, MangaLensApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setContentTitle("MangaLens is translating your screen")
            .setContentText(if (paused) "Paused" else "Live — bubbles translate as you read")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", serviceIntent(ACTION_TOGGLE_PAUSE, 2))
            .addAction(0, "Stop", serviceIntent(ACTION_STOP, 1))
            .build()
    }

    private fun updateNotification() {
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification()) }
    }

    override fun onDestroy() {
        running.value = false
        translateJob?.cancel()
        scope.cancel()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        controller?.detach()
        controller = null
        captureThread?.quitSafely()
        captureThread = null
        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        super.onDestroy()
    }
}
