package app.mangalens.capture

import android.os.Handler
import android.os.HandlerThread
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stop must never close the reader under a frame the capture thread is still
 * copying. The copy reads straight out of the reader's buffer, and closing
 * the reader frees that buffer: on a device, a native crash on Stop, which
 * no catch sees. Rotation already queued the close behind the frame; Stop
 * closed it from the main thread, whenever the tap came.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureTeardownTest {

    @Test
    fun `a close asked for mid-frame waits for the frame, even as the thread quits`() {
        val thread = HandlerThread("capture").apply { start() }
        val handler = Handler(thread.looper)
        val copying = CountDownLatch(1)
        val copied = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        handler.post {
            copying.countDown()
            copied.await(5, TimeUnit.SECONDS)
            events += "frame copied"
        }
        assertTrue(copying.await(5, TimeUnit.SECONDS))

        // onDestroy, on the main thread: the reader's close, then the quit.
        var closedOn: Thread? = null
        ScreenCaptureService.onCaptureThread(handler) {
            closedOn = Thread.currentThread()
            events += "reader closed"
        }
        thread.quitSafely()
        assertTrue("nothing may be closed under the copy", events.isEmpty())

        copied.countDown()
        thread.join(5000)
        assertEquals(listOf("frame copied", "reader closed"), events.toList())
        assertSame(thread, closedOn)
    }

    @Test
    fun `on the capture thread itself it runs at once`() {
        val thread = HandlerThread("capture").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var inline = false
        handler.post {
            var ran = false
            ScreenCaptureService.onCaptureThread(handler) { ran = true }
            inline = ran
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("run in place, not queued behind itself", inline)
        thread.quitSafely()
    }

    @Test
    fun `with the capture thread gone it runs at once`() {
        val thread = HandlerThread("capture").apply { start() }
        val handler = Handler(thread.looper)
        thread.quitSafely()
        thread.join(5000)
        var ranOn: Thread? = null
        ScreenCaptureService.onCaptureThread(handler) { ranOn = Thread.currentThread() }
        assertSame(Thread.currentThread(), ranOn)

        var ran = false
        ScreenCaptureService.onCaptureThread(null) { ran = true }
        assertTrue(ran)
    }
}
