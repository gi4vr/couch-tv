package app.couchtv.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's gear (Settings) button. On some TVs (seen on an ONVO / MediaTek Google TV) that
 * button never reaches any app: Android's global-key table hands it to the maker's hot-key agent or
 * to the Google TV home, and with the Google TV home switched off nothing happens. An
 * accessibility service sees keys before that hand-off, so this one catches only the gear button
 * and opens couch-tv's own gear menu (Home settings or TV settings). Every other key is passed on
 * untouched, and it reads no screen content.
 *
 * Off until the person at the TV switches it on: Settings, System (or Device Preferences),
 * Accessibility, "Home gear button". Android switches it off again whenever the app is reinstalled.
 * undo.sh takes it away with couch-tv's other accessibility services.
 */
class KeyWatch : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The remote's microphone button (KEYCODE_ASSIST) opens Ask on the TV, where the on-TV app is installed.
        // Without the Google Assistant (switched off on many debloated TVs) that button otherwise does nothing.
        if (isAssist(event) && micAsk(this)) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                runCatching {
                    startActivity(Intent().setClassName(ON_TV, "$ON_TV.AskActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("listen", true))
                }
            }
            return true
        }
        if (!isGear(event)) return false   // not ours: Android gets it as usual
        Log.i("couchtv-keywatch", "gear keyCode=${event.keyCode} scan=${event.scanCode} action=${event.action}")
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("gear", true))
            }
        }
        return true   // both the press and the release, so the old (dead) route never sees it
    }

    companion object {
        const val ON_TV = "dev.couchtv.ontv"

        /** Microphone button: Android's assist keys, and the raw scan code 217 seen on a TCL remote. */
        fun isAssist(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_ASSIST || e.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || e.scanCode == 217

        /** On when the on-TV app is there and the setting "mic_ask" (default on) is not switched off. */
        fun micAsk(c: android.content.Context): Boolean =
            c.getSharedPreferences("couchtv", android.content.Context.MODE_PRIVATE).getBoolean("mic_ask", true) &&
                runCatching { c.packageManager.getPackageInfo(ON_TV, 0); true }.getOrDefault(false)

        /** Puts this service on the accessibility list (needs the secure-settings grant from apply.sh). */
        fun ensure(c: android.content.Context) {
            if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            if (!micAsk(c)) return
            runCatching {
                val cr = c.contentResolver
                val me = "${c.packageName}/${KeyWatch::class.java.name}"
                val list = (android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services") ?: "").split(':').filter { it.isNotEmpty() && it != "null" }
                if (list.none { it == me || it == "${c.packageName}/.KeyWatch" }) {
                    android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", (list + me).joinToString(":"))
                    Log.i("couchtv-keywatch", "switched the mic and gear key watch on")
                }
                android.provider.Settings.Secure.putInt(cr, "accessibility_enabled", 1)
            }
        }

        /** The ONVO remote's gear: KEYCODE_BUTTON_14 (201), raw Linux KEY_TAPE (0x180 = 384), which
         *  the global-key table hands to MediaTek's hot-key agent. 176 is Android's own Settings key,
         *  kept for remotes that send it. */
        const val GEAR_SCAN = 384

        fun isGear(e: KeyEvent): Boolean = e.keyCode == KeyEvent.KEYCODE_BUTTON_14 ||
            e.scanCode == GEAR_SCAN || e.keyCode == KeyEvent.KEYCODE_SETTINGS
    }
}
