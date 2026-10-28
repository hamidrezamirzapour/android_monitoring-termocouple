#include <WiFi.h>
#include <WebServer.h>
#include <SPI.h>
#include <OneWire.h>
#include <DallasTemperature.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ILI9341.h>
#include <MAX6675.h>
#include <math.h>
#include <stdlib.h>

// ---------------- Wi-Fi Config ----------------
const char* AP_SSID = "MICROTESLA";
const char* AP_PASS = "123456789";
const uint8_t AP_CHANNEL = 6;

bool apStarted = false;
bool apSecured = false;

// ---------------- Inputs (7 Pins) ----------------
// کلیدها بین پین و GND متصل شوند (فشردن کلید = LOW)
const uint8_t INPUT_PINS[] = {15, 2, 0, 4, 16, 17, 5};
const uint8_t NUM_INPUTS = sizeof(INPUT_PINS) / sizeof(INPUT_PINS[0]);

// ---------------- DS18B20 Pins ----------------
const uint8_t DS1_PIN = 1;  // GPIO1 (TX)
const uint8_t DS2_PIN = 3;  // GPIO3 (RX)

OneWire oneWireDS1(DS1_PIN);
OneWire oneWireDS2(DS2_PIN);
DallasTemperature ds1(&oneWireDS1);
DallasTemperature ds2(&oneWireDS2);

float ds1C = NAN;
float ds2C = NAN;
bool ds1Valid = false;
bool ds2Valid = false;

bool dsConversionPending = false;
unsigned long dsConversionStarted = 0;
const unsigned long DS_CONVERSION_MS = 500;

// ---------------- MAX6675 (با کتابخانه MAX6675.h) ----------------
const uint8_t MAX_CS_PIN  = 32;
const uint8_t MAX_SO_PIN  = 19;
const uint8_t MAX_SCK_PIN = 18;

// ترتیب آرگومان‌ها در کتابخانه MAX6675: CS, SO (MISO), SCK (SCLK)
MAX6675 thermocouple(MAX_CS_PIN, MAX_SO_PIN, MAX_SCK_PIN);

float tcC = NAN;
bool tcValid = false;

// ---------------- TFT ILI9341 Pins ----------------
const uint8_t TFT_CS_PIN   = 23;
const uint8_t TFT_RST_PIN  = 22;
const uint8_t TFT_DC_PIN   = 21;
const uint8_t TFT_MOSI_PIN = 13;
const uint8_t TFT_SCK_PIN  = 14;

Adafruit_ILI9341 tft(&SPI, TFT_CS_PIN, TFT_DC_PIN, TFT_RST_PIN);

// ---------------- Outputs / Relays ----------------
const uint8_t OUT_TC_PIN = 25;  // خروجی رله کنترل ترموکوپل
const uint8_t OUT_DS_PIN = 26;  // خروجی رله کنترل سنسور DS اول

// اگر رله شما با سطح صفر فعال می‌شود true بگذارید
const bool RELAY_ACTIVE_LOW = false;

bool outTC = false;
bool outDS = false;

// ---------------- Temperature Control ----------------
const float HYSTERESIS_C = 1.0;
float setpointTC = NAN;
float setpointDS = NAN;
bool hasSetpointTC = false;
bool hasSetpointDS = false;

// ---------------- Web Server ----------------
WebServer server(80);

// ---------------- Timers ----------------
unsigned long lastTCRead = 0;
unsigned long lastDisplayUpdate = 0;
const unsigned long TC_READ_INTERVAL_MS = 300;
const unsigned long DISPLAY_INTERVAL_MS = 1000;

// ---------------- Output Control Functions ----------------
void writeRelayPin(uint8_t pin, bool on) {
  if (RELAY_ACTIVE_LOW) {
    digitalWrite(pin, on ? LOW : HIGH);
  } else {
    digitalWrite(pin, on ? HIGH : LOW);
  }
}

void setTCOutput(bool on) {
  outTC = on;
  writeRelayPin(OUT_TC_PIN, on);
}

void setDSOutput(bool on) {
  outDS = on;
  writeRelayPin(OUT_DS_PIN, on);
}

