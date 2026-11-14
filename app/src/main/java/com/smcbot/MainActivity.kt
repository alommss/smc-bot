package com.smcbot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private fun spinner(items: List<String>, key: String, def: String): Spinner {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(items.indexOf(p.getString(key, def)).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                p.edit().putString(key, items[pos]).apply()
            }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        return sp
    }

    private fun text(s: String, size: Float): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setPadding(0, 16, 0, 8)
        return t
    }

    private fun button(s: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = s
        b.setOnClickListener { onClick() }
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        root.addView(text("SMC Bot untuk MT5", 22f))
        root.addView(text("Pair", 14f))
        root.addView(spinner(MarketData.symbols(), "symbol", "EURUSD"))
        root.addView(text("Timeframe", 14f))
        root.addView(spinner(MarketData.timeframes(), "tf", "M15"))

        root.addView(button("1. Izinkan Overlay") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("2. Izinkan Usage Access (panel otomatis di MT5)") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(button("3. START BOT") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izinkan overlay dulu (tombol 1)", Toast.LENGTH_LONG).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                startForegroundService(Intent(this, OverlayService::class.java))
                val mt5 = packageManager.getLaunchIntentForPackage(OverlayService.MT5_PKG)
                if (mt5 != null) {
                    startActivity(mt5)
                } else {
                    Toast.makeText(this, "MT5 tidak ditemukan, buka manual", Toast.LENGTH_LONG).show()
                }
            }
        })
        root.addView(button("STOP BOT") {
            stopService(Intent(this, OverlayService::class.java))
        })

        root.addView(
            text(
                "Ketuk panel untuk mengecil/membesar, geser untuk memindahkan.\n\n" +
                    "Sinyal bersifat edukasi, bukan nasihat keuangan. Data dari Yahoo Finance " +
                    "bisa berbeda dari harga broker Anda. Trading berisiko kehilangan modal.",
                12f
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
