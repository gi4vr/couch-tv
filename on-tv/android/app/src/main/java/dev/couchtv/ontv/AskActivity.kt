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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = px(14).toFloat() }
        fun btn(label: String) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 20f; setTextColor(Color.parseColor("#F2EAD8"))
            background = pill(Color.parseColor("#1B1B20")); setPadding(px(22), px(14), px(22), px(14)); isFocusable = true
            setOnFocusChangeListener { v, f -> v.background = pill(Color.parseColor(if (f) "#4A3A12" else "#1B1B20")) }
        }

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(64), px(40), px(64), px(40)) }
        col.addView(TextView(this).apply { text = "Ask"; textSize = 38f; setTextColor(Color.parseColor("#F2EAD8")); typeface = Typeface.DEFAULT_BOLD })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "What do you want to watch or do?"; textSize = 24f; setTextColor(Color.parseColor("#F2EAD8")); setHintTextColor(Color.parseColor("#8A8478"))
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (hasRecognizer()) row.addView(btn("Speak").apply { setOnClickListener { listen() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16) })
        row.addView(btn("Send").apply { setOnClickListener { send() } }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = px(16) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
        status = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#BDB6A7")) }
        col.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        reply = TextView(this).apply { textSize = 24f; setTextColor(Color.parseColor("#F2EAD8")) }
        col.addView(reply, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(options, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0B0B0D")); isFillViewport = true; addView(col) })
        if (!Ask.enabled) {
            status.text = "Ask is off. On a phone open http://${App.lanAddress() ?: "<tv address>"}:5400/setup to pick an AI service."
        } else if (!hasRecognizer()) status.text = "Type with the remote's keyboard (this TV has no voice input for apps)."
        else status.text = "Press Speak, or type."
        input.requestFocus()
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
        busy = true; status.text = "Thinking…"; reply.text = ""; options.removeAllViews()
        Thread {
            val out: JSONObject? = try { Ask.ask(text, Now.who) } catch (e: Exception) { show(null, e.message ?: "That did not work."); null }
            if (out != null) show(out, null)
        }.start()
    }

    private fun show(out: JSONObject?, error: String?) = main.post {
        busy = false; status.text = ""
        reply.text = error ?: out?.optString("reply").orEmpty()
        val opts = out?.optJSONArray("options") ?: return@post
        for (i in 0 until opts.length()) {
            val o = opts.optJSONObject(i) ?: continue
            val play = o.optJSONObject("play") ?: continue
            val b = Button(this).apply {
                text = "▶  ${o.optString("label")}"; isAllCaps = false; textSize = 22f; setTextColor(Color.parseColor("#F2EAD8")); isFocusable = true
                background = GradientDrawable().apply { setColor(Color.parseColor("#1B1B20")); cornerRadius = 40f }
                setPadding(48, 28, 48, 28); gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setColor(Color.parseColor(if (f) "#4A3A12" else "#1B1B20")) }
                setOnClickListener { start(play) }
            }
            options.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
            if (i == 0) b.requestFocus()
        }
    }

    private fun start(play: JSONObject) {
        status.text = "Starting…"
        Thread {
            try {
                Finder.play(play.getString("title"), null, null, play.optString("service").ifEmpty { null }, Now.who)
                main.post { finish() }
            } catch (e: Exception) { main.post { status.text = e.message ?: "Could not start that." } }
        }.start()
    }
}
