package com.microtesla.controller

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("controller", MODE_PRIVATE) }

    private lateinit var address: EditText
    private lateinit var tcEdit: EditText
    private lateinit var dsEdit: EditText
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var readings: TextView
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button

    private var requested = false
    private var started = false
    private var connected = false
    private var destroyed = false
    private var sendPending = false
    private var pollInFlight = false
    private var sessionToken = 0
    private var seeded = false
    private val pollDelayMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        configureInsets(window)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(22))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF4F7F6.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("کنترلر MicroTesla", 25, true))
        subtitle = label(
            "ابتدا به Wi‑Fi دستگاه (MICROTESLA) متصل شوید. شبکه اینترنت ندارد؛ برنامه از مسیر خود Wi‑Fi درخواست محلی می‌فرستد.",
            15, false
        )
        root.addView(subtitle)

        address = edit("نشانی ESP32 (IPv4 یا نام میزبان، بدون http)",
            prefs.getString("address", "192.168.4.1") ?: "192.168.4.1", false)
        root.addView(address)

        connectButton = Button(this).apply { text = "اتصال و شروع پایش" }
        root.addView(connectButton)

        status = label("آمادهٔ اتصال", 15, false).also { root.addView(it) }

        root.addView(label("خوانش‌های دستگاه", 19, true))
        readings = label("هنوز داده‌ای دریافت نشده است", 15, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(readings)

        root.addView(label("تنظیم دما", 19, true))
        root.addView(label("هدف ترموکوپل TC (0 تا 1024 °C):", 15, false))
        tcEdit = edit("", "", true)
        root.addView(tcEdit)
        root.addView(label("هدف سنسور DS1 (‎−55 تا 125 °C):", 15, false))
        dsEdit = edit("", "", true)
        root.addView(dsEdit)

        sendButton = Button(this).apply { text = "اعمال تنظیمات"; isEnabled = false }
        root.addView(sendButton)

        root.addView(label(
            "توجه: تنظیم‌ها در ESP32 پس از ری‌بوت ذخیره نمی‌شوند و هیسترزیس در firmware ثابت ۱°C است و از برنامه قابل تغییر نیست. اگر هر دو فیلد را خالی بگذارید چیزی ارسال نمی‌شود.",
            14, false
        ))

        connectButton.setOnClickListener {
            if (requested) {
                // توقف همیشه مجاز است
                requested = false
                connected = false
                sessionToken++
                pollInFlight = false
                connectButton.text = "اتصال و شروع پایش"
                address.isEnabled = true
                sendButton.isEnabled = false
                showStatus("پایش متوقف شد", true)
            } else {
                val host = hostOrNull(address.text.toString())
                if (host == null) {
                    showStatus("نشانی معتبر وارد کنید؛ فقط IPv4 یا نام میزبان و در صورت نیاز :port مجاز است", false)
                    return@setOnClickListener
                }
                requested = true
                seeded = false
                prefs.edit().putString("address", host).apply()
                if (started) beginSession()
                else showStatus("پس از بازگشت برنامه به صفحه، پایش آغاز می‌شود", true)
            }
        }

        sendButton.setOnClickListener { sendSettings() }
    }

    private fun beginSession() {
        if (!requested || !started || destroyed) return
        sessionToken++
        val token = sessionToken
        pollInFlight = false
        sendPending = false
        connected = false
        address.isEnabled = false
        connectButton.text = "توقف پایش"
        sendButton.isEnabled = false
        showStatus("در حال یافتن مسیر Wi‑Fi و خواندن داده‌ها…", true)
        poll(token)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (requested) beginSession()
    }

    override fun onStop() {
        started = false
        sessionToken++
        pollInFlight = false
        sendPending = false
        connected = false
        address.isEnabled = true
        sendButton.isEnabled = false
        if (requested) showStatus("پایش در پس‌زمینه متوقف است؛ با بازگشت ادامه می‌یابد", true)
        super.onStop()
    }

    private fun poll(token: Int) {
        if (!valid(token) || pollInFlight) return
        val host = hostOrNull(address.text.toString())
        if (host == null) { showStatus("نشانی نامعتبر است", false); return }
        pollInFlight = true
        io.execute {
            var obj: JSONObject? = null
            var err: String? = null
            try { obj = requestJson(host, "/data") } catch (e: Exception) { err = e.message ?: "ارتباط برقرار نشد" }
            val result = obj
            val error = err
            main.post {
                if (!valid(token)) return@post
                pollInFlight = false
                if (result != null) render(result)
                else showStatus("خطا در دریافت /data: $error", false)
                main.postDelayed({ if (valid(token) && !pollInFlight && requested && started && !destroyed) poll(token) }, pollDelayMs)
            }
        }
    }

    private fun render(o: JSONObject) {
        val sb = StringBuilder()
        sb.append("دمای ترموکوپل TC: ").append(temp(o, "tc_C")).append(" °C\n")
        sb.append("دمای DS1: ").append(temp(o, "ds1_C")).append(" °C    دمای DS2: ").append(temp(o, "ds2_C")).append(" °C\n")
        sb.append("خروجی TC: ").append(onOff(o, "outputs", "tc"))
            .append("    خروجی DS1: ").append(onOff(o, "outputs", "ds1")).append('\n')
        sb.append("مقدار تنظیم‌شده در دستگاه — TC: ").append(temp(o, "setpoints_C", "tc"))
            .append(" °C    DS1: ").append(temp(o, "setpoints_C", "ds1")).append(" °C\n")
        sb.append("ورودی‌ها (1 = کلید فشرده / پین LOW):")
        val inputs: JSONArray? = o.optJSONArray("inputs")
        if (inputs == null) sb.append(" ناموجود")
        else for (i in 0 until inputs.length()) {
            val v = if (inputs.isNull(i)) "-" else inputs.optInt(i).toString()
            sb.append("  کلید ").append(i + 1).append(": ").append(v)
        }
        readings.text = sb.toString()

        val ap = o.optJSONObject("ap")
        if (ap != null) {
            val ssid = ap.optString("ssid", "")
            val ip = ap.optString("ip", "")
            val secure = ap.optBoolean("secure", false)
            subtitle.text = "دستگاه: " + (if (ssid.isEmpty()) "MICROTESLA" else ssid) +
                " | IP: " + (if (ip.isEmpty()) "—" else ip) +
                " | شبکه: " + (if (secure) "رمزدار" else "باز")
        }

        // Dirty-guard: فقط اولین پاسخ موفق و وقتی کاربر چیزی تایپ نکرده، فیلدها را مقدار می‌دهیم
        if (!seeded && tcEdit.text.toString().trim().isEmpty() && dsEdit.text.toString().trim().isEmpty()) {
            val sp = o.optJSONObject("setpoints_C")
            if (sp != null) {
                if (!sp.isNull("tc")) {
                    val v = sp.optDouble("tc", Double.NaN)
                    if (v.isFinite()) tcEdit.setText(fmtShort(v))
                }
                if (!sp.isNull("ds1")) {
                    val v = sp.optDouble("ds1", Double.NaN)
                    if (v.isFinite()) dsEdit.setText(fmtShort(v))
                }
            }
        }
        seeded = true
        connected = true
        if (!sendPending) sendButton.isEnabled = true
        showStatus("داده‌های دستگاه دریافت شد", true)
    }

    private fun sendSettings() {
        if (!connected || sendPending) return
        val host = hostOrNull(address.text.toString())
        if (host == null) { showStatus("نشانی معتبر وارد کنید", false); return }

        val tcRaw = tcEdit.text.toString().trim()
        val dsRaw = dsEdit.text.toString().trim()
        val tcVal: Double? = if (tcRaw.isEmpty()) null else parseNumber(tcRaw)
        val dsVal: Double? = if (dsRaw.isEmpty()) null else parseNumber(dsRaw)

        if (tcVal == null && dsVal == null && (tcRaw.isNotEmpty() || dsRaw.isNotEmpty())) {
            showStatus("عدد واردشده معتبر نیست (ارقام فارسی هم پذیرفته است)", false); return
        }
        if (tcRaw.isEmpty() && dsRaw.isEmpty()) {
            showStatus("حداقل یکی از دو مقدار TC یا DS1 را وارد کنید", false); return
        }
        if (tcRaw.isNotEmpty() && tcVal == null) { showStatus("مقدار TC عدد معتبر نیست", false); return }
        if (dsRaw.isNotEmpty() && dsVal == null) { showStatus("مقدار DS1 عدد معتبر نیست", false); return }
        if (tcVal != null && (tcVal < 0.0 || tcVal > 1024.0)) { showStatus("مقدار TC باید بین ۰ تا ۱۰۲۴ درجهٔ سانتی‌گراد باشد", false); return }
        if (dsVal != null && (dsVal < -55.0 || dsVal > 125.0)) { showStatus("مقدار DS1 باید بین ‎−۵۵ تا ۱۲۵ درجهٔ سانتی‌گراد باشد", false); return }

        val parts = mutableListOf<String>()
        if (tcVal != null) parts.add("tc=" + URLEncoder.encode(fmt(tcVal), "UTF-8"))
        if (dsVal != null) parts.add("ds=" + URLEncoder.encode(fmt(dsVal), "UTF-8"))
        val query = parts.joinToString("&")

        val token = sessionToken
        sendPending = true
        sendButton.isEnabled = false
        showStatus("در حال ارسال و راستی‌آزمایی با خواندن مجدد…", true)
        io.execute {
            var outcome = ""
            var okMsg: String? = null
            var error: String? = null
            try {
                val response = requestText(host, "/set?$query")
                val j = JSONObject(response)
                if (!j.optBoolean("ok", false)) {
                    error = j.optString("error", "پاسخ دستگاه ok:false بود")
                } else {
                    // readback /data و تطبیق setpointهای بازتابیده با tolerance 0.01
                    val readback = requestJson(host, "/data")
                    val sp = readback.optJSONObject("setpoints_C")
                    var verified = sp != null
                    if (sp != null && tcVal != null) {
                        if (sp.isNull("tc") || !near(sp.optDouble("tc", Double.NaN), tcVal)) verified = false
                    }
                    if (sp != null && dsVal != null) {
                        if (sp.isNull("ds1") || !near(sp.optDouble("ds1", Double.NaN), dsVal)) verified = false
                    }
                    if (!verified) error = "مقادیر نقطهٔ تنظیم در خواندن مجدد تأیید نشد"
                    else { okMsg = "تنظیمات اعمال و تأیید شد"; outcome = "verified" }
                }
            } catch (e: Exception) {
                error = e.message ?: "ارتباط برقرار نشد"
            }
            val finalOk = okMsg
            val finalErr = error
            val outcomeKind = outcome
            main.post {
                if (!valid(token)) return@post
                sendPending = false
                sendButton.isEnabled = connected
                if (finalOk != null && outcomeKind == "verified") {
                    prefs.edit().putString("tc", tcEdit.text.toString().trim())
                        .putString("ds", dsEdit.text.toString().trim()).apply()
                    showStatus(finalOk + " (توجه: پس از ری‌بوت ESP32 تنظیم‌ها پاک می‌شوند)", true)
                } else {
                    showStatus("اعمال تنظیمات ناموفق بود: ${finalErr ?: "خطای نامشخص"}", false)
                }
            }
        }
    }

    // ---------- شبکه ----------

    private fun requestJson(host: String, path: String): JSONObject = JSONObject(requestText(host, path))

    private fun requestText(host: String, path: String): String {
        val network: Network? = wifiNetwork()
        // مسیر ترجیحی: سوکت روی شبکهٔ Wi‑Fi دستگاه. اگر شبکهٔ Wi‑Fi در سیستم دیده نشد
        // (مثلاً محدودیت سازنده)، با پیام راهنما روی اتصال پیش‌فرض تلاش می‌کنیم.
        val url = URL("http://$host$path")
        val c = if (network != null) network.openConnection(url) as HttpURLConnection
                else url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 5000
        c.readTimeout = 5000
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun wifiNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        return networks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    // ---------- کمک‌تابع‌ها ----------

    private fun near(got: Double, target: Double): Boolean =
        got.isFinite() && kotlin.math.abs(got - target) <= 0.01

    private fun hostOrNull(rawInput: String): String? {
        val raw = rawInput.trim()
        if (raw.length !in 1..253 || raw.contains(Regex("[\\s/?#@\\\\]")) || raw.contains("://")) return null
        val m = HOST_PORT.matchEntire(raw) ?: return null
        val host = m.groupValues[1]
        val port = m.groupValues[2]
        if (port.isNotEmpty()) {
            val portNumber = port.toIntOrNull() ?: return null
            if (portNumber !in 1..65535) return null
        }
        if (host.matches(Regex("[0-9.]+"))) {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { val p = it.toIntOrNull(); p == null || p !in 0..255 }) return null
        } else {
            if (host.length > 253 || host.split('.').any {
                    it.isEmpty() || it.length > 63 || !it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                }) return null
        }
        return host + if (port.isNotEmpty()) ":$port" else ""
    }
    private val HOST_PORT = Regex("^([^:]+)(?::([0-9]{1,5}))?$")

    private fun parseNumber(s: String): Double? = try {
        val normalized = s.trim().map { ch -> when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            '٫' -> '.'; '−' -> '-'; '٬' -> ','; else -> ch
        } }.joinToString("").replace(",", "")
        normalized.toDouble()
    } catch (_: Exception) { null }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)
    private fun fmtShort(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun temp(o: JSONObject, key: String): String {
        if (!o.has(key) || o.isNull(key)) return "--"
        val v = o.optDouble(key, Double.NaN)
        return if (!v.isFinite()) "--" else String.format(Locale.US, "%.2f", v)
    }
    private fun temp(o: JSONObject, group: String, key: String): String {
        val g = o.optJSONObject(group) ?: return "--"
        return temp(g, key)
    }
    private fun onOff(o: JSONObject, group: String, key: String): String {
        val g = o.optJSONObject(group) ?: return "--"
        val v = g.optInt(key, -1)
        return when (v) { 1 -> "روشن"; 0 -> "خاموش"; else -> "--" }
    }

    private fun valid(token: Int) = !destroyed && started && requested && token == sessionToken

    private fun showStatus(s: String, ok: Boolean) {
        status.text = s
        status.setTextColor(if (ok) 0xFF176B42.toInt() else 0xFFB3261E.toInt())
    }

    private fun label(text: String, size: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(0xFF172522.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.RIGHT; setPadding(dp(2), dp(8), dp(2), dp(8))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun edit(hintText: String, value: String, numeric: Boolean): EditText = EditText(this).apply {
        hint = hintText; setText(value); textSize = 16f; setSingleLine(true)
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        gravity = Gravity.RIGHT; layoutDirection = View.LAYOUT_DIRECTION_LTR
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun configureInsets(w: Window) {
        if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        val decor = w.decorView
        decor.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int; val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()
                val bars = insets.getInsets(types)
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                val old = insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                top = old.first; bottom = old.second
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        destroyed = true
        requested = false
        sessionToken++
        io.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
