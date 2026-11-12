package dev.couchtv.ontv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Ask on the TV itself, with the remote: speak (the TV's speech recognizer, where it has one) or type with the
 * remote's keyboard, read the answer, and press a suggestion to start that show. The same Ask as the phone's,
 * the same AI service the setup page picked. Opened from the home's Ask quick button, and nothing here runs
 * until someone opens it.
 */
class AskActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var options: LinearLayout
    private var busy = false

    private val amber = Color.parseColor("#FFB52E")
    private val ink = Color.parseColor("#F2EAD8")
    private val dim = Color.parseColor("#9C968A")
    private val cardBg = Color.parseColor("#17171C")
    private val line = Color.parseColor("#2C2C33")
    private lateinit var inputCard: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(fill: Int, stroke: Int, radius: Int = 22, width: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); setStroke(dp(width), stroke)
    }

    /** A focusable pill or card: an amber ring and a little growth when the remote lands on it. */
    private fun focusable(v: View, fill: Int, radius: Int = 22) {
        v.isFocusable = true; v.isClickable = true
        v.background = card(fill, line, radius)
        v.setOnFocusChangeListener { x, f ->
            x.background = card(fill, if (f) amber else line, radius, if (f) 3 else 1)
            x.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(120).start()
        }
    }

    private fun pillButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#1A1405") else ink)
        setPadding(dp(30), dp(16), dp(30), dp(16))
        focusable(this, if (primary) amber else cardBg, 40)
        if (primary) setOnFocusChangeListener { x, f ->
            x.background = card(amber, if (f) Color.WHITE else amber, 40, if (f) 3 else 1)
            x.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(120).start()
        }
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        App.startService(this)   // the phone remote's server runs again whenever this opens
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(80), dp(44), dp(80), dp(44)) }
        col.addView(TextView(this).apply { text = "Ask"; textSize = 44f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
        col.addView(TextView(this).apply {
            text = "Say or type what you want. Press OK on a suggestion to start it."
            textSize = 20f; setTextColor(dim)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputCard = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(26), dp(8), dp(26), dp(8)); background = card(cardBg, line, 30) }
        input = EditText(this).apply {
            hint = "Something funny, under an hour…"; textSize = 26f; setTextColor(ink); setHintTextColor(Color.parseColor("#6E695F"))
            background = null; imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> send(); true }
            setOnFocusChangeListener { _, f -> inputCard.background = card(cardBg, if (f) amber else line, 30, if (f) 3 else 1) }
        }
        inputCard.addView(input, LinearLayout.LayoutParams(-1, dp(64)))
        row.addView(inputCard, LinearLayout.LayoutParams(0, -2, 1f))
        if (hasRecognizer()) row.addView(pillButton("🎤  Speak", true) { listen() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(18) })
        row.addView(pillButton("Send", false) { send() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })

        status = TextView(this).apply { textSize = 20f; setTextColor(amber) }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        reply = TextView(this).apply {
            textSize = 26f; setTextColor(ink); setLineSpacing(0f, 1.15f); setPadding(dp(28), dp(22), dp(28), dp(22)); visibility = View.GONE
            background = card(cardBg, line, 24)
        }
        col.addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#14161F"), Color.parseColor("#09090B")))
        setContentView(ScrollView(this).apply { background = bg; isFillViewport = true; addView(col) })
        if (!Ask.enabled) {
            status.setTextColor(dim)
            status.text = "Ask is off. On a phone open http://${App.lanAddress() ?: "<tv address>"}:5400/setup and pick an AI service."
        } else status.text = if (hasRecognizer()) "Press the mic button on the remote, or type." else "Type with the remote's keyboard."
        input.requestFocus()
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleIntent(i) }

    /** The remote's microphone button opens this with "listen": start listening at once. */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("listen", false) == true && hasRecognizer() && Ask.enabled) main.postDelayed({ listen() }, 350)
    }

    private fun hasRecognizer() = packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()

    private fun listen() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1), 7)
        } catch (e: ActivityNotFoundException) { status.text = "This TV has no voice input. Type instead." }
    }

    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != 7 || result != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        if (heard.isNotEmpty()) { input.setText(heard); send() }
    }

    // A voice or assistant button on the remote starts voice input, with Ask open.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) && hasRecognizer()) { listen(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!Ask.enabled) { status.text = "Ask is off. Switch it on at http://${App.lanAddress() ?: "<tv address>"}:5400/setup"; return }
        busy = true; status.setTextColor(amber); status.text = "Thinking…"; reply.visibility = View.GONE; options.removeAllViews()
        Thread {
            val out: JSONObject? = try { Ask.ask(text, Now.who) } catch (e: Exception) { show(null, e.message ?: "That did not work."); null }
            if (out != null) show(out, null)
        }.start()
    }

    private fun show(out: JSONObject?, error: String?) = main.post {
        busy = false; status.text = ""
        val answer = error ?: out?.optString("reply").orEmpty()
        reply.text = answer; reply.visibility = if (answer.isEmpty()) View.GONE else View.VISIBLE
        val opts = out?.optJSONArray("options") ?: return@post
        for (i in 0 until opts.length()) {
            val o = opts.optJSONObject(i) ?: continue
            val play = o.optJSONObject("play") ?: continue
            val parts = o.optString("label").split(" · ", limit = 2)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)) }
            row.addView(TextView(this).apply { text = "▶"; textSize = 24f; setTextColor(amber) }, LinearLayout.LayoutParams(dp(40), -2))
            val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            names.addView(TextView(this).apply { text = parts[0]; textSize = 26f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD })
            if (parts.size > 1) names.addView(TextView(this).apply { text = parts[1]; textSize = 18f; setTextColor(amber) })
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            focusable(row, cardBg, 24)
            row.setOnClickListener { start(play) }
            options.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (i == 0) row.requestFocus()
        }
    }

    private fun start(play: JSONObject) {
        status.setTextColor(amber); status.text = "Starting…"
        Thread {
            try {
                Finder.play(play.getString("title"), null, null, play.optString("service").ifEmpty { null }, Now.who)
                main.post { finish() }
            } catch (e: Exception) { main.post { status.text = e.message ?: "Could not start that." } }
        }.start()
    }
}
