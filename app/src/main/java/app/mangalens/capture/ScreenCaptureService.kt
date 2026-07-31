package app.mangalens.capture

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import app.mangalens.pipeline.StripStore
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.translate.PageKey
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

        /**
         * Sticky scrolling: per-frame pixel displacement below this is a
         * settled screen; a run of settled frames long enough to satisfy the
         * reaction-time setting triggers translation of whatever new content
         * scrolled into view.
         */
        private const val SETTLED_DELTA_PX = 3

        /** Trackable displacement per frame pair; beyond this the reader is flinging. */
        private const val TRACK_WINDOW_FRACTION = 3

        /** Cards move at this cadence while a sticky session is live. */
        private const val STICKY_FRAME_GAP_MS = 33L

        /** Claimed regions are inflated by this margin before excluding OCR. */
        private const val CLAIM_MARGIN_PX = 24

        /** Consecutive re-acquired locks that end a coast without a wide match. */
        private const val COAST_RELOCK_FRAMES = 3

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

    // ---- sticky scrolling ----
    //
    // A manhwa is one tall strip. When the tracker knows the exact scroll
    // offset of every frame, cards ride the content instead of clearing on
    // every scroll, and a balloon scrolled back into view still wears its
    // translation — the chapter reads as if it had been translated from the
    // start. The strip store is main-thread only; the capture thread talks
    // to it exclusively through the volatile snapshots below.

    /** Translations of this reading session, in strip coordinates. Main thread only. */
    private val strip = StripStore()

    /** Cumulative strip offset in pixels. Written on the capture thread. */
    @Volatile private var trackOffset = 0

    /** True once the session has cards riding a live tracker lock. */
    @Volatile private var stickyLive = false

    /** Offset the currently painted cards were laid out for. */
    @Volatile private var paintedAtOffset = 0

    /** Screen rects of the painted cards, at [paintedAtOffset]. */
    @Volatile private var paintedRects: List<Rect> = emptyList()

    /** Offset of the last completed translation pass, to gate re-passes. */
    @Volatile private var lastTranslatedOffset = Int.MIN_VALUE

    /** Lost the lock mid-fling; cards hidden until a settle re-locks. */
    @Volatile private var coasting = false

    /** Coast ended on re-acquired locks; the next settle must vote the blind gap away. */
    @Volatile private var needsReground = false

    /** Consecutive settles whose reground found nothing where cards were expected. */
    private var regroundStrikes = 0

    // Capture thread only.
    private var prevProfile: IntArray? = null
    private var settledProfile: IntArray? = null
    private var settledOffset = 0
    private var settledRunStart = 0L
    private var coastLocks = 0
    private var lastFiredProfile: IntArray? = null

    /**
     * Passes in flight, so a cancelled pass's late cleanup can never switch
     * the busy ring off under a newer pass. Main thread only.
     */
    private var busyDepth = 0

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
                // A live session cannot outlive the setting that allows it.
                if (stickyLive && (!s.stickyScroll || s.mode != CaptureMode.AUTO)) {
                    stickyReset()
                }
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
        imageReader?.let { old ->
            val handler = captureHandler
            if (handler != null && handler.looper.thread.isAlive) {
                handler.post { runCatching { old.close() } }
            } else {
                runCatching { old.close() }
            }
        }
        imageReader = reader
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection == null) return
        val (w, h, _) = displaySize()
        if (w != capW || h != capH) {
            stickyReset()
            releaseFrameBuffers()
            setupDisplay()
        }
    }

    /** Runs on the capture HandlerThread. */
    private fun onFrame(reader: ImageReader) {
        // Stop and rotation close the reader from the main thread while
        // frames are still arriving here; a closed reader throws rather than
        // returning null, and an already-acquired Image's buffer dies under
        // the copy. Either way the frame is simply over.
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            lastFrameAt = now
            // ~12 fps is plenty for a 350 ms stability window; dropping the
            // rest skips a full-screen copy per display frame. A live sticky
            // session runs faster so the riding cards keep up with the thumb.
            val frameGap = if (stickyLive) STICKY_FRAME_GAP_MS else 80L
            if (now - lastFrameProcessedAt < frameGap) return
            lastFrameProcessedAt = now
            val bmp = imageToBitmap(image)
            synchronized(frameLock) {
                latestBitmap = bmp
            }
            val sticky = settings.stickyScroll && settings.mode == CaptureMode.AUTO && !paused
            if (sticky && trackFrame(bmp, now)) return
            val thumb = FrameStability.grayThumb(bmp, capW, capH)
            val diff = FrameStability.meanDiff(prevThumb, thumb)
            prevThumb = thumb
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
        } catch (_: IllegalStateException) {
            return
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * Runs the sticky tracker on one frame. Returns true when the sticky
     * session consumed it; false hands the frame to the classic detectors —
     * before a session exists, they still own motion and the first translate.
     * Capture thread only.
     */
    private fun trackFrame(bmp: Bitmap, now: Long): Boolean {
        val prof = ScrollTracker.profile(bmp, shiftedClaimRects(), capW, capH)
        val prev = prevProfile
        prevProfile = prof
        if (!stickyLive && !coasting) {
            settledProfile = null
            settledOffset = 0
        }
        if (prev == null) return stickyLive

        val lock = ScrollTracker.delta(prev, prof, maxShiftPx = capH / TRACK_WINDOW_FRACTION)
        if (lock == null) {
            // A fling too fast to match, or a page swap. Hide the cards but
            // keep everything learned; what happens at the next stillness
            // decides which it was.
            if (!stickyLive) return false
            if (!coasting) {
                coasting = true
                coastLocks = 0
                scope.launch { hideCardsKeepStore() }
            }
            coastLocks = 0
            lastMotionAt = now
            settledRunStart = 0L
            return true
        }

        // delta is negative when the reader scrolls down (content moves
        // up) while strip offsets grow scrolling down — hence subtraction.
        trackOffset -= lock.deltaPx
        if (lock.deltaPx != 0) lastMotionAt = now
        if (kotlin.math.abs(lock.deltaPx) >= SETTLED_DELTA_PX) {
            settledRunStart = 0L
        } else if (settledRunStart == 0L) {
            settledRunStart = now
        }
        val settled = settledRunStart != 0L && now - settledRunStart >= settings.stabilityMs

        if (coasting) {
            // Per-frame locks resuming mid-coast mean the strip is readable
            // again and its visible motion is being integrated once more.
            // Only the frames the fling blinded are unaccounted for; profiles
            // are one screen tall, so a wide re-lock can recover a short
            // blind gap, and the strip store's own landmarks vote away a
            // long one at the next settle.
            coastLocks++
            if (settled) {
                settledRunStart = 0L
                val base = settledProfile
                val wide = if (base == null) null else ScrollTracker.delta(
                    base, prof,
                    maxShiftPx = capH * 2,
                    guessPx = settledOffset - trackOffset,
                )
                when {
                    wide != null -> {
                        // Same strip, a short hop along: correct the offset
                        // from the absolute match.
                        trackOffset = settledOffset - wide.deltaPx
                        coasting = false
                        settledProfile = prof
                        settledOffset = trackOffset
                        scope.launch { onStickySettled() }
                    }
                    coastLocks >= COAST_RELOCK_FRAMES -> {
                        // Too far for profiles to overlap, but the tracker is
                        // reading the strip again — carry on and let stored
                        // balloons re-detected at the settle vote the blind
                        // gap out of the offset.
                        coasting = false
                        needsReground = true
                        settledProfile = prof
                        settledOffset = trackOffset
                        scope.launch { onStickySettled() }
                    }
                    else -> scope.launch { stickyReset() }
                }
            } else if (coastLocks >= COAST_RELOCK_FRAMES) {
                // The strip is readable again mid-motion; stop coasting and
                // ride it. The blind gap stays in the offset until the next
                // settle's landmarks vote it out.
                coasting = false
                needsReground = true
            }
            return true
        }

        if (stickyLive) {
            scope.launch {
                // Read both offsets on the main thread at execution time: a
                // value computed here can be stale by the time the post runs
                // if a repaint lands in between, jumping every card for a
                // frame.
                controller?.bubbleView?.setCardShift(paintedAtOffset - trackOffset)
            }
            if (settled) {
                settledRunStart = 0L
                // Re-anchoring each settle on an absolute match against the
                // previous settled view keeps per-frame rounding from random-
                // walking the offset over a long chapter.
                val base = settledProfile
                if (base != null) {
                    ScrollTracker.delta(
                        base, prof,
                        maxShiftPx = capH,
                        guessPx = settledOffset - trackOffset,
                    )?.let { trackOffset = settledOffset - it.deltaPx }
                }
                settledProfile = prof
                settledOffset = trackOffset
                if (state != State.TRANSLATING) {
                    if (trackOffset != lastTranslatedOffset) {
                        lastFiredProfile = prof
                        scope.launch { onStickySettled() }
                    } else if (profilesDiffer(lastFiredProfile, prof)) {
                        // Same offset, different pixels: a lazy-loaded panel
                        // popped in under a settled screen. Offset gating
                        // alone would ignore it forever.
                        lastFiredProfile = prof
                        scope.launch { onStickySettled() }
                    }
                }
            }
            return true
        }
        return false
    }

    /**
     * Materially different content in two settled profiles of the same
     * offset. Bands under our own cards are already excluded by the profile;
     * a modest fraction of the rest moving a real distance is a panel
     * appearing, not sensor noise.
     */
    private fun profilesDiffer(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return true
        var valid = 0
        var changed = 0
        for (i in a.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            valid++
            if (kotlin.math.abs(a[i] - b[i]) > 10) changed++
        }
        if (valid < a.size / 8) return false
        return changed.toFloat() / valid > 0.04f
    }

    /** Screen rects our cards occupy right now, for the tracker to ignore. */
    private fun shiftedClaimRects(): List<Rect> {
        val rects = paintedRects
        if (rects.isEmpty()) return emptyList()
        val shift = paintedAtOffset - trackOffset
        return rects.map { Rect(it.left, it.top + shift, it.right, it.bottom + shift) }
    }

    // Main-thread sticky helpers.

    private fun onStickySettled() {
        if (projection == null || paused || state == State.TRANSLATING) return
        startTranslate(auto = true)
    }

    /** Coasting: cards vanish but the session's translations stay learned. */
    private fun hideCardsKeepStore() {
        controller?.bubbleView?.setCardShift(0)
        paintCards(emptyList())
    }

    /** Repaints the visible slice of the strip store at the current offset. */
    private fun repaintFromStore() {
        controller?.bubbleView?.setCardShift(0)
        paintCards(strip.visible(trackOffset, capH))
    }

    /**
     * The strip this session was tracking is gone — a new chapter, another
     * app, a page-based reader. Forget the geometry (the translation cache
     * keeps the words) and let the classic loop start over.
     */
    private fun stickyReset() {
        translateJob?.cancel()
        strip.clear()
        stickyLive = false
        coasting = false
        needsReground = false
        regroundStrikes = 0
        trackOffset = 0
        paintedAtOffset = 0
        paintedRects = emptyList()
        lastTranslatedOffset = Int.MIN_VALUE
        state = State.SCANNING
        shownThumb = null
        controller?.bubbleView?.setCardShift(0)
        clearCards()
        setPill(null)
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
            prevProfile = null
            settledProfile = null
            settledRunStart = 0L
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

    private fun pushBusy() {
        busyDepth++
        controller?.setBusy(true)
    }

    private fun popBusy() {
        busyDepth--
        if (busyDepth <= 0) {
            busyDepth = 0
            controller?.setBusy(false)
        }
    }

    /**
     * Paints cards and records both the cells and the rects they cover, in
     * one step — the mask and the tracker's exclusions must never describe
     * cards other than the ones actually on screen. That includes the fast
     * draft painted mid-pass: left unrecorded, its static pixels anchor the
     * scroll tracker to zero and any creep during the pass is baked into the
     * session as a permanent misregistration. Main thread only.
     */
    private fun paintCards(bubbles: List<RenderBubble>) {
        val view = controller?.bubbleView ?: return
        view.setBubbles(bubbles)
        val placed = if (bubbles.isEmpty()) emptyList() else view.placedRects()
        overlayMask = if (placed.isEmpty()) null else FrameStability.mask(placed, capW, capH)
        paintedRects = placed
        paintedAtOffset = trackOffset
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
        val sticky = settings.stickyScroll && settings.mode == CaptureMode.AUTO
        translateJob = scope.launch {
            pushBusy()
            try {
                // A long enough break since the last translated page means the
                // next one probably belongs to a different series.
                works.beginPass(System.currentTimeMillis())
                val live = sticky && stickyLive
                if (!live) shownThumb = null
                val offsetAtCapture = trackOffset
                // A live sticky session grabs the frame with cards up: they
                // sit exactly on already-translated balloons, and the claim
                // rects below keep OCR and detection out of them — no clear,
                // no flash, no feedback.
                val bmp = grabFrame(clearFirst = !live)
                if (bmp == null) {
                    state = State.SCANNING
                    return@launch
                }
                setPill("translating…")
                val claims = if (sticky) {
                    strip.claimedRects(offsetAtCapture, capH).map {
                        Rect(
                            it.left - CLAIM_MARGIN_PX, it.top - CLAIM_MARGIN_PX,
                            it.right + CLAIM_MARGIN_PX, it.bottom + CLAIM_MARGIN_PX,
                        )
                    }
                } else emptyList()
                // The painted card can spill past its logical claim — width
                // floors, screen-edge clamping, a minimum type size that
                // overflows the box — and a live grab keeps cards up, so the
                // pipeline must also be kept out of the pixels the cards
                // actually cover or it would read our own English back.
                val painted = if (live) shiftedClaimRects().map {
                    Rect(
                        it.left - CLAIM_MARGIN_PX, it.top - CLAIM_MARGIN_PX,
                        it.right + CLAIM_MARGIN_PX, it.bottom + CLAIM_MARGIN_PX,
                    )
                } else emptyList()
                val exclusions = (controller?.overlayExclusions() ?: emptyList()) + claims + painted
                var hashes: List<Long> = emptyList()
                val result = try {
                    withContext(Dispatchers.Default) {
                        // Remember the page being translated from the exact
                        // bitmap handed to the pipeline. Waiting for a later
                        // "settled" frame instead left a hole: a fling inside
                        // the suppression window put a new page on screen
                        // first, the baseline then described the new page
                        // while the cards described the old one, and the
                        // mismatch could never be noticed.
                        if (!live) shownThumb = FrameStability.grayThumbOf(bmp)
                        val r = pipeline.process(bmp, settings, exclusions) { partial ->
                            // Fast path landed — paint it now, AI polish follows.
                            // A live sticky session skips the draft: the store's
                            // cards are already up, and swapping them for a
                            // partial repaint would make the page flicker.
                            withContext(Dispatchers.Main.immediate) {
                                if (isActive && state == State.TRANSLATING && !live) {
                                    lastShown = partial.bubbles
                                    suppressUntil = SystemClock.uptimeMillis() + 600
                                    paintCards(partial.bubbles)
                                    setPill("✓ ${partial.bubbles.size} · ${partial.engineLabel} · ✨ upgrading…")
                                }
                            }
                        }
                        // Each card's strip identity, taken while the pixels
                        // are still in hand. Hashed over the balloon's box
                        // where one was detected: the text-tight box shifts
                        // between passes with OCR's mood, and an identity
                        // that drifts cannot vote in a reground.
                        if (sticky) {
                            hashes = r.bubbles.map {
                                PageKey.regionHash(bmp, it.balloon?.box ?: it.box)
                            }
                        }
                        r
                    }
                } finally {
                    // Cancellation is this loop's steady state — every scroll
                    // that interrupts a pass lands here — so the full-screen
                    // copy is reclaimed on that path too, not only on success.
                    bmp.recycle()
                }
                if (!isActive) return@launch
                // The pass was captured under the sticky setting, but the
                // user may have flipped it off mid-flight; committing a live
                // session against an off setting would strand riding cards
                // with no tracker to move them.
                val stickyStill = sticky &&
                    settings.stickyScroll && settings.mode == CaptureMode.AUTO
                if (stickyStill) {
                    var offsetForAdd = offsetAtCapture
                    var holdBack = false
                    if (needsReground) {
                        // A coast ended on re-acquired locks alone, so the
                        // blind gap is still in the offset. Stored balloons
                        // re-detected just now are landmarks: hash votes
                        // first, the balloons' layout pattern when the
                        // hashes jitter. One settle finding nothing where
                        // cards were expected is a fingerprint quirk; only a
                        // run of them means the strip is genuinely gone.
                        val corrected = strip.reground(
                            result.bubbles.mapIndexed { i, b ->
                                (b.balloon?.box ?: b.box) to hashes[i]
                            },
                            offsetAtCapture,
                        )
                        when {
                            corrected != null && kotlin.math.abs(corrected - offsetAtCapture) <= capH * 6 -> {
                                val fix = corrected - offsetAtCapture
                                offsetForAdd = corrected
                                trackOffset += fix
                                needsReground = false
                                regroundStrikes = 0
                            }
                            claims.isEmpty() -> {
                                needsReground = false
                                regroundStrikes = 0
                            }
                            else -> {
                                regroundStrikes++
                                if (regroundStrikes >= 3) {
                                    stickyReset()
                                    return@launch
                                }
                                // Show this screen translated, but keep its
                                // cards out of the strip until the offset can
                                // be trusted — entries stored at a wrong
                                // offset misplace the whole session.
                                holdBack = true
                            }
                        }
                    } else {
                        regroundStrikes = 0
                    }
                    if (holdBack) {
                        // The offset is on probation: show this screen its
                        // translations, but nothing enters the strip and the
                        // next settle gets another vote.
                        lastTranslatedOffset = offsetAtCapture
                        lastShown = emptyList()
                        paintCards(result.bubbles)
                    } else {
                        // A balloon straddling the frame edge was read in
                        // part; storing the fragment would claim the whole
                        // balloon against ever being translated whole. Leave
                        // it for the settle that shows all of it.
                        val edge = (capH / 100).coerceAtLeast(8)
                        val keep = ArrayList<RenderBubble>(result.bubbles.size)
                        val keepHashes = ArrayList<Long>(hashes.size)
                        result.bubbles.forEachIndexed { i, b ->
                            val whole = b.balloon?.box ?: b.box
                            if (whole.top > edge && whole.bottom < capH - edge) {
                                keep.add(b)
                                keepHashes.add(hashes[i])
                            }
                        }
                        strip.add(keep, keepHashes, offsetForAdd)
                        lastTranslatedOffset = offsetForAdd
                        lastShown = emptyList()
                        repaintFromStore()
                    }
                    stickyLive = true
                } else {
                    lastShown = result.bubbles
                    suppressUntil = SystemClock.uptimeMillis() + 500
                    paintCards(result.bubbles)
                }
                state = State.SHOWING
                // A page with dialogue keeps the current work alive and feeds it
                // the names that identify it; a run of pages without any means
                // the reader has left the story — an index, a cover, a menu. A
                // sticky pass that found nothing new over a claimed screen is
                // the reader lingering on dialogue, not leaving the story.
                if (result.bubbles.isNotEmpty() || claims.isNotEmpty()) {
                    works.noteTranslated(System.currentTimeMillis(), glossary.snapshot().keys)
                } else {
                    works.noteQuietPass()
                }
                controller?.bubbleView?.setDebugBalloons(
                    if (settings.diagnostics) result.balloons else emptyList()
                )
                // Diagnostics stay up: they exist to be read off a page that
                // came back wrong, and a pill that vanishes is no use for that.
                if (result.diag != null) {
                    setPill("${result.engineLabel.ifBlank { "—" }} · ${result.diag} · ${works.describe()}")
                } else if (result.bubbles.isEmpty()) {
                    setPill(if (auto) null else "no text found", 1800)
                } else {
                    val mark = if (result.polished) "✨" else "✓"
                    val extra = result.note?.let { " · $it" } ?: ""
                    val total = if (stickyStill && strip.size() > result.bubbles.size) {
                        " · ${strip.size()} on strip"
                    } else ""
                    setPill("$mark ${result.bubbles.size} · ${result.engineLabel}$extra$total", 2400)
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
            } finally {
                popBusy()
            }
        }
    }

    /**
     * Returns a copy of the newest frame. With [clearFirst] our own overlays
     * are cleared and a fresh frame awaited, so OCR never reads our English;
     * a live sticky session passes false and relies on claim-rect exclusions
     * instead.
     */
    private suspend fun grabFrame(clearFirst: Boolean): Bitmap? {
        if (clearFirst) {
            val hadOverlays = controller?.bubbleView?.hasBubbles() == true
            if (hadOverlays) {
                clearCards()
                suppressUntil = SystemClock.uptimeMillis() + 900
                delay(280)
            }
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
        if (stickyLive && coasting) {
            // The offset is untrusted mid-coast; storing cards against it
            // would misplace them for the rest of the session.
            setPill("hold still a moment…", 1400)
            return
        }
        startTranslate(auto = false)
    }

    override fun onNewSeries() {
        // The automatic boundaries — a long gap, or a run of pages with no
        // dialogue — cover switching series the usual way. This is for going
        // straight from one work to the next with neither.
        stickyReset()
        works.startNewWork()
        setPill("new series · names cleared", 2000)
    }

    override fun onTogglePause() {
        paused = !paused
        if (paused) {
            stickyReset()
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
        if (state != State.SHOWING) return
        val shown = lastShown
        if (!stickyLive && shown.isEmpty()) return
        scope.launch {
            clearCards()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(4000)
            if (state != State.SHOWING) return@launch
            suppressUntil = SystemClock.uptimeMillis() + 900
            if (stickyLive) {
                // Mid-coast the offset is untrusted; the settle repaints.
                if (!coasting) repaintFromStore()
            } else if (lastShown === shown) {
                // A newer pass owns the screen now; resurrecting the list
                // captured at peek time would paint the previous page's
                // lines over it — and the mask would then hide the mistake
                // from every detector.
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
