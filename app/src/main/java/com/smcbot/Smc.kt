package com.smcbot

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

data class Swing(
    val index: Int,
    val price: Double,
    val isHigh: Boolean,
    var label: String = ""
)

data class Zone(val low: Double, val high: Double)

data class Signal(
    val side: String,
    val entry: Double,
    val sl: Double,
    val tp: Double,
    val reason: String
)

data class Analysis(
    val trend: String,
    val structure: String,
    val swings: List<Swing>,
    val zone: Zone?,
    val signal: Signal,
    val lastPrice: Double
)

private data class Brk(
    val bullish: Boolean,
    val breakIdx: Int,
    val swingIdx: Int,
    val level: Double,
    val choch: Boolean
)

/** Sumber data: Yahoo Finance (tanpa API key). Harga bisa sedikit berbeda dari broker MT5. */
object MarketData {
    private val yahoo = mapOf(
        "EURUSD" to "EURUSD=X",
        "GBPUSD" to "GBPUSD=X",
        "USDJPY" to "USDJPY=X",
        "AUDUSD" to "AUDUSD=X",
        "XAUUSD" to "GC=F"
    )
    private val tfMap = mapOf(
        "M5" to Pair("5m", "5d"),
        "M15" to Pair("15m", "5d"),
        "M30" to Pair("30m", "10d"),
        "H1" to Pair("60m", "1mo")
    )

    fun symbols(): List<String> = yahoo.keys.toList()
    fun timeframes(): List<String> = tfMap.keys.toList()

    fun fetch(symbol: String, tf: String): List<Candle> {
        val ysym = yahoo[symbol] ?: throw IllegalArgumentException("Simbol tidak didukung")
        val (iv, rg) = tfMap[tf] ?: throw IllegalArgumentException("Timeframe tidak didukung")
        val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$ysym?interval=$iv&range=$rg")
        val con = url.openConnection() as HttpURLConnection
        con.connectTimeout = 10000
        con.readTimeout = 10000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
        try {
            if (con.responseCode != 200) throw RuntimeException("HTTP ${con.responseCode}")
            val body = con.inputStream.bufferedReader().use { it.readText() }
            val res = JSONObject(body).getJSONObject("chart")
                .getJSONArray("result").getJSONObject(0)
            val ts = res.getJSONArray("timestamp")
            val q = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
            val o = q.getJSONArray("open")
            val h = q.getJSONArray("high")
            val l = q.getJSONArray("low")
            val c = q.getJSONArray("close")
            val out = ArrayList<Candle>()
            for (i in 0 until ts.length()) {
                if (o.isNull(i) || h.isNull(i) || l.isNull(i) || c.isNull(i)) continue
                out.add(
                    Candle(ts.getLong(i), o.getDouble(i), h.getDouble(i), l.getDouble(i), c.getDouble(i))
                )
            }
            if (out.size < 30) throw RuntimeException("Data candle kurang")
            return out.takeLast(300)
        } finally {
            con.disconnect()
        }
    }
}

object Smc {
    const val SL_PIPS = 30.0
    const val TP_PIPS = 60.0
    private const val N = 3          // jumlah candle kiri/kanan untuk swing (fractal)
    private const val MAX_AGE = 60   // BOS/CHoCH lebih tua dari ini dianggap basi

    /** 1 pip: forex 0.0001, JPY 0.01, XAUUSD 0.1 (sesuaikan jika broker Anda beda). */
    fun pipSize(sym: String): Double = when {
        sym.endsWith("JPY") -> 0.01
        sym.startsWith("XAU") -> 0.1
        else -> 0.0001
    }

    fun digits(sym: String): Int = when {
        sym.endsWith("JPY") -> 3
        sym.startsWith("XAU") -> 2
        else -> 5
    }

    fun fmt(v: Double, sym: String): String =
        String.format(Locale.US, "%." + digits(sym) + "f", v)

    /** Deteksi swing high/low lalu beri label HH, LH, HL, LL. */
    fun findSwings(c: List<Candle>): List<Swing> {
        val out = ArrayList<Swing>()
        for (i in N until c.size - N) {
            var isH = true
            var isL = true
            for (k in 1..N) {
                if (c[i].high <= c[i - k].high || c[i].high <= c[i + k].high) isH = false
                if (c[i].low >= c[i - k].low || c[i].low >= c[i + k].low) isL = false
            }
            if (isH) out.add(Swing(i, c[i].high, true))
            if (isL) out.add(Swing(i, c[i].low, false))
        }
        var prevH: Swing? = null
        var prevL: Swing? = null
        for (s in out) {
            if (s.isHigh) {
                val p = prevH
                s.label = if (p == null) "H" else if (s.price > p.price) "HH" else "LH"
                prevH = s
            } else {
                val p = prevL
                s.label = if (p == null) "L" else if (s.price > p.price) "HL" else "LL"
                prevL = s
            }
        }
        return out
    }

