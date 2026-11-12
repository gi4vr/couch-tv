package app.couchtv.launcher

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Keeps the Home button watch on. Some TVs (a TCL here) end apps in the background, and Android then leaves the
 * watch listed as on but not bound, so the Home key stays on the TV's own home until couch-tv is opened again.
 * A small job runs about every 15 minutes, survives a restart, and puts the watch back on the list and rebinds it
 * if it is not connected. It reads nothing and does nothing at all when the watch is already running.
 *
 * It can be switched off with the "watch_keepalive" setting (default on). A force-stop cancels every job, so after
 * one the watch stays off until couch-tv is opened once (nothing in Android can change that).
 */
class WatchKeeper : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        val c = applicationContext
        if (!enabled(c)) return false
        Log.i(TAG, "check: watch connected=${FireWatch.connected}")
        runCatching { FireWatch.ensure(c) }
        if (FireWatch.connected) return false
        // Not connected: take it off the list and put it back so Android binds it, then report done.
        FireWatch.rebindIfUnbound(c)
        return false
    }

    override fun onStopJob(p: JobParameters) = false

    companion object {
        private const val TAG = "couchtv.watchkeeper"
        private const val JOB_ID = 4711
        private const val PERIOD_MS = 15 * 60_000L

        fun enabled(c: Context) = c.getSharedPreferences("couchtv", Context.MODE_PRIVATE).getBoolean("watch_keepalive", true)

        fun setEnabled(c: Context, on: Boolean) {
            c.getSharedPreferences("couchtv", Context.MODE_PRIVATE).edit().putBoolean("watch_keepalive", on).apply()
            if (on) schedule(c) else cancel(c)
        }

        /** Safe to call as often as you like: an already scheduled job is kept. */
        fun schedule(c: Context) {
            if (!enabled(c)) return
            runCatching {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.allPendingJobs.any { it.id == JOB_ID }) return
                val info = JobInfo.Builder(JOB_ID, ComponentName(c, WatchKeeper::class.java))
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
                Log.i(TAG, "scheduled")
            }.onFailure { Log.w(TAG, "could not schedule: ${it.message}") }
        }

        fun cancel(c: Context) { runCatching { c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } }
    }
}
