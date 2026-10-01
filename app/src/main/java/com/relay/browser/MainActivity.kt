package com.relay.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var panel: LinearLayout
    private lateinit var goalBox: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var continueBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val prefs by lazy { getSharedPreferences("relay", MODE_PRIVATE) }
    private val relayJs by lazy { assets.open("relay.js").bufferedReader().use { it.readText() } }
    private val systemPrompt by lazy { assets.open("agent_prompt.txt").bufferedReader().use { it.readText() } }

    private var loading = false
    private var agentJob: Job? = null
    private val rows = mutableListOf<List<String>>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val ink = Color.parseColor("#1F2933")
    private val muted = Color.parseColor("#5B6B7A")
    private val red = Color.parseColor("#C2352B")
    private val green = Color.parseColor("#2E7D4F")

    private val sensitive = Regex(
        "\\b(pay|payment|pay now|buy|sell|purchase|place order|order now|book|book now|checkout|check out|confirm|submit|send|delete|remove|transfer|withdraw|proceed to pay|i agree|accept)\\b",
        RegexOption.IGNORE_CASE
    )

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun defaultModel(provider: String) = if (provider == "gemini") "gemini-2.5-flash" else "claude-sonnet-5-5"

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.parseColor("#F3F5F7"))
        }

        // Address bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        urlBox = EditText(this).apply {
            hint = "Search or type a web address"
            isSingleLine = true
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { go(text.toString()); hideKeyboard(); true } else false
            }
        }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(button("AI") { panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        bar.addView(button("Settings") { showSettings() })

        web = WebView(this)
        setupWeb()

        // AI panel
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.WHITE)
        }
        goalBox = EditText(this).apply {
            hint = "Tell the AI what to do, e.g. find the 5 cheapest flights Bangalore to Delhi on 20 Oct"
            textSize = 14f
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = button("Start") { startAgent() }
        stopBtn = button("Stop") { agentJob?.cancel() }.apply { isEnabled = false }
        continueBtn = button("Continue") {}.apply { visibility = View.GONE }
        shareBtn = button("Share results") { shareResults() }.apply { visibility = View.GONE }
        listOf(startBtn, stopBtn, continueBtn, shareBtn).forEach { buttons.addView(it) }
        statusView = TextView(this).apply { textSize = 12f; setTextColor(muted); text = "Ready" }
        logView = TextView(this).apply { textSize = 13f; setTextColor(ink); setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        panel.addView(goalBox, LinearLayout.LayoutParams(match, wrap))
        panel.addView(buttons, LinearLayout.LayoutParams(match, wrap))
        panel.addView(statusView, LinearLayout.LayoutParams(match, wrap))
        panel.addView(logScroll, LinearLayout.LayoutParams(match, 0, 1f))

        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(web, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(panel, LinearLayout.LayoutParams(match, dp(280)))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        web.loadUrl(prefs.getString("home", null)?.ifBlank { null } ?: "https://www.google.com")
        if (prefs.getString("key", "").isNullOrBlank()) showSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: ""
                if (scheme == "http" || scheme == "https") return false
                // upi:, tel:, mailto:, intent: and app links open in the right app
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No app can open this link") }
                return true
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                if (url != null) urlBox.setText(url)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                if (url != null) urlBox.setText(url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                pickFile.launch("*/*")
                return true
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            }
        }
    }

    private fun go(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!Regex("^[a-zA-Z]+://").containsMatchIn(u)) {
            u = if (u.contains(" ") || !u.contains(".")) "https://www.google.com/search?q=" + URLEncoder.encode(u, "UTF-8")
            else "https://$u"
        }
        web.loadUrl(u)
    }

    /* ---------------- settings ---------------- */

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(muted); setPadding(0, dp(10), 0, 0) }

        val current = prefs.getString("provider", "claude") ?: "claude"
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rClaude = RadioButton(this).apply { text = "Claude"; id = View.generateViewId() }
        val rGemini = RadioButton(this).apply { text = "Gemini"; id = View.generateViewId() }
        group.addView(rClaude); group.addView(rGemini)
        (if (current == "gemini") rGemini else rClaude).isChecked = true

        val key = EditText(this).apply {
            hint = "Paste your API key"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val model = EditText(this).apply {
            isSingleLine = true
            setText(prefs.getString("model", null)?.ifBlank { null } ?: defaultModel(current))
        }
        group.setOnCheckedChangeListener { _, checked -> model.setText(defaultModel(if (checked == rGemini.id) "gemini" else "claude")) }
        val max = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("max", 25).toString())
        }
        val shot = CheckBox(this).apply {
            text = "Send a screenshot each step (more accurate, uses more credits)"
            isChecked = prefs.getBoolean("shot", true)
        }
        val home = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("home", "https://www.google.com"))
        }
        val note = TextView(this).apply {
            textSize = 12f; setTextColor(muted); setPadding(0, dp(10), 0, dp(6))
            text = "The key is stored only on this phone. Claude keys: console.anthropic.com. Gemini keys: aistudio.google.com."
        }
        box.addView(label("AI provider")); box.addView(group)
        box.addView(label("API key")); box.addView(key)
        box.addView(label("Model")); box.addView(model)
        box.addView(label("Maximum steps per job")); box.addView(max)
        box.addView(label("Home page")); box.addView(home)
        box.addView(shot); box.addView(note)

        AlertDialog.Builder(this)
            .setTitle("AI settings")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("provider", if (rGemini.isChecked) "gemini" else "claude")
                    .putString("key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim())
                    .putInt("max", (max.text.toString().toIntOrNull() ?: 25).coerceIn(5, 100))
                    .putBoolean("shot", shot.isChecked)
                    .putString("home", home.text.toString().trim())
                    .apply()
                toast("Settings saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /* ---------------- log ---------------- */

    private fun log(title: String, detail: String = "", color: Int = ink) {
        val sb = SpannableStringBuilder()
        sb.append(title)
        sb.setSpan(ForegroundColorSpan(color), 0, sb.length, 0)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, 0)
        if (detail.isNotBlank()) {
            sb.append("\n")
            val start = sb.length
            sb.append(detail)
            sb.setSpan(ForegroundColorSpan(muted), start, sb.length, 0)
        }
        sb.append("\n\n")
        logView.append(sb)
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /* ---------------- dialogs that pause the job ---------------- */

    private suspend fun confirm(message: String): String = suspendCancellableCoroutine { c ->
        val dialog = AlertDialog.Builder(this)
            .setTitle("Approval needed")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Approve") { _, _ -> if (c.isActive) c.resume("approve") }
            .setNeutralButton("Skip") { _, _ -> if (c.isActive) c.resume("skip") }
            .setNegativeButton("Stop job") { _, _ -> if (c.isActive) c.resume("stop") }
            .show()
        c.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
    }

    private suspend fun ask(question: String): String = suspendCancellableCoroutine { c ->
        val input = EditText(this).apply { hint = "Your answer" }
        val holder = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Question from the AI")
            .setMessage(question)
            .setView(holder)
            .setCancelable(false)
            .setPositiveButton("Send") { _, _ -> if (c.isActive) c.resume(input.text.toString().trim().ifEmpty { "done" }) }
            .setNeutralButton("Use the page first") { _, _ ->
                // let the user type a password/OTP in the page, then press Continue
                statusView.text = "Do it in the page, then press Continue"
                continueBtn.visibility = View.VISIBLE
                continueBtn.setOnClickListener {
                    continueBtn.visibility = View.GONE
                    if (c.isActive) c.resume("done, I have finished that in the page")
                }
            }
            .setNegativeButton("Stop job") { _, _ -> if (c.isActive) c.resume(STOP) }
            .show()
        c.invokeOnCancellation { runOnUiThread { dialog.dismiss(); continueBtn.visibility = View.GONE } }
    }

    /* ---------------- page helpers ---------------- */

    private suspend fun js(code: String): String = suspendCancellableCoroutine { c ->
        web.evaluateJavascript(code) { r -> if (c.isActive) c.resume(r ?: "null") }
    }

    private suspend fun waitLoad() {
        var waited = 0
        while ((loading || web.progress < 100) && waited < 20000) { delay(200); waited += 200 }
    }

    private fun screenshot(): String? = try {
        val w = web.width
        val h = web.height
        if (w == 0 || h == 0) null else {
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(full))
            val scale = if (w > 900) 900f / w else 1f
            val small = Bitmap.createScaledBitmap(full, (w * scale).toInt(), (h * scale).toInt(), true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 65, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (e: Exception) { null }

    /* ---------------- AI call ---------------- */

    private suspend fun callAi(provider: String, key: String, model: String, text: String, image: String?): String =
        withContext(Dispatchers.IO) {
            val gemini = provider == "gemini"
            val url = if (gemini)
                URL("https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent")
            else URL("https://api.anthropic.com/v1/messages")

            val body = if (gemini) {
                val parts = JSONArray()
                if (image != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image)))
                parts.put(JSONObject().put("text", text))
                JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
            } else {
                val content: Any = if (image != null) JSONArray()
                    .put(JSONObject().put("type", "image").put("source",
                        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", image)))
                    .put(JSONObject().put("type", "text").put("text", text))
                else text
                JSONObject()
                    .put("model", model)
                    .put("max_tokens", 2000)
                    .put("system", systemPrompt)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            }

            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                conn.setRequestProperty("Content-Type", "application/json")
                if (gemini) conn.setRequestProperty("x-goog-api-key", key)
                else {
                    conn.setRequestProperty("x-api-key", key)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = try { JSONObject(resp) } catch (e: Exception) { throw Exception("HTTP $code: ${resp.take(200)}") }
                if (code !in 200..299) throw Exception(json.optJSONObject("error")?.optString("message")?.ifBlank { null } ?: "HTTP $code")
                buildString {
                    if (gemini) {
                        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text") ?: "")
                    } else {
                        val content = json.optJSONArray("content")
                        if (content != null) for (i in 0 until content.length()) {
                            val o = content.optJSONObject(i)
                            if (o?.optString("type") == "text") append(o.optString("text"))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun parseReply(t: String): JSONObject {
        val s = t.replace("```json", "").replace("```", "")
        val i = s.indexOf('{')
        val j = s.lastIndexOf('}')
        if (i < 0 || j < i) throw Exception("AI reply was not JSON")
        return JSONObject(s.substring(i, j + 1))
    }

    private fun describe(a: JSONObject, action: String, elementLine: String): String {
        val name = elementLine.replace(Regex("^\\[\\d+\\]\\s*"), "").replace(Regex(" -> .*$"), "")
        return when (action) {
            "click" -> "Click $name"
            "type" -> "Type \"${a.optString("text")}\" into $name" + if (a.optBoolean("submit")) " and press Enter" else ""
            "select" -> "Choose \"${a.optString("text")}\" in $name"
            "navigate" -> "Open ${a.optString("url")}"
            "scroll" -> if (a.optString("direction") == "up") "Scroll up" else "Scroll down"
            "back" -> "Go back"
            "wait" -> "Wait for the page"
            "extract" -> "Collect ${maxOf(0, (a.optJSONArray("data")?.length() ?: 0) - 1)} rows"
            else -> action.ifBlank { "Unknown step" }
        }
    }

    /* ---------------- agent ---------------- */

    private fun startAgent() {
        val goal = goalBox.text.toString().trim()
        if (goal.isEmpty()) { toast("Describe the job first"); return }
        if (prefs.getString("key", "").isNullOrBlank()) { showSettings(); return }
        hideKeyboard()
        rows.clear()
        shareBtn.visibility = View.GONE
        logView.text = ""
        startBtn.isEnabled = false
        stopBtn.isEnabled = true
        agentJob = lifecycleScope.launch {
            try {
                runAgent(goal)
            } catch (e: CancellationException) {
                log("Stopped by you", "", red)
                throw e
            } catch (e: Exception) {
                log("AI request failed", e.message ?: e.toString(), red)
                statusView.text = "Check the API key, model name and internet connection"
            } finally {
                startBtn.isEnabled = true
                stopBtn.isEnabled = false
                continueBtn.visibility = View.GONE
            }
        }
    }

    private suspend fun runAgent(goal: String) {
        val provider = prefs.getString("provider", "claude") ?: "claude"
        val key = prefs.getString("key", "") ?: ""
        val model = prefs.getString("model", "")?.ifBlank { null } ?: defaultModel(provider)
        val max = prefs.getInt("max", 25)
        val useShot = prefs.getBoolean("shot", true)
        val history = mutableListOf<String>()
        var fails = 0

        for (step in 1..max) {
            statusView.text = "Step $step of up to $max: reading the page"
            waitLoad()
            js(relayJs)
            val snap = try { JSONObject(js("window.__relaySnap ? window.__relaySnap() : null")) } catch (e: Exception) { null }
            if (snap == null) { delay(1500); continue }
            val elements = snap.optString("elements")
            val image = if (useShot) screenshot() else null
            val prompt = buildString {
                append("GOAL: ").append(goal).append("\n\n")
                append("HISTORY (oldest first):\n").append(history.takeLast(14).joinToString("\n").ifBlank { "none yet" }).append("\n\n")
                append("URL: ").append(snap.optString("url")).append("\n")
                append("TITLE: ").append(snap.optString("title")).append("\n")
                append("SCROLL POSITION: ").append(snap.optString("scroll")).append("\n\n")
                append("ELEMENTS:\n").append(elements.ifBlank { "(none visible)" }).append("\n\n")
                append("PAGE TEXT:\n").append(snap.optString("text"))
            }

            statusView.text = "Step $step: ${if (provider == "gemini") "Gemini" else "Claude"} is deciding"
            val reply = callAi(provider, key, model, prompt, image)
            val a = try { parseReply(reply) } catch (e: Exception) {
                history.add("$step. (your reply was not valid JSON; reply with one JSON object only)")
                if (++fails >= 3) { log("AI replies could not be read", e.message ?: "", red); return }
                continue
            }

            val action = a.optString("action")
            val thought = a.optString("thought")
            val message = a.optString("message")
            val id = if (a.has("id") && !a.isNull("id")) a.optInt("id", -1) else -1
            val elementLine = elements.split("\n").firstOrNull { it.startsWith("[$id]") } ?: ""
            val label = describe(a, action, elementLine)

            when (action) {
                "done" -> {
                    log("Finished", message, green)
                    statusView.text = "Job finished"
                    return
                }
                "ask" -> {
                    log("Question for you", thought)
                    val answer = ask(message.ifBlank { "What should I do next?" })
                    if (answer == STOP) { log("Stopped by you", "", red); return }
                    history.add("$step. Asked user: \"$message\" -> user replied: \"$answer\"")
                    continue
                }
                "extract" -> {
                    val data = a.optJSONArray("data")
                    var count = 0
                    if (data != null) {
                        if (rows.isNotEmpty()) rows.add(emptyList())
                        for (i in 0 until data.length()) {
                            val r = data.optJSONArray(i) ?: continue
                            rows.add((0 until r.length()).map { r.optString(it) })
                            count++
                        }
                    }
                    if (count > 0) shareBtn.visibility = View.VISIBLE
                    log(label, message.ifBlank { thought })
                    history.add("$step. Extracted $count rows: $message")
                    continue
                }
            }

            val submit = a.optBoolean("submit", false)
            val risky = a.optBoolean("needs_confirmation", false) ||
                ((action == "click" || (action == "type" && submit)) && sensitive.containsMatchIn(elementLine))
            if (risky) {
                when (confirm("$label\n\n$thought")) {
                    "stop" -> { log("Stopped by you", "", red); return }
                    "skip" -> {
                        history.add("$step. User REFUSED: $label. Do not try it again; ask the user how to continue.")
                        log("Skipped: $label", "", muted)
                        continue
                    }
                }
            }

            log(label, thought)
            var ok = true
            var msg = "ok"
            when (action) {
                "navigate" -> {
                    var u = a.optString("url").trim()
                    if (!Regex("^[a-zA-Z]+://").containsMatchIn(u)) u = "https://$u"
                    web.loadUrl(u)
                    delay(400)
                }
                "scroll" -> js("scrollBy(0, ${if (a.optString("direction") == "up") -1 else 1} * innerHeight * 0.8)")
                "back" -> if (web.canGoBack()) web.goBack() else { ok = false; msg = "no previous page" }
                "wait" -> delay(2000)
                "click", "type", "select" -> {
                    val res = try { JSONObject(js("window.__relayAct($a)")) } catch (e: Exception) {
                        JSONObject().put("ok", false).put("msg", e.message ?: "script error")
                    }
                    ok = res.optBoolean("ok")
                    msg = res.optString("msg")
                    if (ok && action == "type" && submit) {
                        web.requestFocus()
                        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                    }
                }
                else -> { ok = false; msg = "unknown action $action" }
            }

            if (!ok) { log("That step failed", msg, red); fails++ } else fails = 0
            history.add("$step. $label -> ${if (ok) "ok" else "FAILED: $msg"}")
            if (fails >= 4) {
                log("Too many failed steps in a row", "Try rewording the job or do this part by hand.", red)
                return
            }
            delay(800)
            waitLoad()
        }
        log("Reached the $max-step limit", "Raise it in Settings or split the job into smaller parts.", red)
    }

    private fun shareResults() {
        val csv = rows.joinToString("\r\n") { r ->
            r.joinToString(",") { c ->
                if (c.contains(',') || c.contains('"') || c.contains('\n')) "\"" + c.replace("\"", "\"\"") + "\"" else c
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Relay Browser results")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "Share results"))
    }

    companion object {
        private const val STOP = "__stop__"
    }
}
