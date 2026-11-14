package com.smcbot

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val MT5_PKG = "com.metaquotes.metatrader5"
        const val CH_ID = "smcbot"
    }

    private lateinit var wm: WindowManager
    private lateinit var box: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var tvTitle: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvLevels: TextView
    private lateinit var tvStruct: TextView
    private lateinit var tvSwings: TextView
    private lateinit var tvReason: TextView

    private val main = Handler(Looper.getMainLooper())
    private var exec: ScheduledExecutorService? = null
    private var fgPkg: String? = null
    private var lastEventQuery = 0L
    private var collapsed = false
    private var shown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        buildPanel()
        main.post(ticker)
        exec = Executors.newSingleThreadScheduledExecutor()
        exec?.scheduleWithFixedDelay({ runAnalysis() }, 0, 30, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        exec?.shutdownNow()
        setShown(false)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "SMC Bot", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CH_ID)
            .setContentTitle("SMC Bot aktif")
            .setContentText("Panel muncul saat MT5 dibuka")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView {
        val t = TextView(this)
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    private fun buildPanel() {
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(8), dp(12), dp(10))
        val bg = GradientDrawable()
        bg.setColor(Color.argb(225, 15, 20, 30))
        bg.cornerRadius = dp(14).toFloat()
        box.background = bg

        tvTitle = label(12f, true, Color.parseColor("#9AA7B8"))
        tvSignal = label(26f, true)
        tvLevels = label(13f)
        tvStruct = label(11f, false, Color.parseColor("#C9D3E0"))
        tvSwings = label(12f, true, Color.parseColor("#5DADE2"))
        tvReason = label(10f, false, Color.parseColor("#9AA7B8"))
        tvTitle.text = "SMC BOT"
        tvSignal.text = "..."
        tvReason.text = "Mengambil data..."

        box.addView(tvTitle)
        box.addView(tvSignal)
        box.addView(tvLevels)
        box.addView(tvStruct)
        box.addView(tvSwings)
        box.addView(tvReason)

        lp = WindowManager.LayoutParams(
            dp(240),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8)
        lp.y = dp(120)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        box.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleCollapse()
            }
            true
        }
    }

    /** Ketuk panel untuk mengecilkan / membesarkan. Geser untuk memindahkan. */
    private fun toggleCollapse() {
        collapsed = !collapsed
        val v = if (collapsed) View.GONE else View.VISIBLE
        tvLevels.visibility = v
        tvStruct.visibility = v
        tvSwings.visibility = v
        tvReason.visibility = v
        try { wm.updateViewLayout(box, lp) } catch (_: Exception) {}
    }

    private fun setShown(show: Boolean) {
        if (show == shown) return
        try {
            if (show) wm.addView(box, lp) else wm.removeView(box)
            shown = show
        } catch (_: Exception) {
        }
    }

    // ---------- Deteksi MT5 di layar depan ----------

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun updateForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastEventQuery == 0L) now - 3_600_000L else lastEventQuery
        val events = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fgPkg = e.packageName
        }
        lastEventQuery = now
    }

    private val ticker = object : Runnable {
        override fun run() {
            val show = if (hasUsageAccess()) {
                updateForeground()
                fgPkg == MT5_PKG
            } else {
                true // tanpa usage access: panel selalu tampil
            }
            setShown(show)
            main.postDelayed(this, 2000)
        }
    }

    // ---------- Analisis ----------

    private fun runAnalysis() {
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val sym = p.getString("symbol", "EURUSD") ?: "EURUSD"
        val tf = p.getString("tf", "M15") ?: "M15"
        try {
            val candles = MarketData.fetch(sym, tf)
            val a = Smc.analyze(candles, sym)
            main.post { render(sym, tf, a) }
        } catch (e: Exception) {
            main.post {
                tvTitle.text = "SMC BOT  $sym $tf"
                tvSignal.text = "ERROR"
                tvSignal.setTextColor(Color.parseColor("#FF5252"))
                tvReason.text = e.message ?: "Gagal mengambil data"
            }
        }
    }

    private fun render(sym: String, tf: String, a: Analysis) {
        val s = a.signal
        tvTitle.text = "SMC BOT  $sym $tf  " + Smc.fmt(a.lastPrice, sym)
        tvSignal.text = s.side
        tvSignal.setTextColor(
            when (s.side) {
                "BUY" -> Color.parseColor("#2ECC71")
                "SELL" -> Color.parseColor("#FF5252")
                else -> Color.parseColor("#F1C40F")
            }
        )
        val z = a.zone
        tvLevels.text = if (s.side == "WAIT") {
            if (z != null) "Zona OB: " + Smc.fmt(z.low, sym) + " - " + Smc.fmt(z.high, sym) else "-"
        } else {
            "Entry " + Smc.fmt(s.entry, sym) +
                "\nSL " + Smc.fmt(s.sl, sym) + " (" + Smc.SL_PIPS.toInt() + " pip)" +
                "\nTP " + Smc.fmt(s.tp, sym) + " (" + Smc.TP_PIPS.toInt() + " pip)"
        }
        tvStruct.text = "Trend " + a.trend + "\n" + a.structure
        tvSwings.text = a.swings.takeLast(6).joinToString(" ") { it.label }
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvReason.text = s.reason + "\nUpdate " + t
    }
}
