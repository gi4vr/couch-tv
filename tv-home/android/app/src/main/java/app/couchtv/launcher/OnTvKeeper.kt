package app.couchtv.launcher

import android.content.Context
import android.content.Intent
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/** Keeps the on-TV app's phone remote server (port 5400) running: see FireWatch.keepOnTv. */
object OnTvKeeper {
    private const val PKG = "dev.couchtv.ontv"
    private const val PORT = 5400

    /** Off with the setting "ontv_keepalive" (default on). Does nothing when the on-TV app is not installed. */
    fun check(c: Context) {
        if (!c.getSharedPreferences("couchtv", Context.MODE_PRIVATE).getBoolean("ontv_keepalive", true)) return
        if (runCatching { c.packageManager.getPackageInfo(PKG, 0) }.isFailure) return
        if (answers()) return
        Log.i("couchtv.keepalive", "the on-TV server is not answering: asking it to start")
        runCatching {
            c.sendBroadcast(Intent("$PKG.KEEPALIVE").setClassName(PKG, "$PKG.BootReceiver"))
        }
    }

    private fun answers(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 800); true }
    }.getOrDefault(false)
}