    private fun wait(reason: String) = Signal("WAIT", 0.0, 0.0, 0.0, reason)

    fun analyze(c: List<Candle>, sym: String): Analysis {
        val pip = pipSize(sym)
        val swings = findSwings(c)
        val lastIdx = c.size - 1
        val last = c[lastIdx]

        // Mesin state struktur pasar: cari BOS / CHoCH berdasarkan CLOSE candle
        var trend = 0
        var curH: Swing? = null
        var curL: Swing? = null
        var ev: Brk? = null
        for (i in c.indices) {
            for (s in swings) {
                if (s.index == i - N) {
                    if (s.isHigh) curH = s else curL = s
                }
            }
            val h = curH
            val l = curL
            if (h != null && c[i].close > h.price) {
                ev = Brk(true, i, h.index, h.price, trend == -1)
                trend = 1
                curH = null
            } else if (l != null && c[i].close < l.price) {
                ev = Brk(false, i, l.index, l.price, trend == 1)
                trend = -1
                curL = null
            }
        }
        val trendTxt = when (trend) {
            1 -> "BULLISH (HH/HL)"
            -1 -> "BEARISH (LH/LL)"
            else -> "RANGE"
        }

        val e = ev
        if (e == null || lastIdx - e.breakIdx > MAX_AGE) {
            return Analysis(
                trendTxt, "Belum ada BOS/CHoCH terbaru", swings, null,
                wait("Menunggu struktur baru terbentuk"), last.close
            )
        }

        val kind = if (e.choch) "CHoCH" else "BOS"
        val age = lastIdx - e.breakIdx
        val dir = if (e.bullish) "bullish" else "bearish"
        val struct = "$kind $dir @ ${fmt(e.level, sym)} ($age candle lalu)"

        if (e.bullish) {
            // Titik asal impuls = low terendah antara swing high yang ditembus dan candle breakout
            var lowIdx = e.swingIdx
            for (i in e.swingIdx..e.breakIdx) if (c[i].low < c[lowIdx].low) lowIdx = i
            // Bullish Order Block = candle bearish terakhir sebelum impuls naik
            var ob = lowIdx
            for (i in lowIdx downTo max(0, lowIdx - 5)) {
                if (c[i].close < c[i].open) {
                    ob = i
                    break
                }
            }
            val zone = Zone(c[ob].low, c[ob].high)
            var failed = false
            for (i in e.breakIdx + 1..lastIdx) if (c[i].close < zone.low) failed = true
            var legHigh = c[e.breakIdx].high
            for (i in e.breakIdx..lastIdx) legHigh = max(legHigh, c[i].high)
            val eq = (legHigh + c[lowIdx].low) / 2          // equilibrium 50%
            val mid = (zone.low + zone.high) / 2
            val touched = last.low <= zone.high && last.close >= zone.low
            val zoneTxt = fmt(zone.low, sym) + " - " + fmt(zone.high, sym)
            val sig = when {
                failed -> wait("OB gagal: harga close di bawah zona")
                mid > eq -> wait("OB di area premium, BUY hanya di discount")
                touched -> Signal(
                    "BUY", last.close,
                    last.close - SL_PIPS * pip, last.close + TP_PIPS * pip,
                    "Retrace ke bullish OB (discount) setelah $kind"
                )
                else -> wait("Menunggu retrace ke OB $zoneTxt")
            }
            return Analysis(trendTxt, struct, swings, zone, sig, last.close)
        } else {
            var hiIdx = e.swingIdx
            for (i in e.swingIdx..e.breakIdx) if (c[i].high > c[hiIdx].high) hiIdx = i
            // Bearish Order Block = candle bullish terakhir sebelum impuls turun
            var ob = hiIdx
            for (i in hiIdx downTo max(0, hiIdx - 5)) {
                if (c[i].close > c[i].open) {
                    ob = i
                    break
                }
            }
            val zone = Zone(c[ob].low, c[ob].high)
            var failed = false
            for (i in e.breakIdx + 1..lastIdx) if (c[i].close > zone.high) failed = true
            var legLow = c[e.breakIdx].low
            for (i in e.breakIdx..lastIdx) legLow = min(legLow, c[i].low)
            val eq = (legLow + c[hiIdx].high) / 2
            val mid = (zone.low + zone.high) / 2
            val touched = last.high >= zone.low && last.close <= zone.high
            val zoneTxt = fmt(zone.low, sym) + " - " + fmt(zone.high, sym)
            val sig = when {
                failed -> wait("OB gagal: harga close di atas zona")
                mid < eq -> wait("OB di area discount, SELL hanya di premium")
                touched -> Signal(
                    "SELL", last.close,
                    last.close + SL_PIPS * pip, last.close - TP_PIPS * pip,
                    "Retrace ke bearish OB (premium) setelah $kind"
                )
                else -> wait("Menunggu retrace ke OB $zoneTxt")
            }
            return Analysis(trendTxt, struct, swings, zone, sig, last.close)
        }
    }
}
