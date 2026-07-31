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
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import app.mangalens.translate.WorkMemory
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

        /**
         * Share of the visible page that must differ from the page we
         * translated before it counts as a new page. Readers that turn on a
         * tap swap the page between two frames with no scroll to notice, and
         * the swap is often too gentle for [MOTION_THRESHOLD] — two comic
         * pages are mostly white, so the average difference stays low even
         * when the panels are entirely different.
         */
        private const val PAGE_CHANGE_FRACTION = 0.015

        /**
         * A deliberate slow scroll on a manhwa strip is the motion
         * [MOTION_THRESHOLD] cannot see: mostly-white content slides over
         * mostly-white content, so consecutive frames barely differ, while a
         * new balloon glides in under a card that still shows the previous
         * balloon's line. Row-profile alignment sees the slide directly, so
         * frames are also compared for vertical drift — against a reference
         * this many milliseconds old while scanning, and cumulatively against
         * the translated page while cards are up.
         */
        private const val SLOW_SCROLL_SAMPLE_MS = 420L

        /** Thumb rows of drift (~2% of the screen) that count as scrolling. */
        private const val SLOW_SCROLL_MIN_ROWS = 2

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
    // lazy: these need a Context, which a Service only has after construction
    private val glossary by lazy { GlossaryStore(this) }
    private val cast by lazy { CastBook(this) }

    /**
     * Keeps each series' glossary, cast and story context to itself. Reading
     * several works in a sitting otherwise pools them, and the memory that
     * makes one story consistent then contradicts the next.
     */
    private val works by lazy { WorkMemory(this, glossary, cast) }
    private val translation by lazy { TranslationService(cache, glossary, cast) }
    private val pipeline by lazy { TranslatePipeline(ocr, translation, cache, glossary, cast) }

    private val frameLock = Any()
    private var latestBitmap: Bitmap? = null
    private var prevThumb: IntArray? = null

    /**
     * The page as it looked when we translated it — taken from the very
     * bitmap the pipeline processed, never from a later frame that may
     * already show something else. While overlays are up, every frame is
     * compared against this rather than against the frame before it: a
     * tap-to-turn swap produces one changed frame and then stillness, so
     * frame-to-frame differencing has a single chance to catch it and
     * cumulative comparison has every frame.
     */
    @Volatile private var shownThumb: IntArray? = null

    /**
     * The thumb cells our own cards currently cover. Cards are captured along
     * with the page, so these cells show us, not the reader's content, and
     * every comparison excludes them. Maintained by [paintCards]/[clearCards]
     * so it is always the truth about what is on screen — including the fast
     * draft that paints mid-translation.
     */
    @Volatile private var overlayMask: BooleanArray? = null

    // Reference for slow-scroll drift detection (capture thread only).
    private var slowRefThumb: IntArray? = null
    private var slowRefAt = 0L

    // Reused frame buffers (capture thread only): the display feeds frames at
    // refresh rate, but scroll detection only needs ~12 fps, and allocating a
    // full-screen bitmap per frame melts batteries. One buffer holds the
    // latest complete frame; the other is being written.
    private var frameA: Bitmap? = null
    private var frameB: Bitmap? = null
    private var lastFrameProcessedAt = 0L

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
            shownThumb = null
            clearCards()
            releaseFrameBuffers()
            setupDisplay()
        }
    }

    /** Runs on the capture HandlerThread. */
    private fun onFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            lastFrameAt = now
            // ~12 fps is plenty for a 350 ms stability window; dropping the
            // rest skips a full-screen copy per display frame.
            if (now - lastFrameProcessedAt < 80) return
            lastFrameProcessedAt = now
            val bmp = imageToBitmap(image)
            val thumb = FrameStability.grayThumb(bmp, capW, capH)
            val diff = FrameStability.meanDiff(prevThumb, thumb)
            prevThumb = thumb
            synchronized(frameLock) {
                latestBitmap = bmp
            }
            if (now < suppressUntil) return
            if (diff > MOTION_THRESHOLD) {
                lastMotionAt = now
                slowRefThumb = null
                if (state != State.SCANNING) scope.launch { onMotion() }
                return
            }
            val mask = overlayMask
            // Slow scrolls hide from frame differencing, so drift is measured
            // over a longer baseline: whatever this sampled check misses while
            // cards are up, the cumulative check below accumulates.
            val ref = slowRefThumb
            if (ref == null) {
                slowRefThumb = thumb
                slowRefAt = now
            } else if (now - slowRefAt >= SLOW_SCROLL_SAMPLE_MS) {
                val drift = FrameStability.verticalShift(ref, thumb, mask)
                slowRefThumb = thumb
                slowRefAt = now
                if (kotlin.math.abs(drift) >= SLOW_SCROLL_MIN_ROWS) {
                    lastMotionAt = now
                    if (state != State.SCANNING) scope.launch { onMotion() }
                    return
                }
            }
            if (state == State.SHOWING) {
                // Two ways this page can stop being the page we translated: it
                // was replaced (tap-to-turn swap — cells change in place), or
                // it moved (slow scroll — cells shift, and the balloon that
                // matters most may slide in under a card, changing only masked
                // cells). Check for both against the translated page itself.
                if (FrameStability.changedFraction(shownThumb, thumb, mask) > PAGE_CHANGE_FRACTION ||
                    kotlin.math.abs(FrameStability.verticalShift(shownThumb, thumb, mask)) >= SLOW_SCROLL_MIN_ROWS
                ) {
                    lastMotionAt = now
                    scope.launch { onMotion() }
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * Copies the frame into one of two reused buffers (stride-padded width;
     * cropped only when a translation pass actually grabs it).
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val strideW = plane.rowStride / plane.pixelStride
        val current = synchronized(frameLock) { latestBitmap }
        var target = if (current === frameA && frameA != null) frameB else frameA
        if (target == null || target.width != strideW || target.height != image.height) {
            target?.recycle()
            target = Bitmap.createBitmap(strideW, image.height, Bitmap.Config.ARGB_8888)
            if (current === frameA && frameA != null) frameB = target else frameA = target
        }
        plane.buffer.rewind()
        target.copyPixelsFromBuffer(plane.buffer)
        return target
    }

    /** Serialized onto the capture thread so a buffer is never freed mid-write. */
    private fun releaseFrameBuffers() {
        val action = Runnable {
            synchronized(frameLock) { latestBitmap = null }
            frameA?.recycle()
            frameB?.recycle()
            frameA = null
            frameB = null
            prevThumb = null
            slowRefThumb = null
            slowRefAt = 0L
        }
        val handler = captureHandler
        if (handler != null && handler.looper.thread.isAlive &&
            Thread.currentThread() !== handler.looper.thread
        ) {
            handler.post(action)
        } else {
            action.run()
        }
    }

    private fun onMotion() {
        when (state) {
            State.TRANSLATING -> {
                translateJob?.cancel()
                state = State.SCANNING
                shownThumb = null
                clearCards()
                setPill(null)
            }
            State.SHOWING -> {
                state = State.SCANNING
                shownThumb = null
                clearCards()
                setPill(null)
            }
            State.SCANNING -> Unit
        }
    }

    /**
     * Paints cards and records the cells they cover, in one step — the mask
     * must never describe cards other than the ones actually on screen.
     * Main thread only.
     */
    private fun paintCards(bubbles: List<RenderBubble>) {
        val view = controller?.bubbleView ?: return
        view.setBubbles(bubbles)
        overlayMask = if (bubbles.isEmpty()) null else FrameStability.mask(view.placedRects(), capW, capH)
    }

    /** Clears cards and the mask that described them. Main thread only. */
    private fun clearCards() {
        controller?.bubbleView?.clear()
        overlayMask = null
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
                // A long enough break since the last translated page means the
                // next one probably belongs to a different series.
                works.beginPass(System.currentTimeMillis())
                shownThumb = null
                val bmp = grabCleanBitmap()
                if (bmp == null) {
                    state = State.SCANNING
                    return@launch
                }
                setPill("translating…")
                val exclusions = controller?.overlayExclusions() ?: emptyList()
                val result = try {
                    withContext(Dispatchers.Default) {
                        // Remember the page being translated from the exact
                        // bitmap handed to the pipeline. Waiting for a later
                        // "settled" frame instead left a hole: a fling inside
                        // the suppression window put a new page on screen
                        // first, the baseline then described the new page
                        // while the cards described the old one, and the
                        // mismatch could never be noticed.
                        shownThumb = FrameStability.grayThumbOf(bmp)
                        pipeline.process(bmp, settings, exclusions) { partial ->
                            // Fast path landed — paint it now, AI polish follows.
                            withContext(Dispatchers.Main.immediate) {
                                if (isActive && state == State.TRANSLATING) {
                                    lastShown = partial.bubbles
                                    suppressUntil = SystemClock.uptimeMillis() + 600
                                    paintCards(partial.bubbles)
                                    setPill("✓ ${partial.bubbles.size} · ${partial.engineLabel} · ✨ upgrading…")
                                }
                            }
                        }
                    }
                } finally {
                    // Cancellation is this loop's steady state — every scroll
                    // that interrupts a pass lands here — so the full-screen
                    // copy is reclaimed on that path too, not only on success.
                    bmp.recycle()
                }
                if (!isActive) return@launch
                lastShown = result.bubbles
                suppressUntil = SystemClock.uptimeMillis() + 500
                paintCards(result.bubbles)
                state = State.SHOWING
                // A page with dialogue keeps the current work alive and feeds it
                // the names that identify it; a run of pages without any means
                // the reader has left the story — an index, a cover, a menu.
                if (result.bubbles.isEmpty()) {
                    works.noteQuietPass()
                } else {
                    works.noteTranslated(System.currentTimeMillis(), glossary.snapshot().keys)
                }
                controller?.bubbleView?.setDebugBalloons(
                    if (settings.diagnostics) result.balloons else emptyList()
                )
                // Diagnostics stay up: they exist to be read off a page that
                // came back wrong, and a pill that vanishes is no use for that.
                if (result.diag != null) {
                    setPill("${result.engineLabel.ifBlank { "—" }} · ${result.diag} · ${works.describe()}")
                } else if (result.bubbles.isEmpty()) {
                    setPill(if (auto) null else "no CJK text found", 1800)
                } else {
                    val mark = if (result.polished) "✨" else "✓"
                    val extra = result.note?.let { " · $it" } ?: ""
                    setPill("$mark ${result.bubbles.size} · ${result.engineLabel}$extra", 2400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page still needs watching or a failed pass would sit
                // here until something scrolls. The baseline from the grabbed
                // frame (when the grab got that far) and the mask over
                // whatever draft cards are up are both already in place.
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
            clearCards()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(280)
        }
        // Crop away any stride padding here, once per translation pass, and
        // always hand out a private copy so the reused buffers stay ours.
        return synchronized(frameLock) {
            latestBitmap?.let { src ->
                val w = capW.coerceAtMost(src.width)
                val h = capH.coerceAtMost(src.height)
                val out = Bitmap.createBitmap(src, 0, 0, w, h)
                if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
            }
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

    override fun onNewSeries() {
        // The automatic boundaries — a long gap, or a run of pages with no
        // dialogue — cover switching series the usual way. This is for going
        // straight from one work to the next with neither.
        works.startNewWork()
        setPill("new series · names cleared", 2000)
    }

    override fun onTogglePause() {
        paused = !paused
        if (paused) {
            translateJob?.cancel()
            state = State.SCANNING
            shownThumb = null
            clearCards()
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
            clearCards()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(4000)
            if (state == State.SHOWING) {
                suppressUntil = SystemClock.uptimeMillis() + 900
                paintCards(shown)
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
        releaseFrameBuffers()
        captureThread?.quitSafely()
        captureThread = null
        super.onDestroy()
    }
}
