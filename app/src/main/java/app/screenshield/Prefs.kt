package app.screenshield

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Stored only in the app's private storage. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** false = solid black boxes, true = pixelated (mosaic) boxes. */
    var pixelate: Boolean
        get() = sp.getBoolean("pixelate", false)
        set(v) = sp.edit().putBoolean("pixelate", v).apply()

    /** How many times per second the screen is checked (1..15). */
    var checksPerSecond: Int
        get() = sp.getInt("cps", 5)
        set(v) = sp.edit().putInt("cps", v.coerceIn(1, 15)).apply()

    /** How long a box stays after the last sighting, in ms. Hides detector lag/flicker. */
    var holdMs: Int
        get() = sp.getInt("hold", 900)
        set(v) = sp.edit().putInt("hold", v.coerceIn(200, 5000)).apply()

    /** Extra margin added on every side of a detected area, in percent of its size. */
    var enlargePct: Int
        get() = sp.getInt("enlarge", 20)
        set(v) = sp.edit().putInt("enlarge", v.coerceIn(0, 60)).apply()

    /** Minimum detector confidence in percent. Lower = covers more (incl. false positives). */
    var thresholdPct: Int
        get() = sp.getInt("threshold", 30)
        set(v) = sp.edit().putInt("threshold", v.coerceIn(15, 80)).apply()

    /** Short side of the frame given to the detector, in pixels. */
    var resolution: Int
        get() = sp.getInt("res", 384)
        set(v) = sp.edit().putInt("res", v).apply()

    var coverMaleChest: Boolean
        get() = sp.getBoolean("male_chest", false)
        set(v) = sp.edit().putBoolean("male_chest", v).apply()

    /** Draw a bright outline around detected bare feet (never covered, just marked). */
    var highlightFeet: Boolean
        get() = sp.getBoolean("highlight_feet", true)
        set(v) = sp.edit().putBoolean("highlight_feet", v).apply()

    /** Minimum confidence (percent) for a bare-foot highlight; judged on the foot score alone. */
    var feetThresholdPct: Int
        get() = sp.getInt("feet_threshold", 18)
        set(v) = sp.edit().putInt("feet_threshold", v.coerceIn(8, 60)).apply()

    /** Block everything below a woman's face until that area has been checked. */
    var preBlockFaces: Boolean
        get() = sp.getBoolean("preblock_faces", true)
        set(v) = sp.edit().putBoolean("preblock_faces", v).apply()

    /** true: "censor check" blocks let touches through (max 80% opaque). false: solid, blocks touches. */
    var guardTouchThrough: Boolean
        get() = sp.getBoolean("guard_touch_through", true)
        set(v) = sp.edit().putBoolean("guard_touch_through", v).apply()

    /** Check up to 12x per second while the screen is scrolling. */
    var boostWhileScrolling: Boolean
        get() = sp.getBoolean("boost_scroll", true)
        set(v) = sp.edit().putBoolean("boost_scroll", v).apply()

    var batteryPromptShown: Boolean
        get() = sp.getBoolean("battery_prompt", false)
        set(v) = sp.edit().putBoolean("battery_prompt", v).apply()

    companion object {
        val RESOLUTIONS = intArrayOf(320, 384, 480, 640)
    }
}
