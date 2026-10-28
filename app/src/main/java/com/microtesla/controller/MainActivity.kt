package com.microtesla.controller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("controller", MODE_PRIVATE) }
    private val navy = 0xFF091725.toInt(); private val panel = 0xFF12263A.toInt()
    private val cyan = 0xFF35D4E8.toInt(); private val white = 0xFFF4F8FC.toInt()
    private val muted = 0xFF9BB0C3.toInt(); private val green = 0xFF54D69A.toInt()
    private lateinit var address: EditText; private lateinit var tcEdit: EditText; private lateinit var dsEdit: EditText
    private lateinit var status: TextView; private lateinit var chip: TextView; private lateinit var subtitle: TextView
    private lateinit var connect: Button; private lateinit var apply: Button; private lateinit var tcValue: TextView
    private lateinit var ds1Value: TextView; private lateinit var ds2Value: TextView; private lateinit var tcActual: TextView
    private lateinit var dsActual: TextView; private lateinit var tcRelay: TextView; private lateinit var dsRelay: TextView
    private val inputs = mutableListOf<TextView>()
    private var requested = false; private var started = false; private var destroyed = false
    private var connected = false; private var pending = false; private var inFlight = false
    private var token = 0; private var seeded = false; private var lastTc: Double? = null; private var lastDs: Double? = null
    private val pollMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = navy; window.navigationBarColor = navy
        val scroll = ScrollView(this).apply { setBackgroundColor(navy); isFillViewport = true; clipToPadding = false }
        val root = col().apply { setPadding(dp(16), dp(14), dp(16), dp(28)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(root); setContentView(scroll)

        val brand = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val icon = label("M", 22, true, cyan, Gravity.CENTER).apply { background = round(0xFF173C50.toInt(), 16); contentDescription = "نشان MicroTesla" }
        brand.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = col().apply { setPadding(dp(10), 0, dp(8), 0) }
        title.addView(label("MICROTESLA", 20, true, white)); title.addView(label("داشبورد کنترل دما", 14, false, muted))
        brand.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        chip = label("◉  آمادهٔ اتصال", 13, true, muted, Gravity.CENTER).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); background = round(panel, 25) }
        chip.contentDescription = "وضعیت اتصال: آماده اتصال"; brand.addView(chip); root.addView(brand)
        subtitle = label("برای شروع، به نقطهٔ دسترسی دستگاه متصل شوید.", 13, false, muted)
        subtitle.setPadding(dp(3), dp(12), dp(3), dp(6)); root.addView(subtitle)

        val conn = card(); conn.addView(label("نشانی سرور", 15, true, white))
        address = EditText(this).apply {
            setSingleLine(true); textSize = 16f; setTextColor(white); setHintTextColor(muted); hint = "IPv4 یا نام میزبان"
            setText(prefs.getString("address", "192.168.4.1")); background = round(0xFF0B1B2B.toInt(), 13)
            setPadding(dp(12), dp(4), dp(12), dp(4)); layoutDirection = View.LAYOUT_DIRECTION_LTR
            contentDescription = "نشانی ESP32؛ IPv4 یا نام میزبان و پورت اختیاری"
        }
        conn.addView(address, match())
        val buttons = row().apply { setPadding(0, dp(9), 0, 0) }
        val wifi = button("⚙  تنظیمات Wi-Fi", false).apply {
            contentDescription = "بازکردن تنظیمات Wi-Fi گوشی"
            setOnClickListener { try { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { message("تنظیمات Wi-Fi در دسترس نیست", false) } }
        }
        buttons.addView(wifi, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(7) })
        connect = button("اتصال و شروع پایش", true); connect.contentDescription = "شروع یا توقف پایش"
        buttons.addView(connect, LinearLayout.LayoutParams(0, dp(52), 1f)); conn.addView(buttons); root.addView(conn)
        status = label("●  آمادهٔ اتصال به دستگاه", 14, false, muted).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = round(0xFF102338.toInt(), 13)
            contentDescription = "پیام وضعیت و خطا"
        }
        root.addView(status, match(10))

        root.addView(section("خوانش‌های زنده", "وضعیت دما و خروجی‌های کنترلی"))
        tcValue = sensor(root, "TC · MAX6675", "ترموکوپل · کانال کنترل")
        ds1Value = sensor(root, "DS1 · DS18B20", "حسگر کانال کنترل")
        ds2Value = sensor(root, "DS2 · DS18B20", "حسگر پایش کمکی")

        root.addView(section("ورودی‌های دیجیتال", "۱ یعنی فشرده؛ ورودی با pull-up و سطح LOW"))
        val pinCard = card(); pinCard.addView(label("پایه‌های GPIO دستگاه", 13, false, muted))
        val pins = intArrayOf(15, 2, 0, 4, 16, 17, 5)
        for (start in intArrayOf(0, 4)) {
            val line = row().apply { setPadding(0, dp(8), 0, 0) }; val end = minOf(start + 4, pins.size)
            for (i in start until end) {
                val tile = label("GPIO ${pins[i]}\nنامشخص", 12, true, muted, Gravity.CENTER).apply {
                    background = round(0xFF102338.toInt(), 12); contentDescription = "GPIO ${pins[i]} نامشخص"
                }
                inputs.add(tile); line.addView(tile, LinearLayout.LayoutParams(0, dp(58), 1f).apply { if (i < end - 1) marginEnd = dp(5) })
            }
            if (end - start < 4) line.addView(View(this), LinearLayout.LayoutParams(0, dp(58), (4 - end + start).toFloat()))
            pinCard.addView(line)
        }
        root.addView(pinCard)
        root.addView(section("نقطه‌های تنظیم", "فقط TC و DS1 · مقدارها برحسب درجهٔ سانتی‌گراد"))
        tcActual = setCard(root, "TC · MAX6675", "محدودهٔ مجاز: ۰ تا ۱۰۲۴°C", true)
        dsActual = setCard(root, "DS1 · DS18B20", "محدودهٔ مجاز: −۵۵ تا ۱۲۵°C", false)
        apply = button("✓  اعمال و بررسی تنظیمات", true).apply { isEnabled = false; contentDescription = "اعمال تنظیمات و بررسی بازخوانی از دستگاه" }
        root.addView(apply, match(8))
        root.addView(label("منطق هیتر: روشن در دمای ≤ نقطهٔ تنظیم − ۱°C؛ خاموش در دمای ≥ نقطهٔ تنظیم. هیسترزیس همیشه ۱°C است و قابل تنظیم نیست.", 13, false, muted).apply { setPadding(dp(4), dp(14), dp(4), dp(2)) })
        root.addView(label("تنظیم‌های ESP32 پس از راه‌اندازی مجدد باقی نمی‌مانند.", 13, true, cyan).apply { setPadding(dp(4), dp(7), dp(4), dp(3)) })
        root.addView(label("راهنما: ● روشن  ·  ○ خاموش  ·  ◌ نامشخص — این برنامه کنترل دستی رله ندارد.", 12, false, muted).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        connect.setOnClickListener {
            if (requested) stopPolling() else {
                val host = hostOrNull(address.text.toString())
                if (host == null) { message("نشانی نامعتبر است؛ IPv4 یا hostname و در صورت نیاز :port وارد کنید", false); return@setOnClickListener }
                address.setText(host); prefs.edit().putString("address", host).apply(); requested = true; seeded = false
                if (started) begin() else message("با بازگشت به برنامه پایش آغاز می‌شود", true)
            }
        }
        apply.setOnClickListener { submit() }
    }

    private fun sensor(parent: LinearLayout, title: String, desc: String): TextView {
        val box = card(); val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val names = col(); names.addView(label(title, 16, true, white)); names.addView(label(desc, 12, false, muted))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val relay = label("◌ نامشخص", 12, true, muted, Gravity.CENTER).apply { setPadding(dp(9), dp(8), dp(9), dp(8)); background = round(0xFF102338.toInt(), 20) }
        if (title.startsWith("TC")) tcRelay = relay else if (title.startsWith("DS1")) dsRelay = relay
        top.addView(relay); box.addView(top)
        val line = row().apply { gravity = Gravity.BOTTOM; setPadding(0, dp(13), 0, 0) }
        val value = label("--", 34, true, muted).apply { textDirection = View.TEXT_DIRECTION_LTR; contentDescription = "دما هنوز دریافت نشده" }
        line.addView(value); line.addView(label("°C", 14, true, muted).apply { setPadding(dp(6), 0, 0, dp(8)) })
        box.addView(line); parent.addView(box); return value
    }

    private fun setCard(parent: LinearLayout, title: String, range: String, isTc: Boolean): TextView {
        val box = card(); val head = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val names = col(); names.addView(label(title, 16, true, white)); names.addView(label(range, 12, false, muted))
        head.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        val actual = label("دستگاه: -- °C", 13, true, cyan, Gravity.END); head.addView(actual); box.addView(head)
        val controls = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val field = EditText(this).apply {
            hint = "°C"; textSize = 19f; setTextColor(white); setHintTextColor(muted); gravity = Gravity.CENTER
            background = round(0xFF0B1B2B.toInt(), 12); setSingleLine(true); layoutDirection = View.LAYOUT_DIRECTION_LTR
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(dp(7), 0, dp(7), 0); contentDescription = "نقطه تنظیم $title، $range، اعداد فارسی و عربی پشتیبانی می‌شوند"
        }
        val minus = button("−", false).apply { contentDescription = "کاهش نقطه تنظیم $title یک درجه" }
        val plus = button("+", false).apply { contentDescription = "افزایش نقطه تنظیم $title یک درجه" }
        controls.addView(minus, LinearLayout.LayoutParams(dp(52), dp(50)))
        controls.addView(field, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(7); marginEnd = dp(7) })
        controls.addView(plus, LinearLayout.LayoutParams(dp(52), dp(50))); box.addView(controls); parent.addView(box)
        val low = if (isTc) 0.0 else -55.0; val high = if (isTc) 1024.0 else 125.0
        fun step(n: Double) { val base = parse(field.text.toString()) ?: (if (isTc) lastTc else lastDs) ?: low; field.setText(short((base + n).coerceIn(low, high))); field.setSelection(field.text.length) }
        minus.setOnClickListener { step(-1.0) }; plus.setOnClickListener { step(1.0) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (connected && !pending) apply.isEnabled = true }
            override fun afterTextChanged(s: Editable?) {}
        })
        if (isTc) tcEdit = field else dsEdit = field
        return actual
    }

    override fun onStart() { super.onStart(); started = true; if (requested) begin() }
    override fun onStop() {
        started = false; token++; inFlight = false; pending = false; connected = false
        if (::address.isInitialized) address.isEnabled = true
        if (::apply.isInitialized) apply.isEnabled = false
        if (requested && ::status.isInitialized) message("پایش در پس‌زمینه متوقف است؛ با بازگشت ادامه می‌یابد", true)
        super.onStop()
    }

    private fun begin() {
        if (!requested || !started || destroyed) return
        token++; val id = token; inFlight = false; pending = false; connected = false
        address.isEnabled = false; connect.text = "توقف پایش"; apply.isEnabled = false
        setChip(false, "در حال اتصال"); message("در حال خواندن داده‌های ESP32…", true); poll(id)
    }
    private fun stopPolling() {
        requested = false; token++; inFlight = false; pending = false; connected = false
        connect.text = "اتصال و شروع پایش"; address.isEnabled = true; apply.isEnabled = false
        setChip(false, "متوقف"); stale(); message("پایش متوقف شد؛ داده‌های قبلی کهنه هستند", true)
    }
    private fun valid(id: Int) = !destroyed && started && requested && id == token

    private fun poll(id: Int) {
        if (!valid(id) || inFlight) return
        val host = hostOrNull(address.text.toString()) ?: run { message("نشانی نامعتبر است", false); return }
        inFlight = true
        io.execute {
            var result: JSONObject? = null; var error: String? = null
            try { result = getJson(host, "/data") } catch (e: Exception) { error = e.message ?: "ارتباط برقرار نشد" }
            val data = result; val err = error
            main.post {
                if (!valid(id)) return@post
                inFlight = false
                if (data != null) render(data) else { connected = false; apply.isEnabled = false; setChip(false, "داده کهنه"); message("خطا در دریافت /data: $err", false) }
                main.postDelayed({ if (valid(id) && !inFlight) poll(id) }, pollMs)
            }
        }
    }

    private fun render(o: JSONObject) {
        connected = true
        val t = nullable(o, "tc_C"); val d1 = nullable(o, "ds1_C"); val d2 = nullable(o, "ds2_C")
        showTemp(tcValue, t); showTemp(ds1Value, d1); showTemp(ds2Value, d2)
        val sp = o.optJSONObject("setpoints_C"); val st = sp?.let { nullable(it, "tc") }; val sd = sp?.let { nullable(it, "ds1") }
        if (st != null) lastTc = st; if (sd != null) lastDs = sd
        tcActual.text = "دستگاه: ${st?.let(::short) ?: "--"} °C"; dsActual.text = "دستگاه: ${sd?.let(::short) ?: "--"} °C"
        val out = o.optJSONObject("outputs"); relay(tcRelay, out?.optInt("tc", -1) ?: -1); relay(dsRelay, out?.optInt("ds1", -1) ?: -1)
        val a = o.optJSONArray("inputs"); val pins = o.optJSONArray("input_pins")
        for (i in inputs.indices) {
            val pin = pins?.optInt(i, intArrayOf(15,2,0,4,16,17,5)[i]) ?: intArrayOf(15,2,0,4,16,17,5)[i]
            val v = if (a == null || i >= a.length() || a.isNull(i)) -1 else a.optInt(i, -1)
            val state = if (v == 1) "فشرده" else if (v == 0) "رها" else "نامشخص"
            inputs[i].text = "GPIO $pin\n$state"; inputs[i].setTextColor(if (v == 1) cyan else if (v == 0) white else muted)
            inputs[i].background = round(if (v == 1) 0xFF10414B.toInt() else 0xFF102338.toInt(), 12)
            inputs[i].contentDescription = "GPIO $pin، $state؛ سطح LOW یعنی فشرده"
        }
        val ap = o.optJSONObject("ap")
        if (ap != null) subtitle.text = "دستگاه: ${ap.optString("ssid", "MICROTESLA")}  ·  IP: ${ap.optString("ip", "—")}  ·  ${if (ap.optBoolean("secure", false)) "رمزدار" else "باز"}"
        if (!seeded && sp != null) { if (tcEdit.text.isBlank() && st != null) tcEdit.setText(short(st)); if (dsEdit.text.isBlank() && sd != null) dsEdit.setText(short(sd)) }
        seeded = true; setChip(true, "متصل به دستگاه"); if (!pending) apply.isEnabled = true; message("داده‌های دستگاه دریافت شد", true)
    }

    private fun submit() {
        if (!connected || pending) return
        val tr = tcEdit.text.toString().trim(); val dr = dsEdit.text.toString().trim()
        val tc = if (tr.isEmpty()) null else parse(tr); val ds = if (dr.isEmpty()) null else parse(dr)
        if ((tr.isNotEmpty() && tc == null) || (dr.isNotEmpty() && ds == null)) { message("مقدارها باید عددی باشند", false); return }
        if (tc == null && ds == null) { message("حداقل یکی از نقطه‌های تنظیم را وارد کنید", false); return }
        if (tc != null && (!tc.isFinite() || tc !in 0.0..1024.0)) { message("TC باید بین ۰ و ۱۰۲۴°C باشد", false); return }
        if (ds != null && (!ds.isFinite() || ds !in -55.0..125.0)) { message("DS1 باید بین −۵۵ و ۱۲۵°C باشد", false); return }
        val host = hostOrNull(address.text.toString()) ?: run { message("نشانی سرور نامعتبر است", false); return }
        val id = token; pending = true; apply.isEnabled = false; message("در حال اعمال و بررسی تنظیمات…", true)
        io.execute {
            var error: String? = null; var tcRead: Double? = null; var dsRead: Double? = null
            try {
                val q = mutableListOf<String>()
                if (tc != null) q.add("tc=" + URLEncoder.encode(fmt(tc), "UTF-8"))
                if (ds != null) q.add("ds=" + URLEncoder.encode(fmt(ds), "UTF-8"))
                val response = getJson(host, "/set?${q.joinToString("&")}")
                if (!response.optBoolean("ok", false)) error = response.optString("error", "تنظیمات پذیرفته نشد")
                if (error == null) {
                    val verify = getJson(host, "/data").optJSONObject("setpoints_C")
                    tcRead = verify?.let { nullable(it, "tc") }; dsRead = verify?.let { nullable(it, "ds1") }
                    if (tc != null && (tcRead == null || abs(tcRead!! - tc) > 0.01)) error = "تأیید TC ناموفق بود (اختلاف بیش از ۰٫۰۱°C)"
                    if (ds != null && (dsRead == null || abs(dsRead!! - ds) > 0.01)) error = "تأیید DS1 ناموفق بود (اختلاف بیش از ۰٫۰۱°C)"
                }
            } catch (e: Exception) { error = e.message ?: "ارتباط برقرار نشد" }
            val err = error; val rt = tcRead; val rd = dsRead
            main.post {
                if (!valid(id)) return@post
                pending = false
                if (err == null) {
                    if (rt != null) lastTc = rt; if (rd != null) lastDs = rd
                    tcActual.text = "دستگاه: ${rt?.let(::short) ?: "--"} °C"; dsActual.text = "دستگاه: ${rd?.let(::short) ?: "--"} °C"
                    if (tc != null) prefs.edit().putString("tcSet", short(tc)).apply()
                    if (ds != null) prefs.edit().putString("dsSet", short(ds)).apply()
                    apply.isEnabled = true; message("تنظیمات اعمال و با بازخوانی دستگاه تأیید شد", true)
                } else { apply.isEnabled = connected; message("خطا در اعمال تنظیمات: $err", false) }
            }
        }
    }

    private fun nullable(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k, Double.NaN).takeIf { it.isFinite() }
    private fun showTemp(v: TextView, n: Double?) { v.text = n?.let { String.format(Locale.US, "%.2f", it) } ?: "--"; v.setTextColor(if (n == null) muted else cyan); v.contentDescription = "دما: ${n?.let { "%.2f درجه سانتی‌گراد".format(Locale.US, it) } ?: "داده موجود نیست"}" }
    private fun relay(v: TextView, n: Int) { val state = when (n) { 1 -> "روشن"; 0 -> "خاموش"; else -> "نامشخص" }; v.text = when (n) { 1 -> "● روشن"; 0 -> "○ خاموش"; else -> "◌ نامشخص" }; v.setTextColor(if(n==1) green else if(n==0) white else muted); v.background = round(if(n==1) 0xFF103B30.toInt() else 0xFF102338.toInt(), 20); v.contentDescription = "وضعیت رله: $state" }
    private fun stale() { for (v in listOf(tcValue, ds1Value, ds2Value)) v.setTextColor(muted); relay(tcRelay, -1); relay(dsRelay, -1); inputs.forEach { it.setTextColor(muted); it.background = round(0xFF102338.toInt(), 12) } }
    private fun getJson(host: String, path: String): JSONObject = JSONObject(request(host, path))
    private fun request(host: String, path: String): String {
        val url = URL("http://$host$path"); val network = wifiNetwork()
        val c = if (network != null) network.openConnection(url) as HttpURLConnection else url.openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 5000; c.readTimeout = 5000
        try { val code = c.responseCode; val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $body"); return body
        } finally { c.disconnect() }
    }
    private fun wifiNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }
    private fun hostOrNull(input: String): String? {
        val s = input.trim(); if (s.isEmpty() || s.length > 253 || s.contains(Regex("[\\s/?#@\\\\]")) || s.contains("://")) return null
        val m = Regex("^([^:]+)(?::([0-9]{1,5}))?$").matchEntire(s) ?: return null
        val h = m.groupValues[1]; val port = m.groupValues[2]
        if (port.isNotEmpty() && (port.toIntOrNull() ?: return null) !in 1..65535) return null
        if (h.matches(Regex("[0-9.]+"))) { val a=h.split('.'); if(a.size!=4 || a.any { it.toIntOrNull()?.let { n -> n !in 0..255 } != false }) return null }
        else if (h.split('.').any { it.isEmpty() || it.length>63 || !it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) }) return null
        return h + if (port.isNotEmpty()) ":$port" else ""
    }
    private fun parse(s: String): Double? = try { s.trim().map { when(it) { in '۰'..'۹' -> '0'+(it-'۰'); in '٠'..'٩' -> '0'+(it-'٠'); '٫' -> '.'; '−' -> '-'; '٬' -> ','; else -> it } }.joinToString("").replace(",", "").toDouble() } catch (_: Exception) { null }
    private fun fmt(n: Double) = String.format(Locale.US, "%.2f", n)
    private fun short(n: Double) = String.format(Locale.US, "%.1f", n)
    private fun message(s: String, ok: Boolean) { status.text = s; status.setTextColor(if (ok) green else 0xFFFF8D8D.toInt()) }
    private fun setChip(ok: Boolean, s: String) { chip.text = (if(ok) "●  " else "◉  ") + s; chip.setTextColor(if(ok) green else muted); chip.background = round(if(ok) 0xFF103B30.toInt() else panel, 25); chip.contentDescription = "وضعیت اتصال: $s" }
    private fun round(color: Int, radius: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun card() = col().apply { background = round(panel, 19); setPadding(dp(14), dp(14), dp(14), dp(14)); elevation = dp(2).toFloat(); clipToOutline = true }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(s: String, size: Int, bold: Boolean, color: Int, gravityValue: Int = Gravity.START) = TextView(this).apply { text=s; textSize=size.toFloat(); setTextColor(color); gravity=gravityValue; if(bold) setTypeface(typeface, Typeface.BOLD) }
    private fun button(s: String, primary: Boolean) = Button(this).apply { text=s; isAllCaps=false; textSize=13f; minHeight=dp(48); minimumHeight=dp(48); setTextColor(if(primary) 0xFF06232C.toInt() else white); background=round(if(primary) cyan else 0xFF1D3851.toInt(), 13); setPadding(dp(8), dp(5), dp(8), dp(5)); stateListAnimator=null }
    private fun section(s: String, sub: String) = col().apply { setPadding(dp(2), dp(17), dp(2), dp(7)); addView(label(s, 17, true, white)); addView(label(sub, 12, false, muted)) }
    private fun match(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { if(top>0) topMargin=dp(top) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onDestroy() { destroyed=true; requested=false; token++; io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy() }
}
