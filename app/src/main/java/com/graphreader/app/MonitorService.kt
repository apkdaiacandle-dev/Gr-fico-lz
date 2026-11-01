package com.graphreader.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MonitorService : Service() {
    companion object {
        const val ACTION_START = "com.graphreader.app.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS = "lzgraph_settings"
        const val KEY_INTERVAL = "update_interval_ms"
        const val DEFAULT_INTERVAL = 500L
    }

    data class Candle(
        val bullish: Boolean,
        val height: Int,
        val body: Int,
        val upperWick: Int,
        val lowerWick: Int,
        val strength: Float
    )

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastAnalysisAt = 0L
    private var latestResult = "Aguardando leitura"
    private var latestTrend = "Indefinida"
    private var latestConfidence = "—"
    private var latestDetails = "Aguardando candles"
    private var latestCandleCount = 0
    private var lastBitmapAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = NotificationCompat.Builder(this, "lzgraph_panel")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("LZGraph ativo")
            .setContentText("Captura e reconhecimento em tempo real")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (code == Activity.RESULT_OK && data != null) startCapture(code, data)
        }
        return START_STICKY
    }

    private fun intervalMs(): Long = getSharedPreferences(PREFS, MODE_PRIVATE)
        .getLong(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(100L, 5000L)

    private fun startCapture(resultCode: Int, data: Intent) {
        if (!Settings.canDrawOverlays(this)) return
        if (panel == null) showPanel()
        if (projection != null) return

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            val interval = intervalMs()
            if (now - lastAnalysisAt < interval) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastAnalysisAt = now
            try {
                analyzeImage(image)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection!!.createVirtualDisplay(
            "LZGraphCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
    }

    private fun analyzeImage(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // Área central evita status bar e grande parte do painel do próprio LZGraph.
        val left = (image.width * 0.05f).toInt()
        val right = (image.width * 0.95f).toInt().coerceAtMost(bitmap.width - 1)
        val top = (image.height * 0.12f).toInt()
        val bottom = (image.height * 0.86f).toInt().coerceAtMost(bitmap.height - 1)
        val step = max(3, image.width / 260)

        val xScores = ArrayList<Int>()
        var green = 0
        var red = 0
        var x = left
        while (x < right) {
            var candlePixels = 0
            var y = top
            while (y < bottom) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                if (g > 115 && g > r * 1.20f && g > b * 1.03f) {
                    green++; candlePixels++
                } else if (r > 115 && r > g * 1.20f && r > b * 1.10f) {
                    red++; candlePixels++
                }
                y += step
            }
            xScores.add(candlePixels)
            x += step
        }

        val runs = mutableListOf<IntRange>()
        var runStart = -1
        for (i in xScores.indices) {
            val active = xScores[i] >= 2
            if (active && runStart < 0) runStart = i
            if ((!active || i == xScores.lastIndex) && runStart >= 0) {
                val end = if (!active) i - 1 else i
                if (end - runStart >= 0) runs.add(runStart..end)
                runStart = -1
            }
        }

        // Junta pequenas quebras causadas por pixels antialiasados.
        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            if (merged.isNotEmpty() && r.first - merged.last().last <= 2) {
                val old = merged.removeAt(merged.lastIndex)
                merged.add(old.first..r.last)
            } else merged.add(r)
        }

        val candles = mutableListOf<Candle>()
        for (r in merged.takeLast(30)) {
            val pxStart = left + r.first * step
            val pxEnd = min(right - 1, left + (r.last + 1) * step)
            var greenCount = 0
            var redCount = 0
            var minY = bottom
            var maxY = top
            var colored = 0
            var yy = top
            while (yy < bottom) {
                var xx = pxStart
                while (xx <= pxEnd) {
                    val c = bitmap.getPixel(xx, yy)
                    val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
                    val isGreen = gg > 115 && gg > rr * 1.20f && gg > bb * 1.03f
                    val isRed = rr > 115 && rr > gg * 1.20f && rr > bb * 1.10f
                    if (isGreen || isRed) {
                        colored++
                        if (isGreen) greenCount++ else redCount++
                        minY = min(minY, yy); maxY = max(maxY, yy)
                    }
                    xx += step
                }
                yy += step
            }
            if (colored < 3 || maxY <= minY) continue
            val height = maxY - minY + 1
            val body = max(1, (colored.toFloat() / max(1, (r.last-r.first+1))).roundToInt())
            val bullish = greenCount >= redCount
            val wickTotal = max(0, height - body)
            val upperWick = wickTotal / 2
            val lowerWick = wickTotal - upperWick
            val strength = abs(greenCount - redCount).toFloat() / colored.coerceAtLeast(1)
            candles.add(Candle(bullish, height, min(body, height), upperWick, lowerWick, strength))
        }

        val total = green + red
        val greenRatio = if (total == 0) 0f else green.toFloat() / total
        val redRatio = if (total == 0) 0f else red.toFloat() / total
        val trend = when {
            greenRatio > 0.62f -> "ALTA"
            redRatio > 0.62f -> "BAIXA"
            greenRatio > 0.54f -> "ALTA LEVE"
            redRatio > 0.54f -> "BAIXA LEVE"
            else -> "LATERAL"
        }

        val result = classifyPattern(candles, trend)
        val confidenceBase = if (candles.size >= 3) 55 else 45
        val colorConfidence = (abs(greenRatio - redRatio) * 70f).roundToInt()
        val confidence = (confidenceBase + colorConfidence + result.second).coerceIn(40, 96)
        latestCandleCount = candles.size
        latestDetails = result.third
        lastBitmapAt = SystemClock.elapsedRealtime()
        updatePanel(result.first, trend, "$confidence%")
        bitmap.recycle()
    }

    private fun classifyPattern(c: List<Candle>, trend: String): Triple<String, Int, String> {
        if (c.isEmpty()) return Triple("Sem candles detectados", 0, "Amplie ou reposicione o gráfico")
        val last = c.last()
        val prev = c.getOrNull(c.lastIndex - 1)

        val smallBody = last.body <= last.height * 0.28f
        val longLower = last.lowerWick >= last.body * 1.6f
        val longUpper = last.upperWick >= last.body * 1.6f
        val strong = last.strength >= 0.35f

        if (smallBody && longLower && !longUpper) {
            return Triple("Martelo possível", 18, "Corpo pequeno + sombra inferior longa")
        }
        if (smallBody && longUpper && !longLower) {
            return Triple("Estrela cadente possível", 17, "Corpo pequeno + sombra superior longa")
        }
        if (smallBody && last.upperWick > last.body && last.lowerWick > last.body) {
            return Triple("Doji possível", 16, "Corpo pequeno com sombras nos dois lados")
        }

        if (prev != null && prev.bullish != last.bullish && strong) {
            if (!prev.bullish && last.bullish) {
                return Triple("Engolfo de alta possível", 20, "Mudança de cor + força compradora")
            }
            if (prev.bullish && !last.bullish) {
                return Triple("Engolfo de baixa possível", 20, "Mudança de cor + força vendedora")
            }
        }

        if (c.size >= 3) {
            val a = c[c.lastIndex - 2]; val b = c[c.lastIndex - 1]; val d = c.last()
            if (a.bullish && b.bullish && d.bullish && a.strength > .2f && b.strength > .2f && d.strength > .2f) {
                return Triple("Sequência de alta", 16, "3 candles com pressão compradora")
            }
            if (!a.bullish && !b.bullish && !d.bullish && a.strength > .2f && b.strength > .2f && d.strength > .2f) {
                return Triple("Sequência de baixa", 16, "3 candles com pressão vendedora")
            }
        }

        return when (trend) {
            "ALTA", "ALTA LEVE" -> Triple("Movimento de alta", 8, "Predomínio de pixels/candles verdes")
            "BAIXA", "BAIXA LEVE" -> Triple("Movimento de baixa", 8, "Predomínio de pixels/candles vermelhos")
            else -> Triple("Consolidação / lateral", 6, "Forças verde e vermelha próximas")
        }
    }

    private fun updatePanel(pattern: String, trend: String, confidence: String) {
        latestResult = pattern; latestTrend = trend; latestConfidence = confidence
        val interval = intervalMs()
        handler.post {
            panel?.findViewWithTag<TextView>("pattern")?.text = "Padrão: $latestResult"
            panel?.findViewWithTag<TextView>("trend")?.text = "Tendência: $latestTrend"
            panel?.findViewWithTag<TextView>("confidence")?.text = "Confiança heurística: $latestConfidence"
            panel?.findViewWithTag<TextView>("details")?.text = latestDetails
            panel?.findViewWithTag<TextView>("candles")?.text = "Candles detectados: $latestCandleCount"
            panel?.findViewWithTag<TextView>("time")?.text = "Atualização: ${formatInterval(interval)}"
        }
    }

    private fun formatInterval(ms: Long): String = when {
        ms < 1000 -> "${ms} ms"
        ms % 1000L == 0L -> "${ms / 1000L}s"
        else -> "${ms / 1000f}s"
    }

    private fun showPanel() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(Color.rgb(10, 13, 24), Color.rgb(124, 92, 255), dp(24))
        }
        val title = tv("LZGraph", 21f, Color.WHITE, true)
        val subtitle = tv("PAINEL AO VIVO • RECONHECIMENTO", 10f, Color.rgb(37,208,165), false)
        val status = tv("●  Captura autorizada", 13f, Color.rgb(37,208,165), true)
        val pattern = tv("Padrão: $latestResult", 14f, Color.WHITE, false).also { it.tag = "pattern" }
        val trend = tv("Tendência: $latestTrend", 14f, Color.WHITE, false).also { it.tag = "trend" }
        val confidence = tv("Confiança heurística: $latestConfidence", 14f, Color.WHITE, false).also { it.tag = "confidence" }
        val details = tv(latestDetails, 11f, Color.rgb(174,180,195), false).also { it.tag = "details" }
        val candles = tv("Candles detectados: $latestCandleCount", 11f, Color.rgb(174,180,195), false).also { it.tag = "candles" }
        val time = tv("Atualização: ${formatInterval(intervalMs())}", 10f, Color.rgb(139,147,167), false).also { it.tag = "time" }
        val close = tv("FECHAR PAINEL", 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(28,32,48), Color.rgb(70,75,95), dp(16))
            setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { stopSelf() }
        }
        root.addView(title)
        root.addView(subtitle, margins(0,4,0,12))
        root.addView(status, margins(0,0,0,9))
        root.addView(pattern, margins(0,0,0,6))
        root.addView(trend, margins(0,0,0,6))
        root.addView(confidence, margins(0,0,0,6))
        root.addView(details, margins(0,0,0,5))
        root.addView(candles, margins(0,0,0,2))
        root.addView(time)
        root.addView(close, margins(0,15,0,0))

        val p = WindowManager.LayoutParams(
            dp(325), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(70) }
        title.setOnTouchListener(DragListener(p))
        wm.addView(root, p)
        panel = root
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke)
    }

    private fun margins(l: Int,t: Int,r: Int,b: Int) = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(l),dp(t),dp(r),dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class DragListener(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        var dx=0; var dy=0; var sx=0; var sy=0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { dx=e.rawX.toInt(); dy=e.rawY.toInt(); sx=params.x; sy=params.y; return true }
                MotionEvent.ACTION_MOVE -> { params.x=sx+e.rawX.toInt()-dx; params.y=sy+e.rawY.toInt()-dy; panel?.let { wm.updateViewLayout(it, params) }; return true }
            }
            return false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val c = NotificationChannel("lzgraph_panel", "LZGraph", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(c)
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release(); virtualDisplay=null
        imageReader?.close(); imageReader=null
        projection?.stop(); projection=null
        handler.removeCallbacksAndMessages(null)
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel=null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
