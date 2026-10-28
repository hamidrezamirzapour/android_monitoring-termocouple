# BUILD_VALIDATION

این سند بررسی‌های ایستا را ثبت می‌کند. در محیط اجرای این پروژه Android SDK/Gradle و Arduino toolchain موجود نیست؛ **هیچ compile گرفته نشده است** و هیچ ادعایی دربارهٔ ساخت APK، آپلود firmware یا آزمون سخت‌افزاری مطرح نمی‌شود.

## بررسی‌های انجام‌شده

- XML فایل‌های `AndroidManifest.xml` و `styles.xml` با XML parser خوانده شد؛ مجوزهای `INTERNET` و `ACCESS_NETWORK_STATE` موجود و `usesCleartextTraffic="true"` برای HTTP محلی برقرار است. Manifest تغییر نکرده است.
- تنظیمات نسخه بررسی شد: minSdk 23، targetSdk 35، Kotlin 2.0.21، AGP 8.6.1، Gradle 8.7 و Java 17. فایل‌های Gradle و workflow تغییر نکرده‌اند.
- **تطابق با اسکیمای JSON جدید firmware (`MicroTeslaControl`)** به‌صورت ایستا بررسی شد: خواندن `tc_C`, `ds1_C`, `ds2_C` به‌صورت null-safe (نمایش `--` برای null)، `outputs.tc/outputs.ds1` با `optJSONObject` و نگاشت ۱→«روشن» و ۰→«خاموش»، آرایهٔ `inputs` با `optJSONArray` nullable و برچسب کلیدهای ۱..۷ (۱ = فشرده/LOW)، `setpoints_C.tc/ds1` با عنوان «مقدار تنظیم‌شده در دستگاه» و نمایش `ap.ssid/ip/secure` در زیرنویس.
- **مسیریابی Wi‑Fi:** polling `/data` هر ~۱٫۵ ثانیه پس از پایان درخواست قبلی، مقید به شبکه با `TRANSPORT_WIFI` از طریق `ConnectivityManager`؛ در نبود شبکهٔ Wi‑Fi پیام راهنمای فارسی نشان داده می‌شود و تلاش روی اتصال پیش‌فرض ادامه می‌یابد.
- **جریان /set:** ارسال فقط پارامترهای غیرخالی با URL-encoding، اعتبارسنجی بازه (TC 0–1024، DS ‎−۵۵..۱۲۵) با پیام فارسی، پشتیبانی ارقام فارسی/عربی، پردازش HTTP 400 و `ok:false` با نمایش متن `error` firmware، و readback `/data` با tolerance ۰٫۰۱ روی `setpoints_C` پیش از پیام «تنظیمات اعمال و تأیید شد». ذخیرهٔ Preferences فقط پس از تأیید انجام می‌شود.
- **Dirty-guard:** فیلدهای ویرایش فقط از اولین پاسخ موفق `/data` و فقط در صورت خالی بودن مقدار می‌گیرند؛ پاسخ‌های بعدی بازنویسی نمی‌کنند.
- اعتبارسنجی نشانی (IPv4/hostname با پورت اختیاری، بدون مسیر/query/userinfo) و دکمهٔ اتصال/توقف با توقف همیشه‌مجاز و cleanup در `onDestroy` بازبینی شد.
- از APIهای حساس Kotlin با احتیاط ایستا استفاده شده: `setSingleLine(true)`، بدون smart-cast نامطمئن روی nullableها، و بررسی صریح null در `optJSONObject`/`optJSONArray`.
- مسیر Arduino جدید `firmware/MicroTeslaControl/MicroTeslaControl.ino` با محتوای دقیق sketch کاربر جایگزین شد و پوشهٔ قدیمی `MicroTesla_ESP32_Hysteresis` حذف شد.
- **اسکیمای جدید /set و /data در firmware مدل‌سازی و بررسی شد:** پارامترهای اختیاری `tc`/`ds` با الزام حداقل یکی، خطای 400 با `{"ok":false,"error":...}`، موفقیت 200 با `{"ok":true,"message":"setpoint updated"}`، هیسترزیس ثابت ۱°C (بدون پارامتر hys)، بدون ماندگاری setpoint پس از ری‌بوت، و جهت هیتر: روشن در `temp <= set − 1.0`، خاموش در `temp >= set`. مدل مستقل منطق برای set=100 در دماهای 99، 100، 98 به ترتیب خروجی ON، OFF، ON داد (مطابق شرح قرارداد).
- ساختار ZIP به‌صورت بازگشتی همراه dotfiles (شامل `.github`) تولید و سپس با `ZipFile.testzip()` و مقایسهٔ اعضا با فایل‌های پروژه آزموده شد.

## محدودیت آزمون

Static checks صحت ترجمهٔ Kotlin/C++ یا سازگاری دقیق کتابخانه‌های Arduino را اثبات نمی‌کنند. **در این محیط هیچ compile برای Kotlin یا Arduino اجرا نشده است.** برای نتیجهٔ اجرایی، workflow GitHub/Gradle باید APK را بسازد و firmware باید با کتابخانه‌های نصب‌شده در Arduino IDE برای برد هدف کامپایل و سپس روی سخت‌افزار واقعی آزموده شود. رفتار relay فیزیکی و قطع Wi‑Fi توسط سازندهٔ گوشی نیز اینجا قابل‌آزمایش نبود.
