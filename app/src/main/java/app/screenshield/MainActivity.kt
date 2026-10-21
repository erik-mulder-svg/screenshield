package app.screenshield

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings + status screen, built in code (no extra UI libraries). */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var overlayBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var notifBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }
        val scroll = ScrollView(this).apply { addView(root); fitsSystemWindows = true }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        // ---- Setup
        header(root, "Setup")
        overlayBtn = button(root, "") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryBtn = button(root, "") { askUnrestrictedBattery() }
        notifBtn = button(root, "Allow notification (shows when protection is on)") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            button(root, "Add quick-settings tile") { addTile() }
        }
        note(root, "Tip: you can also add the tile yourself: pull down quick settings › ✎ (edit) › drag \"ScreenShield\" in.")

        // ---- Look
        header(root, "Censor style")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val black = RadioButton(this).apply { text = "Black boxes"; id = View.generateViewId() }
        val pixel = RadioButton(this).apply { text = "Pixelated"; id = View.generateViewId() }
        group.addView(black); group.addView(pixel)
        group.check(if (prefs.pixelate) pixel.id else black.id)
        group.setOnCheckedChangeListener { _, checked ->
            prefs.pixelate = checked == pixel.id
        }
        root.addView(group)
        note(root, "Pixelated shows a coarse mosaic of the covered area (it updates each time the detector sees it). Black is the most private.")

        // ---- Performance
        header(root, "Detection")
        slider(root, "Checks per second", 1, 15, prefs.checksPerSecond, { "$it / s" }) {
            prefs.checksPerSecond = it
        }
        note(root, "Higher = boxes react faster, but uses more battery. 4–6 is a good balance.")
        slider(root, "Hold boxes for", 2, 30, prefs.holdMs / 100, { "%.1f s".format(it / 10f) }) {
            prefs.holdMs = it * 100
        }
        slider(root, "Enlarge boxes by", 0, 60, prefs.enlargePct, { "$it %" }) {
            prefs.enlargePct = it
        }
        slider(root, "Sensitivity threshold", 15, 80, prefs.thresholdPct, { "$it %" }) {
            prefs.thresholdPct = it
        }
        note(root, "Lower threshold = catches more, but also covers more harmless things.")

        val resIndex = Prefs.RESOLUTIONS.indexOf(prefs.resolution).coerceAtLeast(0)
        slider(root, "Detection detail", 0, Prefs.RESOLUTIONS.size - 1, resIndex,
            { "${Prefs.RESOLUTIONS[it]} px" }) {
            prefs.resolution = Prefs.RESOLUTIONS[it]
            CaptureService.instance?.applySettings()
        }
        note(root, "More detail finds small images in feeds better, but each check takes longer.")

        val male = Switch(this).apply {
            text = "Also cover bare male chests"
            isChecked = prefs.coverMaleChest
            setOnCheckedChangeListener { _, v -> prefs.coverMaleChest = v }
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(male)

        // ---- Privacy
        header(root, "Privacy")
        note(root, "This app has no internet permission. Screen frames exist only in memory for a moment and are never saved. " +
            "Apps that block screenshots (some banking/streaming apps) appear black to the detector and can't be filtered.")
        note(root, "Covered areas can't be tapped through (Android blocks touches under opaque overlays). Scroll from another spot.")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    private fun refresh() {
        val on = CaptureService.running
        val sb = StringBuilder()
        sb.append(if (on) "● Protection is ON" else "○ Protection is off")
        if (on) sb.append("\nLast check took ${CaptureService.lastInferMs} ms · ${CaptureService.lastBoxCount} box(es)")
        CaptureService.lastError?.let { if (!on) sb.append("\n").append(it) }
        status.text = sb
        toggle.text = if (on) "Turn off" else "Turn on"

        val overlayOk = Settings.canDrawOverlays(this)
        overlayBtn.text = if (overlayOk) "✓ Display over other apps: allowed" else "Allow \"Display over other apps\""
        overlayBtn.isEnabled = !overlayOk

        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBtn.text = if (batteryOk) "✓ Battery: Unrestricted" else "Set battery usage to Unrestricted"

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifBtn.visibility = if (notifOk) View.GONE else View.VISIBLE
    }

    private fun onToggle() {
        if (CaptureService.running) {
            CaptureService.instance?.shutdown()
            refresh()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "First allow \"Display over other apps\"", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        if (!batteryOk && !prefs.batteryPromptShown) {
            prefs.batteryPromptShown = true
            AlertDialog.Builder(this)
                .setTitle("Keep ScreenShield running")
                .setMessage("Samsung may stop background apps to save battery, which would silently switch protection off. " +
                    "Set this app's battery usage to \"Unrestricted\"?")
                .setPositiveButton("Set Unrestricted") { _, _ -> askUnrestrictedBattery() }
                .setNegativeButton("Not now") { _, _ -> startProtection() }
                .show()
            return
        }
        startProtection()
    }

    private fun startProtection() {
        startActivity(Intent(this, ProjectionRequestActivity::class.java))
    }

    private fun askUnrestrictedBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            openAppDetails()
            return
        }
        try {
            // On Android 12+ / One UI this is the same as App info › Battery › Unrestricted.
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        Toast.makeText(this, "Battery › Unrestricted", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(this, ToggleTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_shield),
            mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile is already there"
                else -> "Tile not added – add it via quick settings › edit"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- tiny UI helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(4))
        })
    }

    private fun note(root: LinearLayout, t: String) {
        root.addView(TextView(this).apply {
            text = t
            textSize = 13f
            alpha = 0.75f
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun button(root: LinearLayout, t: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
        root.addView(b)
        return b
    }

    private fun slider(
        root: LinearLayout, label: String, min: Int, max: Int, value: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit,
    ) {
        val tv = TextView(this).apply { textSize = 15f; setPadding(0, dp(10), 0, 0) }
        tv.text = "$label: ${fmt(value)}"
        val sb = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    tv.text = "$label: ${fmt(p)}"
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        root.addView(tv)
        root.addView(sb)
    }
}
