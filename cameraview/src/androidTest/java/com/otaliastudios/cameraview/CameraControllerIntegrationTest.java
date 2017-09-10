package com.otaliastudios.cameraview;


import android.content.Context;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;


@RunWith(AndroidJUnit4.class)
@MediumTest
public class CameraControllerIntegrationTest extends BaseTest {

    @Rule
    public ActivityTestRule<TestActivity> rule = new ActivityTestRule<>(TestActivity.class);

    private CameraView camera;
    private Camera1 controller;
    private CameraListener listener;

    @Before
    public void setUp() {
        ui(new Runnable() {
            @Override
            public void run() {
                camera = new CameraView(rule.getActivity()) {

                    @Override
                    protected CameraController instantiateCameraController(CameraCallbacks callbacks, Preview preview) {
                        controller = new Camera1(callbacks, preview);
                        return controller;
                    }
                };

                listener = mock(CameraListener.class);
                camera.addCameraListener(listener);
                rule.getActivity().inflate(camera);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        camera.stopCapturingVideo();
        /* int state = controller.getState();
        if (state >= CameraController.STATE_STARTING) {
            // Enqueue a stop and wait.
            camera.stop();
            waitForClose(true);
        } else if (state == CameraController.STATE_STOPPING) {
            // Wait for incoming stop.
            waitForClose(true);
        } */
        camera.destroy();
    }

    private CameraOptions waitForOpen(boolean expectSuccess) {
        final Task<CameraOptions> open = new Task<>();
        open.listen();
        doEndTask(open, 0).when(listener).onCameraOpened(any(CameraOptions.class));
        CameraOptions result = open.await(2000);
        if (expectSuccess) {
            assertNotNull("Can open", result);
        } else {
            assertNull("Should not open", result);
        }
        return result;
    }

    private Boolean waitForClose(boolean expectSuccess) {
        final Task<Boolean> close = new Task<>();
        close.listen();
        doEndTask(close, true).when(listener).onCameraClosed();
        Boolean result = close.await(2000);
        if (expectSuccess) {
            assertNotNull("Can close", result);
        } else {
            assertNull("Should not close", result);
        }
        return result;
    }

    //region test open/close

    @Test
    public void testOpenClose() throws Exception {
        // Starting and stopping are hard to get since they happen on another thread.
        assertEquals(controller.getState(), CameraController.STATE_STOPPED);

        camera.start();
        waitForOpen(true);
        assertEquals(controller.getState(), CameraController.STATE_STARTED);

        camera.stop();
        waitForClose(true);
        assertEquals(controller.getState(), CameraController.STATE_STOPPED);
    }

    @Test
    public void testOpenTwice() {
        camera.start();
        waitForOpen(true);
        camera.start();
        waitForOpen(false);
    }

    @Test
    public void testCloseTwice() {
        camera.stop();
        waitForClose(false);
    }

    // @Test
    // This works great on the device but crashes on the emulator.
    // There must be something wrong with the emulated camera...
    // Like stopPreview() and release() are not really sync calls?
    public void testConcurrentCalls() throws Exception {
        final CountDownLatch latch = new CountDownLatch(4);
        doCountDown(latch).when(listener).onCameraOpened(any(CameraOptions.class));
        doCountDown(latch).when(listener).onCameraClosed();

        camera.start();
        camera.stop();
        camera.start();
        camera.stop();

        boolean did = latch.await(10, TimeUnit.SECONDS);
        assertTrue("Handles concurrent calls to start & stop, " + latch.getCount(), did);
    }

    @Test
    public void testStartInitializesOptions() {
        assertNull(camera.getCameraOptions());
        assertNull(camera.getExtraProperties());
        camera.start();
        waitForOpen(true);
        assertNotNull(camera.getCameraOptions());
        assertNotNull(camera.getExtraProperties());
    }

    //endregion

    //region test Facing/SessionType
    // Test things that should reset the camera.

    @Test
    public void testSetFacing() throws Exception {
        camera.start();
        CameraOptions o = waitForOpen(true);
        int size = o.getSupportedFacing().size();
        if (size > 1) {
            // set facing should call stop and start again.
            final CountDownLatch latch = new CountDownLatch(2);
            doCountDown(latch).when(listener).onCameraOpened(any(CameraOptions.class));
            doCountDown(latch).when(listener).onCameraClosed();

            camera.toggleFacing();

            boolean did = latch.await(2, TimeUnit.SECONDS);
            assertTrue("Handles setFacing while active", did);
        }
    }

    @Test
    public void testSetSessionType() throws Exception {
        camera.setSessionType(SessionType.PICTURE);
        camera.start();
        waitForOpen(true);

        // set session type should call stop and start again.
        final CountDownLatch latch = new CountDownLatch(2);
        doCountDown(latch).when(listener).onCameraOpened(any(CameraOptions.class));
        doCountDown(latch).when(listener).onCameraClosed();

        camera.setSessionType(SessionType.VIDEO);

        boolean did = latch.await(2, TimeUnit.SECONDS);
        assertTrue("Handles setSessionType while active", did);
        assertEquals(camera.getSessionType(), SessionType.VIDEO);
    }

    //endregion

    //region test Set Parameters
    // When camera is open, parameters will be set only if supported.

    @Test
    public void testSetZoom() {
        camera.start();
        CameraOptions options = waitForOpen(true);
        boolean can = options.isZoomSupported();
        float oldValue = camera.getZoom();
        float newValue = 0.65f;
        camera.setZoom(newValue);
        assertEquals(can ? newValue : oldValue, camera.getZoom(), 0f);
    }

    @Test
    public void testSetExposureCorrection() {
        camera.start();
        CameraOptions options = waitForOpen(true);
        boolean can = options.isExposureCorrectionSupported();
        float oldValue = camera.getExposureCorrection();
        float newValue = options.getExposureCorrectionMaxValue();
        camera.setExposureCorrection(newValue);
        assertEquals(can ? newValue : oldValue, camera.getExposureCorrection(), 0f);
    }

    @Test
    public void testSetFlash() {
        camera.start();
        CameraOptions options = waitForOpen(true);
        Flash[] values = Flash.values();
        Flash oldValue = camera.getFlash();
        for (Flash value : values) {
            camera.setFlash(value);
            if (options.supports(value)) {
                assertEquals(camera.getFlash(), value);
                oldValue = value;
            } else {
                assertEquals(camera.getFlash(), oldValue);
            }
        }
    }

    @Test
    public void testSetWhiteBalance() {
        camera.start();
        CameraOptions options = waitForOpen(true);
        WhiteBalance[] values = WhiteBalance.values();
        WhiteBalance oldValue = camera.getWhiteBalance();
        for (WhiteBalance value : values) {
            camera.setWhiteBalance(value);
            if (options.supports(value)) {
                assertEquals(camera.getWhiteBalance(), value);
                oldValue = value;
            } else {
                assertEquals(camera.getWhiteBalance(), oldValue);
            }
        }
    }

    @Test
    public void testSetHdr() {
        camera.start();
        CameraOptions options = waitForOpen(true);
        Hdr[] values = Hdr.values();
        Hdr oldValue = camera.getHdr();
        for (Hdr value : values) {
            camera.setHdr(value);
            if (options.supports(value)) {
                assertEquals(camera.getHdr(), value);
                oldValue = value;
            } else {
                assertEquals(camera.getHdr(), oldValue);
            }
        }
    }

    //endregion

    //region testSetVideoQuality
    // This can be tricky because can trigger layout changes.

    @Test(expected = IllegalStateException.class)
    public void testSetVideoQuality_whileRecording() {
        camera.setSessionType(SessionType.VIDEO);
        camera.setVideoQuality(VideoQuality.HIGHEST);
        camera.start();
        waitForOpen(true);
        camera.startCapturingVideo(null);
        camera.setVideoQuality(VideoQuality.LOWEST);
    }

    @Test
    public void testSetVideoQuality_whileInPictureSessionType() {
        camera.setSessionType(SessionType.PICTURE);
        camera.setVideoQuality(VideoQuality.HIGHEST);
        camera.start();
        waitForOpen(true);
        camera.setVideoQuality(VideoQuality.LOWEST);
        assertEquals(camera.getVideoQuality(), VideoQuality.LOWEST);
    }

    @Test
    public void testSetVideoQuality_whileNotStarted() {
        camera.setVideoQuality(VideoQuality.HIGHEST);
        assertEquals(camera.getVideoQuality(), VideoQuality.HIGHEST);
        camera.setVideoQuality(VideoQuality.LOWEST);
        assertEquals(camera.getVideoQuality(), VideoQuality.LOWEST);
    }

    @Test
    public void testSetVideoQuality_shouldRecompute() {
        // If video quality changes bring to a new capture size,
        // this might bring to a new aspect ratio,
        // which might bring to a new preview size. No idea how to test.
        assertTrue(true);
    }

    //endregion

    // TODO: startVideo, endVideo

    // TODO: startAutoFocus

    // TODO: capturePicture

    // TODO: captureSnapshot
}