void updateTemperatureOutputs() {
  // کنترل رله ترموکوپل
  if (!tcValid || !hasSetpointTC) {
    setTCOutput(false);
  } else if (!outTC && tcC <= setpointTC - HYSTERESIS_C) {
    setTCOutput(true);
  } else if (outTC && tcC >= setpointTC) {
    setTCOutput(false);
  }

  // کنترل رله DS18B20 شماره ۱
  if (!ds1Valid || !hasSetpointDS) {
    setDSOutput(false);
  } else if (!outDS && ds1C <= setpointDS - HYSTERESIS_C) {
    setDSOutput(true);
  } else if (outDS && ds1C >= setpointDS) {
    setDSOutput(false);
  }
}

// ---------------- DS18B20 Service ----------------
void startDSConversion() {
  ds1.requestTemperatures();
  ds2.requestTemperatures();
  dsConversionStarted = millis();
  dsConversionPending = true;
}

bool isValidDS(float val) {
  return isfinite(val) && val != DEVICE_DISCONNECTED_C && val >= -55.0 && val <= 125.0;
}

void serviceDSConversion() {
  if (!dsConversionPending) return;
  if (millis() - dsConversionStarted < DS_CONVERSION_MS) return;

  float t1 = ds1.getTempCByIndex(0);
  float t2 = ds2.getTempCByIndex(0);

  ds1Valid = isValidDS(t1);
  ds2Valid = isValidDS(t2);

  ds1C = ds1Valid ? t1 : NAN;
  ds2C = ds2Valid ? t2 : NAN;

  dsConversionPending = false;
  updateTemperatureOutputs();
  startDSConversion();
}

// ---------------- MAX6675 Service ----------------
void serviceThermocouple() {
  if (millis() - lastTCRead < TC_READ_INTERVAL_MS) return;
  lastTCRead = millis();

  // در کتابخانه MAX6675 خواندن وضعیت با read() انجام می‌شود
  int status = thermocouple.read();
  
  // وضعیت STATUS_OK یعنی اتصال سنسور سالم است
  if (status == STATUS_OK) {
    float temp = thermocouple.getTemperature();
    if (isfinite(temp) && temp >= 0.0 && temp <= 1024.0) {
      tcC = temp;
      tcValid = true;
    } else {
      tcValid = false;
      tcC = NAN;
    }
  } else {
    tcValid = false;
    tcC = NAN;
  }

  updateTemperatureOutputs();
}

// ---------------- Web Server Handlers ----------------
String jsonNumber(float val, bool valid) {
  if (!valid || !isfinite(val)) return "null";
  return String(val, 2);
}

void handleData() {
  String json;
  json.reserve(400);

  json  = "{";
  json += "\"tc_C\":" + jsonNumber(tcC, tcValid) + ",";
  json += "\"ds1_C\":" + jsonNumber(ds1C, ds1Valid) + ",";
  json += "\"ds2_C\":" + jsonNumber(ds2C, ds2Valid) + ",";

  json += "\"inputs\":[";
  for (uint8_t i = 0; i < NUM_INPUTS; i++) {
    if (i > 0) json += ",";
    json += (digitalRead(INPUT_PINS[i]) == LOW) ? "1" : "0";
  }
  json += "],";

  json += "\"input_pins\":[15,2,0,4,16,17,5],";

  json += "\"outputs\":{";
  json += "\"tc\":";
  json += outTC ? "1" : "0";
  json += ",\"ds1\":";
  json += outDS ? "1" : "0";
  json += "},";

  json += "\"setpoints_C\":{";
  json += "\"tc\":" + jsonNumber(setpointTC, hasSetpointTC) + ",";
  json += "\"ds1\":" + jsonNumber(setpointDS, hasSetpointDS);
  json += "},";

  json += "\"ap\":{";
  json += "\"ssid\":\"";
  json += AP_SSID;
  json += "\",\"ip\":\"";
  json += WiFi.softAPIP().toString();
  json += "\",\"secure\":";
  json += apSecured ? "true" : "false";
  json += "}}";

  server.sendHeader("Cache-Control", "no-store");
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json; charset=utf-8", json);
}

bool parseFloatValue(const String& text, float& val) {
  const char* start = text.c_str();
  char* end = nullptr;
  val = strtof(start, &end);
  return (end != start && *end == '\0' && isfinite(val));
}

