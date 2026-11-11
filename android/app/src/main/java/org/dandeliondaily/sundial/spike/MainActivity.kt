package org.dandeliondaily.sundial.spike

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Test console for the step 0 spike. Each button runs one of the checks in Sundial Plan.md. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Sundial · step 0 spike"
            textSize = 22f
        })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)

        fun btn(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { onClick(); refresh() }
            })
        }

        btn("Allow notifications") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        btn("Show the Now card") {
            Spike.setCardOn(this, true)
            NowCard.post(this, alert = false, reason = "button")
        }
        btn("Rows: switch 5 ↔ 3") {
            Spike.toggleRows(this)
            if (Spike.cardOn(this)) NowCard.post(this, alert = false, reason = "rows changed")
        }
        btn("Style: switch custom rows ↔ plain fallback") {
            Spike.toggleStyle(this)
            if (Spike.cardOn(this)) NowCard.post(this, alert = false, reason = "style changed")
        }
        btn("Placement: switch silent section ↔ top (no sound)") {
            Spike.togglePlacement(this)
            if (Spike.cardOn(this)) NowCard.post(this, alert = false, reason = "placement changed")
        }
        btn("Next part of the day now (silent)") {
            val part = Spike.nextPart(this)
            Spike.setCardOn(this, true)
            NowCard.post(this, alert = false, reason = "switch to $part")
        }
        btn("Check now (alerts once if items are open)") {
            if (Spike.openItems(this).isEmpty()) {
                EventLog.add(this, "check: all clear, no alert")
            } else {
                Spike.setCardOn(this, true)
                NowCard.post(this, alert = true, reason = "check button")
            }
        }
        btn("In 1 min: silent switch (then lock the phone)") {
            Alarms.schedule(this, Alarms.SWITCH, 60_000)
        }
        btn("In 1 min: check (then lock the phone)") {
            Alarms.schedule(this, Alarms.CHECK, 60_000)
        }
        btn("Overnight test: start / stop (alarm every 30 min)") {
            val on = !Spike.chainOn(this)
            Spike.setChainOn(this, on)
            if (on) Alarms.schedule(this, Alarms.CHAIN, Alarms.CHAIN_MINUTES * 60_000)
            else Alarms.cancel(this, Alarms.CHAIN)
        }
        btn("Open app settings (set battery to Unrestricted)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        btn("Reset fake rows") {
            Spike.reset(this)
            if (Spike.cardOn(this)) NowCard.post(this, alert = false, reason = "reset")
        }
        btn("Clear log") { EventLog.clear(this) }

        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        col.addView(log)
        // Android 15+ draws apps edge to edge; keep the content clear of the bars.
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val ctx = this
        status.text = buildString {
            appendLine("Part: ${Spike.part(ctx)} · rows ${Spike.rowLimit(ctx)} · style ${Spike.style(ctx)} · placement ${Spike.placement(ctx)}")
            appendLine("Notifications allowed: ${nm.areNotificationsEnabled()}")
            appendLine("Exact alarms allowed: ${Alarms.canExact(ctx)}")
            appendLine("Battery unrestricted: ${pm.isIgnoringBatteryOptimizations(packageName)}")
            appendLine("Card on: ${Spike.cardOn(ctx)} · overnight test: ${Spike.chainOn(ctx)}")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        }
        log.text = EventLog.read(this)
    }
}
