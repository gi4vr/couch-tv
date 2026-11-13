package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Keeps the on-TV app's phone remote server (port 5400) running: see FireWatch.keepOnTv. */
object OnTvKeeper {
    private const val PKG = "dev.couchtv.ontv"
    private const val PORT = 5400

    /** Off with the setting "ontv_keepalive" (default on). Does nothing when the on-TV app is not installed. */
    fun check(c: Context) {
        if (!c.getSharedPreferences("couchtv", Context.MODE_PRIVATE).getBoolean("ontv_keepalive", true)) return
        if (runCatching { c.packageManager.getPackageInfo(PKG, 0) }.isFailure) return
        if (answers()) return
        // two tries a few seconds apart, so one slow answer does not restart a healthy server
        Thread.sleep(4000)
        if (answers()) return
        Log.i("couchtv.keepalive", "the on-TV server is not answering: asking it to start again")
        runCatching {
            c.sendBroadcast(Intent("$PKG.KEEPALIVE").setClassName(PKG, "$PKG.BootReceiver").putExtra("restart", true))
        }
    }

    /** A real request, not only a connection: a hung server still accepts connections and never answers. */
    private fun answers(): Boolean = runCatching {
        val u = URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection
        u.connectTimeout = 2500; u.readTimeout = 4000
        try { u.responseCode in 100..599 } finally { u.disconnect() }
    }.getOrDefault(false)
}
