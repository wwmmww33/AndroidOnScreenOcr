package com.example.onscreenocr2

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var overlayView: View
    private lateinit var bubbleView: View
    
    private var screenDensity: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var currentRect: Rect? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    companion object {
        private const val NOTIFICATION_ID = 1234
        private const val CHANNEL_ID = "OCR_CHANNEL"
        var mediaProjectionResultData: Intent? = null
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        updateScreenMetrics()
        startForeground(NOTIFICATION_ID, createNotification())
        
        initOverlayView()
        initBubbleView()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "OCR Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OCR Running")
            .setContentText("Ready to capture multiple times")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaProjection == null && mediaProjectionResultData != null) {
            setupMediaProjection()
        }
        return START_STICKY
    }

    private fun setupMediaProjection() {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionResultData!!)
            
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OCR_PERMANENT", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseProjection()
                }
            }, mainHandler)

            showGlobalToast("✅ Engine Ready")
        } catch (e: Exception) {
            Log.e("OCR", "Setup failed: ${e.message}")
        }
    }

    private fun startCaptureProcess() {
        if (mediaProjection == null) {
            showGlobalToast("❌ Session expired. Re-open app.")
            return
        }
        if (currentRect == null) return

        resetOverlay()

        mainHandler.postDelayed({
            captureFromBuffer()
        }, 500)
    }

    private fun captureFromBuffer() {
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                mainHandler.postDelayed({ captureFromBuffer() }, 100)
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            processResult(bitmap)
        } catch (e: Exception) {
            Log.e("OCR", "Buffer Error: ${e.message}")
            showGlobalToast("❌ Capture failed")
        }
    }

    private fun processResult(fullBitmap: Bitmap) {
        val rect = currentRect ?: return
        try {
            val x = maxOf(0, rect.left)
            val y = maxOf(0, rect.top)
            val width = minOf(rect.width(), fullBitmap.width - x)
            val height = minOf(rect.height(), fullBitmap.height - y)

            if (width < 10 || height < 10) return

            val cropped = Bitmap.createBitmap(fullBitmap, x, y, width, height)
            val inputImage = InputImage.fromBitmap(cropped, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isNotBlank()) {
                        copyToClipboard(visionText.text)
                        showGlobalToast("✅ Text Copied!")
                    } else {
                        showGlobalToast("ℹ️ No text found")
                    }
                }
                .addOnFailureListener { showGlobalToast("❌ OCR Failed") }
        } catch (e: Exception) {
            showGlobalToast("❌ Processing Error")
        }
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection = null
    }

    private fun initOverlayView() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(overlayView, params)
        overlayView.visibility = View.GONE

        val selectionView = overlayView.findViewById<SelectionView>(R.id.selectionView)
        val layoutOptions = overlayView.findViewById<LinearLayout>(R.id.layoutOptions)

        selectionView.onSelectionChanged = { rect ->
            currentRect = rect
            if (rect != null) {
                layoutOptions.visibility = View.VISIBLE
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                windowManager.updateViewLayout(overlayView, params)
            }
        }

        overlayView.findViewById<Button>(R.id.btnArabic).setOnClickListener { startCaptureProcess() }
        overlayView.findViewById<Button>(R.id.btnEnglish).setOnClickListener { startCaptureProcess() }
        overlayView.findViewById<Button>(R.id.btnCancel).setOnClickListener { resetOverlay() }
    }

    private fun resetOverlay() {
        overlayView.visibility = View.GONE
        overlayView.findViewById<LinearLayout>(R.id.layoutOptions).visibility = View.GONE
        overlayView.findViewById<SelectionView>(R.id.selectionView).reset()
        val params = overlayView.layoutParams as WindowManager.LayoutParams
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(overlayView, params)
    }

    private fun initBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0; params.y = 500
        windowManager.addView(bubbleView, params)

        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                            moved = true
                            params.x = initialX + dx
                            params.y = initialY + dy
                            windowManager.updateViewLayout(bubbleView, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) overlayView.visibility = View.VISIBLE
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateScreenMetrics() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
    }

    private fun showGlobalToast(msg: String) {
        mainHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProjection()
    }

    override fun onBind(intent: Intent?) = null
}
