package app.mangalens.capture

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import app.mangalens.MainActivity
import app.mangalens.MangaLensApp
import app.mangalens.R
import app.mangalens.ocr.BalloonFinder
import app.mangalens.ocr.OcrEngine
import app.mangalens.overlay.Hyphenation
import app.mangalens.overlay.OverlayController
import app.mangalens.overlay.RenderBubble
import app.mangalens.pipeline.AiFailure
import app.mangalens.pipeline.ScrollMatch
import app.mangalens.pipeline.TranslatePipeline
import app.mangalens.pipeline.UpgradeMerge
import app.mangalens.scroll.AutoScroller
import app.mangalens.scroll.ScrollPace
import app.mangalens.settings.AppSettings
import app.mangalens.settings.CaptureMode
import app.mangalens.settings.SettingsRepository
import app.mangalens.translate.CastBook
import app.mangalens.translate.GlossaryStore
import app.mangalens.translate.LlmHttp
import app.mangalens.translate.PendingRead
import app.mangalens.translate.TranslationCache
import app.mangalens.translate.TranslationService
import app.mangalens.translate.WorkMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
         * Quiet time after which the frame is read ahead of the stability
         * window. OCR and balloon detection are the slow half of a pass and
         * need nothing but the frame, so they start as soon as the screen
         * settles; by the time the reader has provably stopped, the page is
         * usually already read and only translation remains. Painting still
         * waits for the full window, so a brief pause never flashes cards.
         */
        private const val EARLY_ANALYSIS_MS = 150L

        /** The ticker's beat while nothing is due sooner: warming the connection, checking the quiet. */
        private const val TICK_MS = 60L

        /** A look at one screen long enough for a phone's radio to have gone idle. */
        private const val LONG_LOOK_MS = 8_000L

        /**
         * How far (thumb mean difference) the live frame may have drifted
         * from the frame read ahead before that reading is thrown away.
         * Identical frames differ by capture noise only, well under one.
         */
        private const val PREPARED_MAX_DRIFT = 1.2

        /**
         * Spacing between the frames actually looked at. A dozen a second
         * is plenty for a 350 ms stability window, and every frame not
         * looked at is a full-screen copy not made. The last frame of a
         * burst is never skipped, whatever the spacing — see [FrameGate].
         */
        private const val FRAME_INTERVAL_MS = 80L

        /**
         * How long the cells a mask has just given up stay excluded. A card
         * is on screen a frame or two after it is placed and gone a frame
         * or two after it is cleared, and the capture sees each frame later
         * still; for that long the mask and the screen disagree, and the
         * cells that still show a card must not be read as the page having
         * changed.
         */
        private const val MASK_GRACE_MS = 300L

        /**
         * Passes a page change may cancel mid-stream, one after another,
         * before the next pass is left to finish. A region that never stops
         * changing — an animated banner, a video — trips the page-change
         * check as surely as a swap does, and left unchecked it would cancel
         * every pass, and the reader would never see a translation at all.
         * Two in a row is a reader skimming; from the third the pass runs
         * to the end, and the page is watched again once it has.
         *
         * Only a page seen to be replaced counts. A scroll is the reader
         * moving on, which no banner can pass for: it cancels a pass
         * whatever the count, and it starts the next stop with the full
         * budget again.
         */
        private const val MAX_MID_PASS_CANCELS = 2

        /**
         * How long an alert holds the status pill. It is shown once, so it
         * stays long enough to be read, whatever the loop does meanwhile.
         */
        private const val ALERT_MS = 6000L

        /** How long the pill says why the AI could not read a page. */
        private const val FAILURE_MS = 4500L

        /**
         * The pill for a page the AI could not read — or, when [partly],
         * could not finish: some of its lines made it onto the page first.
         */
        internal fun failurePill(cause: String, partly: Boolean): String =
            "⚠ AI couldn't ${if (partly) "finish" else "read"} this page — $cause"

        val running = MutableStateFlow(false)

        /**
         * Whether translation is paused, for the home screen: it shows Fuki
         * napping and offers "Wake up" instead of treating a paused session
         * as a running one.
         */
        val pausedState = MutableStateFlow(false)

        /**
         * Whether the screen moved between two frames. While our own
         * painting is settling — [ownPaintSettling] — the difference is taken
         * only over the cells [mask] leaves: a card fading in or a streamed
         * line joining the page changes nothing outside the mask, while
         * a scroll moves the page around the cards as much as under them.
         * The rest of the time every cell counts, as it always has.
         */
        internal fun frameMoved(
            prev: IntArray?,
            thumb: IntArray,
            mask: BooleanArray?,
            ownPaintSettling: Boolean,
        ): Boolean = FrameStability.meanDiff(prev, thumb, if (ownPaintSettling) mask else null) > MOTION_THRESHOLD

        /**
         * How [thumb] differs from [base], the page as it was translated,
         * over the cells [mask] leaves to judge by.
         *
         * The page may have moved (slow scroll — cells shift, and the balloon
         * that matters most may slide in under a card, changing only masked
         * cells), or been replaced (tap-to-turn swap — cells change in
         * place). A move is looked for first: a scroll changes plenty of
         * cells too, and must never be taken for a replaced page, which only
         * so many passes may be cancelled for.
         *
         * The bar for a replaced page adapts to how much of the page our own
         * cards hide. On a dense page the cards cover most of the text — the
         * very cells a page swap changes hardest — so the comparison runs
         * over gutters and margins, where a real swap moves far fewer cells.
         * Demanding the full fraction there is how a tap-to-turn under
         * blanket coverage went unnoticed while the old page's cards sat on
         * the new page.
         */
        internal fun judgeAgainstShown(base: IntArray, thumb: IntArray, mask: BooleanArray?): PageCheck {
            if (kotlin.math.abs(FrameStability.verticalShift(base, thumb, mask)) >= SLOW_SCROLL_MIN_ROWS) {
                return PageCheck.SCROLLED
            }
            val cov = FrameStability.coverage(mask)
            val pageBar = PAGE_CHANGE_FRACTION * when {
                cov > 0.60 -> 0.30
                cov > 0.35 -> 0.60
                else -> 1.0
            }
            return if (FrameStability.changedFraction(base, thumb, mask) > pageBar) PageCheck.REPLACED else PageCheck.SAME
        }
    }

    private enum class State { SCANNING, TRANSLATING, SHOWING }

    /** What a frame says about the page that was translated; see [judgeAgainstShown]. */
    internal enum class PageCheck { SAME, SCROLLED, REPLACED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsRepo: SettingsRepository

    @Volatile private var settings = AppSettings()

    /**
     * Whether [settings] holds what the reader saved yet, rather than the
     * defaults it starts as. The store is read off the main thread and the
     * capture can start first; a pass before then would see no key and
     * tell a reader who has one to add it. Main thread only.
     */
    private var settingsLoaded = false

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
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
     * The thumb cells our own overlays currently cover: the cards, and the
     * floating button with its status pill. All of it is captured along
     * with the page, so these cells show us, not the reader's content, and
     * every comparison excludes them. Maintained by [paintCards],
     * [clearCards] and the controls' own layout changes, so it is always
     * the truth about what is on screen — including the lines that stream
     * in mid-translation and a pill that comes and goes on its own —
     * and it keeps the cells just given up for [MASK_GRACE_MS], see
     * [setOverlayMask].
     */
    @Volatile private var overlayMask: BooleanArray? = null

    /** Bumped on every mask change, so a stale narrowing never lands. Main thread only. */
    private var maskEpoch = 0

    /**
     * The share of itself the page shows at on screen, wherever the
     * lettering paints nothing: below 1 while MangaLens's own veil is up
     * (see BubbleOverlayView.veiled), since the veil is captured along with
     * the page. Frames are divided by it, so everything downstream reads the
     * page as it is. Set on the main thread, read on the capture thread.
     */
    @Volatile private var screenLevel = 1f

    /** [screenLevel] as it was when the newest frame was read. Guarded by [frameLock]. */
    private var latestLevel = 1f

    /**
     * The frame the cards on screen were set for, as the overlay paints
     * against it under the veil, and the pass frame it was copied from: the
     * pass frees its own when it ends, and the cards outlive it. Main
     * thread only.
     */
    private var cardsPage: Bitmap? = null
    private var cardsFrom: Bitmap? = null

    // Reference for slow-scroll drift detection (capture thread only).
    private var slowRefThumb: IntArray? = null
    private var slowRefAt = 0L

    /** The newest frame's thumb, for judging whether a frame read ahead is still the one on screen. */
    @Volatile private var latestThumb: IntArray? = null

    /**
     * A frame grabbed and read ahead of the stability window: the page
     * analysis runs while the loop is still waiting to be sure the reader
     * has stopped. Main thread only; [preparing] mirrors it for the
     * capture thread, which must cancel it the moment the screen moves.
     */
    private class Prepared(
        val bitmap: Bitmap,
        /** The frame's thumbnail, taken as it was grabbed, to tell at once whether the screen has moved on. */
        val thumb: IntArray,
    ) {
        lateinit var job: Deferred<Pair<IntArray, TranslatePipeline.Analysis>>

        /**
         * The AI read of this frame, started alongside its analysis: with
         * Gemini the page goes to the model the moment the screen is still,
         * so the answer is already streaming by the time the reader has
         * provably stopped. It is not a child of the analysis job and must
         * be cancelled explicitly whenever the frame is abandoned.
         */
        @Volatile var read: PendingRead? = null
        @Volatile private var dropped = false

        fun adopt(r: PendingRead?) {
            read = r
            if (dropped) r?.cancel()
        }

        fun drop() {
            dropped = true
            read?.cancel()
        }
    }

    private var prepared: Prepared? = null
    @Volatile private var preparing = false

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
    private val gate = FrameGate(FRAME_INTERVAL_MS)
    private val recheckRunnable = Runnable { recheck() }

    /**
     * A frame that arrived inside the interval, kept — not copied — until
     * the interval is up, when it is looked at unless a newer one has
     * arrived by then. Capture thread only.
     */
    private var heldImage: Image? = null
    private var heldAt = 0L

    @Volatile private var lastMotionAt = 0L

    /**
     * Passes cancelled by a replaced page since the reader last scrolled;
     * see [MAX_MID_PASS_CANCELS]. Written on the main thread only.
     */
    @Volatile private var midPassCancels = 0
    @Volatile private var lastFrameAt = 0L

    /**
     * Until when our own painting or clearing is still settling on screen.
     * Meanwhile frame differencing looks only past the overlays (see
     * [frameMoved]) and no pass starts.
     */
    @Volatile private var suppressUntil = 0L
    @Volatile private var state = State.SCANNING
    @Volatile private var paused = false

    private var translateJob: Job? = null

    /** The alert last shown, so the same one is not shown again; see [showAlert]. Main thread only. */
    private var alerted: String? = null

    /** Uptime until which an alert holds the pill; see [setPill]. Main thread only. */
    private var alertUntil = 0L

    private var lastShown: List<RenderBubble> = emptyList()

    /**
     * The scroll signature of the frame [lastShown] was lettered for, so the
     * next stop can carry those cards across a measured scroll; null when
     * unknown. Main thread only.
     */
    private var shownOn: ScrollMatch? = null
    private var capW = 0
    private var capH = 0

    /** Translation passes finished so far: auto-scroll waits on the next one when stops are translated. */
    private var passes = 0

    private var autoScroller: AutoScroller? = null

    /** What auto-scroll sees of the screen and of translation. Main thread, but for [balloons]. */
    private val scrollPage = object : AutoScroller.Page {
        override fun size(): Pair<Int, Int> = capW to capH

        override fun obstacles(): List<Rect> = controller?.overlayExclusions() ?: emptyList()

        override fun thumb(): IntArray? = latestThumb

        override fun mask(): BooleanArray? = overlayMask

        override fun holding(): Boolean = controller?.menuOpen == true

        override suspend fun balloons(): List<Rect>? {
            val s = settings
            val exclusions = controller?.overlayExclusions() ?: emptyList()
            return withContext(Dispatchers.Default) {
                val bmp = grabFrame() ?: return@withContext null
                try {
                    BalloonFinder.analyze(
                        bmp, (bmp.height * s.ignoreTopPct).toInt(), (bmp.height * s.ignoreBottomPct).toInt(), exclusions,
                    ).balloons.map { it.box }
                } finally {
                    bmp.recycle()
                }
            }
        }

        override fun translating(): Boolean =
            !paused && settings.mode == CaptureMode.AUTO && LlmHttp.setupNeeded(settings) == null

        override fun passes(): Int = passes

        override fun englishBelow(y: Int): Int =
            if (state != State.SHOWING) 0 else lastShown.filter { it.box.centerY() > y }.sumOf { it.translated.length }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        scope.launch {
            settingsRepo.flow.collect { s ->
                // A new key gets its own verdict, even when it is the same
                // words the old key earned.
                if (s.apiKey != settings.apiKey || s.provider != settings.provider) alerted = null
                settings = s
                settingsLoaded = true
                controller?.bubbleView?.let { v ->
                    v.textScale = s.textScale
                    v.bgOpacity = s.bgOpacity
                }
                controller?.setManual(s.mode == CaptureMode.MANUAL)
                controller?.setScrollButtonShown(s.autoScrollButton)
                autoScroller?.let { a ->
                    a.level = s.scrollLevel
                    a.smart = s.smartScroll
                }
                updateVeil()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                pausedState.value = paused
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
        pipeline.warm(settings)
        // The hyphenation patterns load here, not on the UI thread when the
        // first long word is lettered.
        scope.launch(Dispatchers.Default) { Hyphenation.warm() }
        controller = OverlayController(this, this).also { it.attach() }
        controller?.bubbleView?.let { v ->
            v.textScale = settings.textScale
            v.bgOpacity = settings.bgOpacity
        }
        controller?.setManual(settings.mode == CaptureMode.MANUAL)
        controller?.setScrollButtonShown(settings.autoScrollButton)
        autoScroller = AutoScroller(
            scope = scope,
            handler = Handler(mainLooper),
            touchSlop = ViewConfiguration.get(this).scaledTouchSlop,
            density = resources.displayMetrics.density,
            page = scrollPage,
            listener = object : AutoScroller.Listener {
                override fun onRunningChanged(running: Boolean) {
                    controller?.setAutoScrolling(running)
                }

                override fun say(text: String, ms: Long) = answer(text, ms)
            },
        ).also {
            it.level = settings.scrollLevel
            it.smart = settings.smartScroll
        }
        controller?.onFootprintChanged = { refreshOverlayMask() }
        controller?.bubbleView?.let { v -> v.onVeilChanged = { screenLevel = v.screenLevel } }
        updateVeil()
        refreshOverlayMask()
        running.value = true
        startTicker()
        setPill(
            if (settings.mode == CaptureMode.AUTO) "I'm on! Stop scrolling and I'll translate"
            else "I'm on! Tap 文\u2060A to translate a page",
            2600,
        )
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
        // Three buffers: one may be held across the frame interval (see
        // [FrameGate]), one is being acquired, and the display always has
        // one left to draw into.
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
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
            translateJob?.cancel()
            discardPrepared()
            state = State.SCANNING
            shownThumb = null
            clearCards()
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
        val now = SystemClock.uptimeMillis()
        lastFrameAt = now
        when (gate.arrival(now)) {
            FrameGate.Action.PROCESS -> {
                dropHeld()
                process(image, now)
            }
            FrameGate.Action.HOLD_AND_SCHEDULE -> {
                hold(image, now)
                captureHandler?.postDelayed(recheckRunnable, gate.delayFor(now))
            }
            FrameGate.Action.HOLD -> hold(image, now)
        }
    }

    /**
     * The interval since the last frame looked at is up: the frame held
     * meanwhile — or a newer one, if the reader has one — is looked at now.
     * Runs on the capture HandlerThread.
     */
    private fun recheck() {
        val newer = try {
            imageReader?.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        }
        val now = SystemClock.uptimeMillis()
        val image: Image
        val arrivedAt: Long
        if (newer != null) {
            dropHeld()
            image = newer
            arrivedAt = now
            lastFrameAt = now
        } else {
            image = heldImage ?: run {
                gate.recheckIdle()
                return
            }
            heldImage = null
            arrivedAt = heldAt
        }
        when (gate.recheck(now)) {
            FrameGate.Action.PROCESS -> process(image, arrivedAt)
            // A frame arrived and was looked at just before this fired; this
            // one is newer still, so it waits its turn rather than going.
            else -> {
                hold(image, arrivedAt)
                captureHandler?.postDelayed(recheckRunnable, gate.delayFor(now))
            }
        }
    }

    /** Keeps [image] as the newest frame of the current interval, releasing the one held before. */
    private fun hold(image: Image, arrivedAt: Long) {
        dropHeld()
        heldImage = image
        heldAt = arrivedAt
    }

    private fun dropHeld() {
        heldImage?.let { runCatching { it.close() } }
        heldImage = null
    }

    /**
     * Looks at one frame, which arrived at [now]: keeps it as the newest
     * frame for the next pass to grab, and judges whether the screen moved
     * or the page changed. Runs on the capture HandlerThread; closes [image].
     */
    private fun process(image: Image, now: Long) {
        try {
            val level = screenLevel
            val bmp = imageToBitmap(image)
            synchronized(frameLock) {
                latestBitmap = bmp
                latestLevel = level
            }
            val thumb = FrameStability.grayThumb(bmp, capW, capH)
            if (level != 1f) FrameStability.lift(thumb, 1f / level)
            val mask = overlayMask
            // Our own painting and clearing is motion too, as far as
            // frame-to-frame differencing can tell. For the moments around
            // it the difference is taken past the cells our overlays cover,
            // so a card fading in cannot pass for a scroll. Every card is in
            // the mask from the moment it is painted and for a while after it
            // is cleared. The check used to sit these moments out altogether,
            // and the page repaints each time the model streams an item.
            // For as long as a stream lasted there was then no motion check
            // at all, and cards stayed painted over a page that had moved.
            val moved = frameMoved(prevThumb, thumb, mask, ownPaintSettling = now < suppressUntil)
            prevThumb = thumb
            latestThumb = thumb
            if (moved) {
                slowRefThumb = null
                scrolled(now)
                return
            }
            // Slow scrolls hide from frame differencing, so drift is measured
            // over a longer baseline: whatever this sampled check misses while
            // cards are up, the cumulative check below accumulates. It looks
            // only past the mask, so our own painting never reads as drift.
            val ref = slowRefThumb
            if (ref == null) {
                slowRefThumb = thumb
                slowRefAt = now
            } else if (now - slowRefAt >= SLOW_SCROLL_SAMPLE_MS) {
                val drift = FrameStability.verticalShift(ref, thumb, mask)
                slowRefThumb = thumb
                slowRefAt = now
                if (kotlin.math.abs(drift) >= SLOW_SCROLL_MIN_ROWS) {
                    scrolled(now)
                    return
                }
            }
            // The page is compared against the page we translated, from the
            // moment it is grabbed until its cards come down. The comparison
            // excludes exactly the cells we paint, so it runs through every
            // paint, and a page turned mid-stream is caught at once rather
            // than when the stream ends.
            val current = state
            if (current == State.SCANNING) return
            val base = shownThumb ?: return
            when (judgeAgainstShown(base, thumb, mask)) {
                PageCheck.SCROLLED -> scrolled(now)
                PageCheck.REPLACED -> if (current == State.SHOWING || midPassCancels < MAX_MID_PASS_CANCELS) {
                    lastMotionAt = now
                    scope.launch { onMotion(pageChanged = true) }
                }
                PageCheck.SAME -> Unit
            }
        } catch (_: IllegalStateException) {
            return
        } finally {
            runCatching { image.close() }
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
            dropHeld()
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

    /**
     * The reader moved the page, as a frame at [now] shows. Capture thread.
     * Between stops there is usually nothing to take down, and no reason to
     * wake the main thread for every frame of a scroll. The exceptions are
     * a frame being read ahead, and a count of cancelled passes for the
     * scroll to wipe.
     */
    private fun scrolled(now: Long) {
        // Moving on after a long look at one screen: the radio has likely
        // dropped to idle, and waking it costs the next request a few
        // hundred milliseconds on mobile data. It wakes now, during the
        // scroll, instead.
        if (now - lastMotionAt >= LONG_LOOK_MS) pipeline.warm(settings, afterIdle = true)
        lastMotionAt = now
        if (state != State.SCANNING || preparing || midPassCancels > 0) scope.launch { onMotion() }
    }

    /**
     * The screen moved, or — [pageChanged] — the page under the cards is no
     * longer the page they were written for. Main thread only.
     */
    private fun onMotion(pageChanged: Boolean = false) {
        // A scroll is the reader moving on, which a region that never stops
        // changing cannot pass for. Whatever stop comes next gets the full
        // budget of page-change cancels again.
        if (!pageChanged) midPassCancels = 0
        when (state) {
            State.TRANSLATING -> {
                if (pageChanged) midPassCancels++
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
            // Between stops the only thing left to throw away is a frame
            // read ahead, which the screen no longer shows.
            State.SCANNING -> if (!pageChanged) discardPrepared()
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
     * Paints cards and records the cells they cover, in one step — the mask
     * must never describe cards other than the ones actually on screen.
     * That includes the lines painted mid-pass as they stream in: left
     * unrecorded, their static pixels would count as the page not having
     * changed, and the balloon that slid in under one would never be
     * noticed. Main thread only.
     */
    private fun paintCards(bubbles: List<RenderBubble>, frame: Bitmap? = null) {
        val view = controller?.bubbleView ?: return
        if (frame != null && frame !== cardsFrom && bubbles.isNotEmpty()) {
            cardsFrom = frame
            cardsPage = if (view.veil > 0f) frame.copy(Bitmap.Config.ARGB_8888, false) else null
        }
        view.setBubbles(bubbles, cardsPage)
        setOverlayMask(currentOverlayMask())
    }

    /**
     * Veils the page while MangaLens is awake and the reader wants no
     * ghosts (see BubbleOverlayView.veiled). Frames are read through the
     * veil from the next one on; the one or two the screen takes to catch
     * up change all over like a scroll does, which only restarts the wait
     * for the page to settle. Main thread only.
     */
    private fun updateVeil() {
        val view = controller?.bubbleView ?: return
        view.veiled = !paused && settings.noGhosts
        screenLevel = view.screenLevel
        if (view.veil <= 0f) {
            cardsPage = null
            cardsFrom = null
        }
    }

    /** Clears cards and the mask that described them. Main thread only. */
    private fun clearCards() {
        controller?.bubbleView?.clear()
        setOverlayMask(currentOverlayMask())
    }

    /** The floating controls moved, or the pill came, went or was re-measured. Main thread only. */
    private fun refreshOverlayMask() {
        setOverlayMask(currentOverlayMask())
    }

    /** The cells under the cards on screen, if any, and under the floating controls. */
    private fun currentOverlayMask(): BooleanArray? {
        val rects = ArrayList<Rect>()
        controller?.let { c ->
            if (c.bubbleView.hasBubbles()) rects.addAll(c.bubbleView.placedRects())
            rects.addAll(c.bubbleView.debugStrokeRects())
            rects.addAll(c.overlayExclusions())
        }
        return if (rects.isEmpty() || capW <= 0 || capH <= 0) null else FrameStability.mask(rects, capW, capH)
    }

    /**
     * Records what our overlays cover. The cells the previous mask covered
     * and this one does not stay excluded for [MASK_GRACE_MS] more: the
     * screen catches up with a change a few frames after it is made, and
     * until it has, those cells still show the card or pill that was there
     * — which, compared against the translated page, looks exactly like
     * the page having changed. Main thread only.
     */
    private fun setOverlayMask(current: BooleanArray?) {
        val previous = overlayMask
        val epoch = ++maskEpoch
        overlayMask = FrameStability.union(previous, current)
        if (previous == null) return
        scope.launch {
            delay(MASK_GRACE_MS)
            if (maskEpoch == epoch) overlayMask = current
        }
    }

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                delay(untilNextTick())
                if (paused || settings.mode == CaptureMode.MANUAL) continue
                val now = SystemClock.uptimeMillis()
                // Auto-scroll dragging the page is motion, from the finger
                // coming down to its lift, as a reader's own scroll is. A
                // glide slow enough to read along with moves the page too
                // little between frames for frame differencing to see on a
                // sparse strip, and a blank gutter shows no change at all.
                // Unseen, it would have a pass read a page on the move, and
                // one finishing mid-glide would pass for the stop's own.
                if (autoScroller?.moving == true) {
                    pipeline.warm(settings, afterIdle = now - lastMotionAt >= LONG_LOOK_MS)
                    lastMotionAt = now
                    if (state != State.SCANNING || preparing || midPassCancels > 0) onMotion()
                    continue
                }
                if (projection == null || state != State.SCANNING) continue
                // The reader is scrolling toward the next stop: have the
                // connection open by the time they get there.
                if (now - lastMotionAt < 200) pipeline.warm(settings)
                // Not held behind suppressUntil: that masks our own painting
                // from the motion check, and while scanning the cards are
                // gone — the scroll that got here cleared them. A reader who
                // moves on while lines still stream in stopped being read
                // for up to 0.6 s after the last one landed.
                if (lastFrameAt <= 0) continue
                val quiet = now - lastMotionAt
                if (quiet >= settings.stabilityMs) {
                    startTranslate(auto = true)
                } else if (quiet >= EARLY_ANALYSIS_MS && !preparing) {
                    startPrepare()
                }
            }
        }
    }

    /**
     * Time to the ticker's next look: a regular beat, but exactly on the
     * moment the quiet reaches the read-ahead or the stability window, so
     * neither starts up to a beat late.
     */
    private fun untilNextTick(): Long {
        val quiet = SystemClock.uptimeMillis() - lastMotionAt
        var next = TICK_MS
        for (at in longArrayOf(if (preparing) Long.MAX_VALUE else EARLY_ANALYSIS_MS, settings.stabilityMs.toLong())) {
            if (at > quiet) next = minOf(next, at - quiet)
        }
        return next.coerceAtLeast(1L)
    }

    /**
     * Grabs the frame and starts reading it before the stability window
     * has run out. Nothing is painted from here; [startTranslate] picks the
     * reading up if the frame is still the one on screen, and any motion
     * in between throws it away.
     */
    private fun startPrepare() {
        // Never read a frame with our own cards on it, nor one no AI can
        // translate: the pass will only say what is missing.
        if (controller?.bubbleView?.hasBubbles() == true) return
        if (!settingsLoaded || LlmHttp.setupNeeded(settings) != null) return
        val bmp = grabFrame() ?: return
        val exclusions = controller?.overlayExclusions() ?: emptyList()
        preparing = true
        val thumb = FrameStability.grayThumbOf(bmp)
        val p = Prepared(bmp, thumb)
        val current = settings
        p.job = scope.async(Dispatchers.Default) {
            // Encoding the page for the model and reading it on-device need
            // nothing from each other: the request is prepared and sent
            // while the analysis runs. The job ends when both have.
            launch { p.adopt(pipeline.startRead(bmp, current, readScope)) }
            thumb to pipeline.analyze(bmp, current, exclusions)
        }
        prepared = p
    }

    /**
     * Where AI reads started ahead of a pass run: the service's lifetime,
     * off the main thread, and outside any one pass's job — a read started
     * ahead of the stability window outlives the preparation that started
     * it. Nothing stops such a read but whoever holds it: [abandon] for a
     * frame read ahead, and the pass's own cleanup once it has taken the
     * frame over.
     */
    private val readScope by lazy { CoroutineScope(scope.coroutineContext + Dispatchers.IO) }

    /** Drops the frame read ahead, if any. Main thread only. */
    private fun discardPrepared() {
        val p = prepared
        prepared = null
        preparing = false
        if (p != null) abandon(p)
    }

    /**
     * Gives up a frame read ahead. Its analysis and its AI read stop, and
     * the frame is freed once nothing can still be looking at it. The read
     * does not stop with its analysis, since it runs in [readScope], and
     * nobody else will cancel it. Left running, it streams on to the end,
     * spends the rate limit on racing requests, and teaches the glossary
     * and cast from a page nobody reads. Main thread only.
     */
    private fun abandon(p: Prepared) {
        p.job.cancel()
        p.drop()
        retireLater(p.bitmap)
    }

    /**
     * Whether the frame read ahead is still what the screen shows, judged
     * over the cells our own overlays do not cover — the button's busy ring
     * and whatever the pill says, which must not count. Drift is measured
     * two ways, as a page change is: by how far the cells moved on average,
     * which a scroll makes obvious, and by how many moved at all, which a
     * tap-to-turn between two mostly-white pages does and the average hides.
     */
    private fun stillOnScreen(read: IntArray, live: IntArray?): Boolean {
        val mask = overlayMask
        return FrameStability.meanDiff(read, live, mask) <= PREPARED_MAX_DRIFT &&
            FrameStability.changedFraction(read, live, mask) <= PAGE_CHANGE_FRACTION
    }

    /**
     * [lastShown], moved to where the scroll since it was lettered put its
     * lettering on [frame]; empty when that scroll is not known.
     */
    private fun carry(frame: Bitmap): List<RenderBubble> {
        val before = shownOn ?: return emptyList()
        if (lastShown.isEmpty()) return emptyList()
        val now = pipeline.signatureOf(frame) ?: return emptyList()
        val d = now.scrolledFrom(before) ?: return emptyList()
        val ignoreTop = (frame.height * settings.ignoreTopPct).toInt()
        val ignoreBottom = (frame.height * settings.ignoreBottomPct).toInt()
        return CardCarry.carried(lastShown, -d, frame.height, ignoreTop, ignoreBottom) { box ->
            now.keeps(before, d, box.top, box.bottom)
        }
    }

    /**
     * Hands over the frame read ahead, or null when there is none. The
     * caller owns it from here, and must adopt or [abandon] it however it
     * ends. Main thread only.
     */
    private fun takePrepared(): Prepared? {
        val p = prepared ?: return null
        prepared = null
        preparing = false
        if (p.job.isCancelled) {
            abandon(p)
            return null
        }
        return p
    }

    /**
     * Frees a frame whose reading was cut short. The recognizer may still
     * be looking at the pixels for a moment after its coroutine is
     * cancelled, so the memory is given back a little later rather than
     * from under it.
     */
    private fun retireLater(bmp: Bitmap) {
        scope.launch {
            delay(1500)
            runCatching { bmp.recycle() }
        }
    }

    private fun startTranslate(auto: Boolean) {
        if (state == State.TRANSLATING || !settingsLoaded) return
        // Translation is the AI's alone. Without a key there is nothing to
        // ask, and a pass would only spin the busy ring to paint nothing,
        // so the reader is told what to add instead — once per stop: a
        // bare page counts as shown, with nothing on it, until they move
        // it. Cards already up from an earlier pass stay, watched as before.
        LlmHttp.setupNeeded(settings)?.let { missing ->
            discardPrepared()
            if (state == State.SCANNING) {
                lastShown = emptyList()
                state = State.SHOWING
                passes++
            }
            setPill(missing, 4000)
            return
        }
        state = State.TRANSLATING
        translateJob = scope.launch {
            pushBusy()
            // What the pass holds, and must give back however it ends:
            // finished, failed, or cancelled by a scroll or a pause at any
            // point where it suspends. Each is recorded the moment the pass
            // takes it on, before it can suspend again. A frame read ahead is
            // held whole until the pass adopts its bitmap and read in one
            // step. A read started here is recorded as it starts, not once
            // the page has been analysed. AI reads run outside this job (see
            // [readScope]), and one missed here would run to its end.
            var prep: Prepared? = null
            var bmp: Bitmap? = null
            var read: PendingRead? = null
            // The stop this pass answers (a tap, when asked for by hand), and
            // when its first English landed: what the reader waits for,
            // shown with diagnostics on.
            val stopAt = if (auto) lastMotionAt else SystemClock.uptimeMillis()
            var firstLineAt = -1L
            // The lines on screen so far, as they streamed in.
            var streamed: List<RenderBubble> = emptyList()
            // The last stop's cards, moved by the scroll, standing in until
            // this pass letters the same lines itself.
            var carried: List<RenderBubble> = emptyList()
            try {
                // A long enough break since the last translated page means the
                // next one probably belongs to a different series.
                works.beginPass(System.currentTimeMillis())
                shownThumb = null
                val exclusions = controller?.overlayExclusions() ?: emptyList()

                // The page may already have been read: analysis starts as
                // soon as the screen goes quiet, ahead of the stability
                // window, and is picked up here when the frame has not moved
                // since. Otherwise the frame is grabbed and read now.
                //
                // Either way the baseline for page-change detection is taken
                // from the exact bitmap handed to the pipeline. Waiting for a
                // later "settled" frame instead left a hole: a fling inside
                // the suppression window put a new page on screen first, the
                // baseline then described the new page while the cards
                // described the old one, and the mismatch could never be
                // noticed.
                var ahead: TranslatePipeline.Analysis? = null
                val taken = takePrepared()?.let { t ->
                    // A frame the screen has already moved on from — the
                    // page was still creeping when it was grabbed — is given
                    // up at once, not after its whole analysis.
                    if (stillOnScreen(t.thumb, latestThumb)) t else {
                        abandon(t)
                        null
                    }
                }
                prep = taken
                if (taken != null && auto) {
                    // A scroll cleared the cards. Those still on screen come
                    // back now, where the measured scroll put their lettering,
                    // rather than once the page is analysed, memory has found
                    // them again and each is re-lettered: on a phone, most of
                    // a second more of a raw page after every nudge.
                    carried = carry(taken.bitmap)
                    if (carried.isNotEmpty()) {
                        firstLineAt = SystemClock.uptimeMillis()
                        lastShown = carried
                        shownOn = pipeline.signatureOf(taken.bitmap)
                        suppressUntil = SystemClock.uptimeMillis() + 600
                        paintCards(carried, taken.bitmap)
                    }
                }
                if (taken != null) {
                    val done = try {
                        taken.job.await()
                    } catch (e: CancellationException) {
                        if (!isActive) throw e
                        null
                    } catch (_: Exception) {
                        null
                    }
                    // Either the pass adopts the frame and its read, or it
                    // gives both up. It holds them whole no longer.
                    prep = null
                    if (done != null && stillOnScreen(done.first, latestThumb)) {
                        bmp = taken.bitmap
                        read = taken.read
                        shownThumb = done.first
                        ahead = done.second
                    } else {
                        // The screen moved under the reading, or it failed.
                        // What was carried was for that frame: the fresh one
                        // is grabbed clean of it, and letters its own lines.
                        abandon(taken)
                        carried = emptyList()
                    }
                }
                val analysis: TranslatePipeline.Analysis = ahead ?: run {
                    val fresh = grabCleanBitmap()
                    if (fresh == null) {
                        state = State.SCANNING
                        return@launch
                    }
                    bmp = fresh
                    // A read the pass starts itself runs as the pass's child.
                    // A scroll or a pause then stops it the moment the pass is
                    // cancelled, not once analysis next looks up from the
                    // pixels. A read started ahead cannot be a child: it began
                    // before the pass did.
                    val passScope = CoroutineScope(coroutineContext + Dispatchers.IO)
                    withContext(Dispatchers.Default) {
                        // The model needs nothing analysis produces, so it
                        // starts first and reads while the page is analysed.
                        read = pipeline.startRead(fresh, settings, passScope)
                        shownThumb = FrameStability.grayThumbOf(fresh)
                        pipeline.analyze(fresh, settings, exclusions)
                    }
                }
                val passRead = read

                // While the pass works, the button's busy ring says so and
                // the pill stays out of the way. The one step worth a word
                // is the art clean-up, which holds the finished page back.
                var spoke = false
                val result = withContext(Dispatchers.Default) {
                    pipeline.translate(analysis, settings, read = passRead, onPartial = { partial ->
                        // More of the AI's answer landed — paint it now, the
                        // rest follows.
                        withContext(Dispatchers.Main.immediate) {
                            if (isActive && state == State.TRANSLATING) {
                                if (firstLineAt < 0 && partial.bubbles.isNotEmpty()) firstLineAt = SystemClock.uptimeMillis()
                                streamed = partial.bubbles
                                // Carried cards give way to the lines this
                                // pass letters over them, and stay until then.
                                val shown = UpgradeMerge.merge(carried, partial.bubbles)
                                lastShown = shown
                                shownOn = bmp?.let(pipeline::signatureOf)
                                suppressUntil = SystemClock.uptimeMillis() + 600
                                paintCards(shown, bmp)
                                if (partial.note == "cleaning art…") {
                                    setPill("✨ cleaning art…")
                                    spoke = true
                                }
                            }
                        }
                    })
                }
                if (!isActive) return@launch
                // The finished answer replaces the lines it covers and never
                // erases the ones it doesn't: the reader may be reading them.
                // Cards carried from the last stop go now: the answer holds
                // every line this stop read or remembered.
                val shown = UpgradeMerge.merge(streamed, result.bubbles)
                lastShown = shown
                shownOn = bmp?.let(pipeline::signatureOf)
                suppressUntil = SystemClock.uptimeMillis() + 500
                paintCards(shown, bmp)
                state = State.SHOWING
                passes++
                // A page with dialogue keeps the current work alive and feeds it
                // the names that identify it; a run of pages without any means
                // the reader has left the story — an index, a cover, a menu.
                if (shown.isNotEmpty()) {
                    works.noteTranslated(System.currentTimeMillis(), glossary.snapshot().keys)
                } else {
                    works.noteQuietPass()
                }
                controller?.bubbleView?.setDebugBalloons(
                    if (settings.diagnostics) result.balloons else emptyList(),
                    if (settings.diagnostics) result.panels else emptyList(),
                )
                if (settings.diagnostics) refreshOverlayMask()
                // A page that stays the page it was for a moment after the
                // pass is a page whose changes are real, not perpetual.
                scope.launch {
                    delay(1500)
                    if (state == State.SHOWING && lastShown === shown) midPassCancels = 0
                }
                // A problem the reader has to act on comes before everything
                // else, in auto mode too. Diagnostics stay up: they exist to
                // be read off a page that came back wrong, and a pill that
                // vanishes is no use for that. A page that simply came out
                // right needs no word at all — the lines are on it.
                val alert = result.alert?.takeIf { it.isNotBlank() && it != alerted }
                val failure = result.failure
                if (alert != null) {
                    showAlert(alert)
                } else if (result.diag != null) {
                    val now = SystemClock.uptimeMillis()
                    val waited = (if (firstLineAt >= 0) "stop→1st line ${firstLineAt - stopAt} ms · " else "") +
                        (if (carried.isNotEmpty()) "carried ${carried.size} · " else "") +
                        "stop→done ${now - stopAt} ms"
                    setPill("${result.engineLabel.ifBlank { "—" }} · $waited · ${result.diag} · ${works.describe()}")
                } else if (failure != null) {
                    setPill(failurePill(failure, partly = shown.isNotEmpty()), FAILURE_MS)
                } else if (shown.isEmpty() && !auto) {
                    setPill("no text on this page", 1800)
                } else if (spoke) {
                    setPill(null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page still needs watching or a failed pass would sit
                // here until something scrolls. The baseline from the grabbed
                // frame (when the grab got that far) and the mask over
                // whatever lines streamed in are both already in place.
                state = State.SHOWING
                // Nothing else translates the page, so the reader is told
                // why it stays raw. A rejected key fails every page alike
                // and gets the one-time alert first.
                passes++
                val rejected = if (AiFailure.keyRejected(e)) {
                    LlmHttp.providerLabel(settings) + " rejected the API key — check it in MangaLens"
                } else {
                    null
                }
                val cause = AiFailure.cause(e)
                when {
                    rejected != null && rejected != alerted -> showAlert(rejected)
                    // Not the AI's doing (on-device analysis threw, say):
                    // the page is not blamed on it.
                    cause == AiFailure.UNEXPECTED -> setPill("⚠ couldn't read this page — scroll a little to retry", FAILURE_MS)
                    else -> setPill(failurePill(cause, partly = streamed.isNotEmpty()), FAILURE_MS)
                }
            } finally {
                // Cancellation is this loop's steady state. Every scroll
                // that interrupts a pass lands here, so what the pass holds
                // is given back on that path too, not only on success. The
                // read stays the pass's to stop even once it is passed to
                // translate, which reads from it but does not stop it when
                // the pass is cancelled. Cancelling a finished read does
                // nothing.
                prep?.let { abandon(it) }
                read?.cancel()
                bmp?.let { if (isActive) it.recycle() else retireLater(it) }
                popBusy()
            }
        }
    }

    /**
     * Returns a copy of the newest frame with our own overlays guaranteed
     * absent. If overlays are visible they are cleared first and a fresh,
     * clean frame awaited, so OCR never reads our own English.
     */
    private suspend fun grabCleanBitmap(): Bitmap? {
        val hadOverlays = controller?.bubbleView?.hasBubbles() == true
        if (hadOverlays) {
            clearCards()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(280)
        }
        return grabFrame()
    }

    /**
     * A private copy of the newest frame, cropped of any stride padding, or
     * null before the first frame has arrived. Always a copy, so the reused
     * capture buffers stay the capture thread's own.
     */
    private fun grabFrame(): Bitmap? = synchronized(frameLock) {
        latestBitmap?.let { src ->
            val w = capW.coerceAtMost(src.width)
            val h = capH.coerceAtMost(src.height)
            if (latestLevel != 1f) return@let lifted(src, w, h, 1f / latestLevel)
            val out = Bitmap.createBitmap(src, 0, 0, w, h)
            if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
        }
    }

    /** The top-left [w]×[h] of [src], brightened by [gain]: the page as it is under MangaLens's veil. */
    private fun lifted(src: Bitmap, w: Int, h: Int, gain: Float): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lift = Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val area = Rect(0, 0, w, h)
        Canvas(out).drawBitmap(src, area, area, lift)
        return out
    }

    /**
     * Shows [text] in the status pill, or hides it. While an alert holds
     * the pill (see [showAlert]), the loop's own messages leave it alone. A
     * scroll, or the next page's own word, would otherwise wipe the one
     * message the reader has to act on before they could read it, and
     * it is not shown twice. Main thread only.
     */
    private fun setPill(text: String?, autoHideMs: Long = 0) {
        if (SystemClock.uptimeMillis() < alertUntil) return
        controller?.setStatus(text, autoHideMs)
    }

    /** Answers something the reader just did. It shows at once, over an alert too. Main thread only. */
    private fun answer(text: String, autoHideMs: Long) {
        alertUntil = 0L
        setPill(text, autoHideMs)
    }

    /**
     * Shows a problem the reader has to act on, such as a rejected key. It
     * shows in auto mode too, where the pill otherwise stays quiet, because
     * nothing on the page would say why it stays untranslated. Each text is
     * shown once, unless another comes between. A rejected key fails every
     * page the same way, and once the reader has read it, they do not need
     * it over every page after. Main thread only.
     */
    private fun showAlert(text: String) {
        alerted = text
        alertUntil = 0L
        setPill(if (text.startsWith("⚠")) text else "⚠ $text", ALERT_MS)
        alertUntil = SystemClock.uptimeMillis() + ALERT_MS
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
        pipeline.forgetRecent()
        answer("new series · old names forgotten", 2000)
    }

    override fun onTogglePause() {
        paused = !paused
        updateVeil()
        if (paused) {
            // Cancelling the pass stops its AI read too: the pass gives
            // back everything it holds on the way out. A frame read ahead
            // and not yet taken is stopped here.
            translateJob?.cancel()
            discardPrepared()
            state = State.SCANNING
            shownThumb = null
            clearCards()
            answer("napping · tap 文\u2060A to wake me", 1600)
        } else {
            answer("awake!", 1200)
        }
        pausedState.value = paused
        controller?.setPaused(paused)
        updateNotification()
    }

    override fun onToggleMode() {
        val next = if (settings.mode == CaptureMode.AUTO) CaptureMode.MANUAL else CaptureMode.AUTO
        scope.launch { settingsRepo.setMode(next) }
        answer(if (next == CaptureMode.AUTO) "hands-free · I translate when you stop" else "tap 文\u2060A to translate each page", 2200)
    }

    override fun onPeek() {
        if (state != State.SHOWING) return
        val shown = lastShown
        if (shown.isEmpty()) return
        scope.launch {
            clearCards()
            suppressUntil = SystemClock.uptimeMillis() + 900
            delay(4000)
            if (state != State.SHOWING) return@launch
            suppressUntil = SystemClock.uptimeMillis() + 900
            if (lastShown === shown) {
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
                .putExtra(MainActivity.EXTRA_OPEN_TWEAKS, true)
        )
    }

    override fun onStopRequested() {
        stopSelf()
    }

    override fun isPaused() = paused

    override fun isAutoMode() = settings.mode == CaptureMode.AUTO

    override fun isAutoScrolling() = autoScroller?.running == true

    override fun onToggleAutoScroll() {
        val scroller = autoScroller ?: return
        if (scroller.running) {
            scroller.stop(null)
            answer("auto-scroll off", 1200)
            return
        }
        if (!scroller.start()) {
            // Only an accessibility service may move another app's page.
            answer("switch on \"MangaLens auto-scroll\" in Accessibility first", 5000)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_OPEN_SCROLL, true)
            )
            return
        }
        answer(
            if (scrollPage.translating()) "auto-scroll on · I stop at each page to translate it · tap 文\u2060A to nap and glide"
            else "auto-scroll on · touch the screen to pause it",
            2600,
        )
    }

    override fun onScrollSpeed(step: Int) {
        val scroller = autoScroller ?: return
        // From the scroller's own level: taps faster than the store echoes each count.
        val next = ScrollPace.level(scroller.level + step)
        scroller.level = next
        scroller.carryOn()
        scope.launch { settingsRepo.setScrollLevel(next) }
        answer("speed $next", 900)
    }

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
            .setContentTitle("MangaLens is reading along")
            .setContentText(if (paused) "Napping. Tap Resume to wake me" else "On. I translate the pages as you read")
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
        autoScroller?.close()
        autoScroller = null
        running.value = false
        pausedState.value = false
        translateJob?.cancel()
        discardPrepared()
        scope.cancel()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        controller?.onFootprintChanged = null
        controller?.bubbleView?.onVeilChanged = null
        controller?.detach()
        controller = null
        releaseFrameBuffers()
        captureThread?.quitSafely()
        captureThread = null
        super.onDestroy()
    }
}
