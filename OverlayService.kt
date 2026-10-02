package com.example.screentranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.nl.translate.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// ---- change these two to switch languages ----
private const val SOURCE = TranslateLanguage.ENGLISH
private const val TARGET = TranslateLanguage.PERSIAN
// ----------------------------------------------

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var overlay: OverlayView
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private val ui = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var sw = 0; private var sh = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(SOURCE).setTargetLanguage(TARGET).build())
    private val cache = HashMap<String, String>()
    private var modelReady = false
    private var busy = false
    private var showing = false
    private var auto = false
    private val autoTick = object : Runnable {
        override fun run() { if (auto) { translateOnce(); ui.postDelayed(this, 2500) } }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification(), 
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, ui)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        sw = dm.widthPixels; sh = dm.heightPixels
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vDisplay = projection!!.createVirtualDisplay("cap", sw, sh, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)

        addOverlay(); addBubble()
        translator.downloadModelIfNeeded().addOnSuccessListener { modelReady = true }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val ch = "st"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ch, "Translator", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, ch).setContentTitle("Screen Translator running")
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
    }

    private fun addOverlay() {
        overlay = OverlayView(this)
        val lp = WindowManager.LayoutParams(-1, -1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        wm.addView(overlay, lp)
    }

    private fun addBubble() {
        val d = resources.displayMetrics.density
        bubble = TextView(this).apply {
            text = "文"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC3F51B5.toInt()) }
        }
        bubbleLp = WindowManager.LayoutParams((52 * d).toInt(), (52 * d).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = sw - (52 * d).toInt(); y = sh / 3
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false; var longFired = false
        val longPress = Runnable {
            longFired = true; auto = !auto
            bubble.alpha = if (auto) 1f else 0.7f
            if (auto) ui.post(autoTick) else clear()
        }
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; sx = bubbleLp.x; sy = bubbleLp.y
                    moved = false; longFired = false; ui.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 15 || Math.abs(e.rawY - dy) > 15) {
                        moved = true; ui.removeCallbacks(longPress)
                    }
                    if (moved) {
                        bubbleLp.x = sx + (e.rawX - dx).toInt(); bubbleLp.y = sy + (e.rawY - dy).toInt()
                        wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    ui.removeCallbacks(longPress)
                    if (!moved && !longFired) { if (showing) clear() else translateOnce() }
                    if (moved) { // snap to nearest side edge
                        bubbleLp.x = if (bubbleLp.x + bubble.width / 2 < sw / 2) 0 else sw - bubble.width
                        wm.updateViewLayout(bubble, bubbleLp)
                    }
                }
            }
            true
        }
        wm.addView(bubble, bubbleLp)
    }

    private fun clear() { overlay.clear(); showing = false }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val p = img.planes[0]
            val rowPad = p.rowStride - p.pixelStride * sw
            val bmp = Bitmap.createBitmap(sw + rowPad / p.pixelStride, sh, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(p.buffer)
            return Bitmap.createBitmap(bmp, 0, 0, sw, sh)
        } finally { img.close() }
    }

    private fun translateOnce() {
        if (busy || !modelReady) return
        busy = true
        overlay.clear(); bubble.visibility = android.view.View.INVISIBLE // keep our UI out of the shot
        ui.postDelayed({
            val bmp = grab()
            bubble.visibility = android.view.View.VISIBLE
            if (bmp == null) { busy = false; return@postDelayed }
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val lines = result.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                    if (lines.isEmpty()) { busy = false; return@addOnSuccessListener }
                    val out = ArrayList<Block>()
                    var pending = lines.size
                    fun done() { if (--pending == 0) { overlay.setBlocks(out); showing = true; busy = false } }
                    for (ln in lines) {
                        val r = ln.boundingBox!!
                        val src = ln.text
                        val bg = bmp.getPixel(r.left.coerceIn(0, sw - 1), r.top.coerceIn(0, sh - 1))
                        val fg = if (Color.luminance(bg) > 0.5f) Color.BLACK else Color.WHITE
                        val cached = cache[src]
                        if (cached != null) { out.add(Block(r, cached, bg, fg)); done() }
                        else translator.translate(src)
                            .addOnSuccessListener { t -> cache[src] = t; out.add(Block(r, t, bg, fg)); done() }
                            .addOnFailureListener { done() }
                    }
                }
                .addOnFailureListener { busy = false }
        }, 150)
    }

    override fun onDestroy() {
        auto = false; ui.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(overlay); wm.removeView(bubble) }
        vDisplay?.release(); reader?.close(); projection?.stop()
        translator.close(); recognizer.close()
        super.onDestroy()
    }
}
