package com.example.core

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "ScreenCapture"
private const val CHANNEL_ID = "mooazb_screen_share"
private const val NOTIFICATION_ID = 4207
private const val MAX_FRAME_EDGE_PX = 1280
private const val MIN_FRAME_INTERVAL_MS = 650L

data class ScreenShareState(
    val isSharing: Boolean = false,
    val latestFrame: Bitmap? = null,
    val frameTimestampMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val errorMessage: String? = null
)

object ScreenShareCoordinator {
    private val _state = MutableStateFlow(ScreenShareState())
    val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    internal fun setSharingActive(active: Boolean, error: String? = null) {
        _state.update { current ->
            if (!active) {
                current.copy(
                    isSharing = false,
                    errorMessage = error ?: current.errorMessage
                )
            } else {
                current.copy(isSharing = true, errorMessage = null)
            }
        }
    }

    internal fun updateFrame(bitmap: Bitmap) {
        _state.update { current ->
            current.copy(
                isSharing = true,
                latestFrame = bitmap,
                frameTimestampMs = System.currentTimeMillis(),
                width = bitmap.width,
                height = bitmap.height,
                errorMessage = null
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun start(context: Context, resultCode: Int, data: Intent) {
        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun stop(context: Context) {
        val stopIntent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(stopIntent)
        _state.update { it.copy(isSharing = false) }
    }
}

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameMs: Long = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system or user")
            tearDownProjection()
            stopSelfCompat()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            recreateCaptureSurface(width, height)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                tearDownProjection()
                stopSelfCompat()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                ensureNotificationChannel()
                val notification = buildNotification()
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    ScreenShareCoordinator.setSharingActive(
                        active = false,
                        error = "Screen sharing permission was not granted."
                    )
                    stopSelfCompat()
                    return START_NOT_STICKY
                }

                startProjection(resultCode, dataIntent)
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            tearDownProjection()
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data)
            if (projection == null) {
                ScreenShareCoordinator.setSharingActive(false, "Unable to initialize MediaProjection.")
                stopSelfCompat()
                return
            }

            captureThread = HandlerThread("MooazbScreenCapture").apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            projection.registerCallback(projectionCallback, captureHandler)
            mediaProjection = projection

            val (w, h, densityDpi) = resolveCaptureDimensions()
            createVirtualDisplayAndReader(projection, w, h, densityDpi)
            ScreenShareCoordinator.setSharingActive(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            ScreenShareCoordinator.setSharingActive(
                false,
                e.localizedMessage ?: "Failed to start screen capture."
            )
            tearDownProjection()
            stopSelfCompat()
        }
    }

    private fun resolveCaptureDimensions(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val rawWidth: Int
        val rawHeight: Int
        val densityDpi: Int = resources.displayMetrics.densityDpi

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            rawWidth = bounds.width()
            rawHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            rawWidth = metrics.widthPixels
            rawHeight = metrics.heightPixels
        }

        val scale = if (max(rawWidth, rawHeight) > MAX_FRAME_EDGE_PX) {
            MAX_FRAME_EDGE_PX.toFloat() / max(rawWidth, rawHeight).toFloat()
        } else {
            1f
        }
        val targetW = (rawWidth * scale).roundToInt().coerceAtLeast(360)
        val targetH = (rawHeight * scale).roundToInt().coerceAtLeast(360)
        return Triple(targetW, targetH, densityDpi)
    }

    private fun createVirtualDisplayAndReader(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ) {
        val scaledW: Int
        val scaledH: Int
        val longest = max(width, height)
        if (longest > MAX_FRAME_EDGE_PX) {
            val ratio = MAX_FRAME_EDGE_PX.toFloat() / longest.toFloat()
            scaledW = (width * ratio).roundToInt().coerceAtLeast(320)
            scaledH = (height * ratio).roundToInt().coerceAtLeast(320)
        } else {
            scaledW = width
            scaledH = height
        }

        val reader = ImageReader.newInstance(scaledW, scaledH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            val img: Image? = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (img == null) return@setOnImageAvailableListener
            try {
                if (now - lastFrameMs < MIN_FRAME_INTERVAL_MS &&
                    ScreenShareCoordinator.state.value.latestFrame != null
                ) {
                    return@setOnImageAvailableListener
                }
                lastFrameMs = now
                imageToBitmap(img)?.let { bmp ->
                    ScreenShareCoordinator.updateFrame(bmp)
                }
            } finally {
                img.close()
            }
        }, captureHandler)

        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "MooazbScreenShare",
            scaledW,
            scaledH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )
    }

    private fun recreateCaptureSurface(rawWidth: Int, rawHeight: Int) {
        val projection = mediaProjection ?: return
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            createVirtualDisplayAndReader(
                projection = projection,
                width = rawWidth,
                height = rawHeight,
                densityDpi = resources.displayMetrics.densityDpi
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resize capture surface: ${e.message}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) return null
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val uncropped = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        uncropped.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == image.width) {
            uncropped
        } else {
            val cropped = Bitmap.createBitmap(uncropped, 0, 0, image.width, image.height)
            uncropped.recycle()
            cropped
        }
    }

    private fun tearDownProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        } finally {
            virtualDisplay = null
        }
        try {
            imageReader?.close()
        } catch (_: Exception) {
        } finally {
            imageReader = null
        }
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (_: Exception) {
        } finally {
            mediaProjection = null
        }
        try {
            captureThread?.quitSafely()
        } catch (_: Exception) {
        } finally {
            captureThread = null
            captureHandler = null
        }
        ScreenShareCoordinator.setSharingActive(false)
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Screen Sharing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Active Mooazb AI screen sharing session"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_share_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Screen sharing with Gemini AI is active")
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        tearDownProjection()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.core.SCREEN_SHARE_START"
        const val ACTION_STOP = "com.example.core.SCREEN_SHARE_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