void handleSet() {
  bool hasTCArg = server.hasArg("tc");
  bool hasDSArg = server.hasArg("ds");

  if (!hasTCArg && !hasDSArg) {
    server.send(400, "application/json", "{\"ok\":false,\"error\":\"Use /set?tc=VAL&ds=VAL\"}");
    return;
  }

  float newTC = setpointTC;
  float newDS = setpointDS;

  if (hasTCArg) {
    if (!parseFloatValue(server.arg("tc"), newTC) || newTC < 0.0 || newTC > 1024.0) {
      server.send(400, "application/json", "{\"ok\":false,\"error\":\"tc must be 0-1024\"}");
      return;
    }
  }

  if (hasDSArg) {
    if (!parseFloatValue(server.arg("ds"), newDS) || newDS < -55.0 || newDS > 125.0) {
      server.send(400, "application/json", "{\"ok\":false,\"error\":\"ds must be -55 to 125\"}");
      return;
    }
  }

  if (hasTCArg) {
    setpointTC = newTC;
    hasSetpointTC = true;
  }
  if (hasDSArg) {
    setpointDS = newDS;
    hasSetpointDS = true;
  }

  updateTemperatureOutputs();

  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(200, "application/json", "{\"ok\":true,\"message\":\"setpoint updated\"}");
}

