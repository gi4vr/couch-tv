package dev.couchtv.ontv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject

/**
 * How the Couch TV Home app (app.couchtv.launcher) asks the AI through this app, so the home can draw Ask in its own
 * style. Only an app signed with the same key may call it (signature permission), and it does what the phone's Ask
 * does: "ask" answers a request in words with suggestions, "play" starts one suggestion. Nothing is stored here.
 */
class AskProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val out = Bundle()
        try {
            when (method) {
                "status" -> out.putString("json", JSONObject().put("ready", Ask.enabled).toString())
                "ask" -> {
                    if (!Ask.enabled) throw ApiError(501, "Ask is off. Set it up on a phone at http://${App.lanAddress() ?: "<tv>"}:5400/setup")
                    out.putString("json", Ask.ask(arg.orEmpty().take(500), Now.who).toString())
                }
                "play" -> {
                    val p = JSONObject(arg ?: "{}")
                    Finder.play(p.getString("title"), null, null, p.optString("service").ifEmpty { null }, Now.who)
                    out.putString("json", JSONObject().put("ok", true).toString())
                }
                else -> out.putString("error", "unknown method")
            }
        } catch (e: Exception) { out.putString("error", e.message ?: "That did not work.") }
        return out
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