// صفحه HTML برای تست سریع با مرورگر گوشی
const char INDEX_HTML[] PROGMEM = R"rawliteral(
<!doctype html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta charset="utf-8">
  <title>MICROTESLA</title>
  <style>
    body { font-family: sans-serif; max-width: 500px; margin: 20px auto; padding: 0 14px; background: #f7f9fa; }
    .card { background: white; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-bottom: 15px; }
    pre { background: #2d3748; color: #68d391; padding: 12px; border-radius: 6px; overflow-wrap: anywhere; }
    input, button { font-size: 16px; padding: 10px; margin: 6px 0; width: 100%; box-sizing: border-box; }
    button { background: #3182ce; color: white; border: none; border-radius: 4px; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h2>کنترل پنل MICROTESLA</h2>
    <pre id="data">در حال ارتباط...</pre>
  </div>
  <div class="card">
    <h3>تنظیم دما</h3>
    <form id="form">
      <label>هدف ترموکوپل MAX6675 (°C):</label>
      <input id="tc" type="number" step="0.1" min="0" max="1024" placeholder="مثلا 120">
      <label>هدف سنسور اول DS18B20 (°C):</label>
      <input id="ds" type="number" step="0.1" min="-55" max="125" placeholder="مثلا 45">
      <button type="submit">ذخیره تنظیمات</button>
    </form>
    <p id="result"></p>
  </div>
  <script>
    async function update() {
      try {
        const res = await fetch('/data');
        const json = await res.json();
        document.getElementById('data').textContent = JSON.stringify(json, null, 2);
      } catch (e) {
        document.getElementById('data').textContent = 'عدم ارتباط با ESP32';
      }
    }
    document.getElementById('form').onsubmit = async (e) => {
      e.preventDefault();
      const tc = document.getElementById('tc').value;
      const ds = document.getElementById('ds').value;
      const p = new URLSearchParams();
      if (tc !== '') p.set('tc', tc);
      if (ds !== '') p.set('ds', ds);
      const res = await fetch('/set?' + p.toString());
      document.getElementById('result').textContent = await res.text();
      update();
    };
    setInterval(update, 1000);
    update();
  </script>
</body>
</html>
)rawliteral";

void handleRoot() {
  server.send_P(200, "text/html; charset=utf-8", INDEX_HTML);
}

void handleNotFound() {
  server.send(404, "text/plain", "Not Found");
}

// ---------------- TFT Display Update ----------------
void updateDisplay() {
  if (millis() - lastDisplayUpdate < DISPLAY_INTERVAL_MS) return;
  lastDisplayUpdate = millis();

  tft.fillScreen(ILI9341_BLACK);
  tft.setTextSize(2);

  tft.setCursor(0, 0);
  tft.setTextColor(ILI9341_CYAN);
  tft.print("WiFi: ");
  tft.println(AP_SSID);

  tft.setTextColor(ILI9341_WHITE);
  tft.print("IP: ");
  tft.println(WiFi.softAPIP());

  tft.setTextColor(ILI9341_YELLOW);
  tft.print("TC: ");
  if (tcValid) tft.print(tcC, 1);
  else tft.print("--");
  tft.print(" / ");
  if (hasSetpointTC) tft.println(setpointTC, 1);
  else tft.println("--");

  tft.setTextColor(ILI9341_GREEN);
  tft.print("DS1: ");
  if (ds1Valid) tft.print(ds1C, 1);
  else tft.print("--");
  tft.print(" / ");
  if (hasSetpointDS) tft.println(setpointDS, 1);
  else tft.println("--");

  tft.setTextColor(ILI9341_WHITE);
  tft.print("DS2: ");
  if (ds2Valid) { tft.print(ds2C, 1); tft.println(" C"); }
  else { tft.println("--"); }

  tft.print("OUT TC:");
  tft.print(outTC ? "ON" : "OFF");
  tft.print(" DS:");
  tft.println(outDS ? "ON" : "OFF");

  tft.print("Inputs: ");
  for (uint8_t i = 0; i < NUM_INPUTS; i++) {
    tft.print(digitalRead(INPUT_PINS[i]) == LOW ? "1" : "0");
  }
  tft.println();
}

// ---------------- Wi-Fi AP Initialization ----------------
void startAccessPoint() {
  WiFi.mode(WIFI_OFF);
  delay(300);

  WiFi.mode(WIFI_AP);
  delay(300);

  // تلاش برای ساخت شبکه با پسورد
  apStarted = WiFi.softAP(AP_SSID, AP_PASS, AP_CHANNEL, false, 4);
  apSecured = apStarted;

  // اگر هسته با پسورد Fail کرد، خودکار شبکه باز می‌سازد تا بی ارتباط نمانید
  if (!apStarted) {
    WiFi.softAPdisconnect(true);
    delay(200);
    WiFi.mode(WIFI_OFF);
    delay(200);
    WiFi.mode(WIFI_AP);
    delay(200);
    apStarted = WiFi.softAP(AP_SSID);
    apSecured = false;
  }
}

// ---------------- Setup ----------------
void setup() {
  // ۱. مقداردهی خروجی رله‌ها (ابتدا خاموش)
  digitalWrite(OUT_TC_PIN, RELAY_ACTIVE_LOW ? HIGH : LOW);
  pinMode(OUT_TC_PIN, OUTPUT);

  digitalWrite(OUT_DS_PIN, RELAY_ACTIVE_LOW ? HIGH : LOW);
  pinMode(OUT_DS_PIN, OUTPUT);

  setTCOutput(false);
  setDSOutput(false);

  // ۲. ورودی‌های دیجیتال
  for (uint8_t i = 0; i < NUM_INPUTS; i++) {
    pinMode(INPUT_PINS[i], INPUT_PULLUP);
  }

  // ۳. استارت Wi-Fi Access Point
  startAccessPoint();

  // ۴. راه‌اندازی MAX6675
  thermocouple.begin();

  // ۵. راه‌اندازی DS18B20
  ds1.begin();
  ds2.begin();
  ds1.setResolution(11);
  ds2.setResolution(11);
  ds1.setWaitForConversion(false);
  ds2.setWaitForConversion(false);
  startDSConversion();

  // ۶. راه‌اندازی نمایشگر TFT با کلاک SCK روی GPIO14 و دیتای MOSI روی GPIO13
  SPI.begin(TFT_SCK_PIN, -1, TFT_MOSI_PIN, TFT_CS_PIN);
  tft.begin();
  tft.setRotation(1);
  tft.fillScreen(ILI9341_BLACK);

  // ۷. راه‌اندازی وب‌سرور برای اپلیکیشن اندروید و مرورگر
  server.on("/", HTTP_GET, handleRoot);
  server.on("/data", HTTP_GET, handleData);
  server.on("/set", HTTP_GET, handleSet);
  server.onNotFound(handleNotFound);
  server.begin();

  updateDisplay();
}

// ---------------- Loop ----------------
void loop() {
  server.handleClient();
  serviceDSConversion();
  serviceThermocouple();
  updateDisplay();
  delay(2);
}
