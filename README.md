# قطب‌نما

قطب‌نمای دقیق با جهت قبله، برای اندروید.
هم روی GitHub Pages اجرا می‌شود (و نصب‌شدنی است)، هم با GitHub Actions به APK تبدیل می‌شود.

## راه‌اندازی در Termux

```
unzip -o ~/storage/downloads/compass-app.zip -d ~
cd ~/compass-app
git init -b main
git add .
git commit -m "Compass app"
gh repo create compass --public --source=.
gh api -X POST repos/{owner}/{repo}/pages -f build_type=workflow
git push -u origin main
```

اگر دستور `gh api` خطا داد: در گیت‌هاب برو به Settings، بخش Pages، و Source را روی **GitHub Actions** بگذار. بعد در تب Actions روی workflow با نام Deploy to GitHub Pages گزینه Run workflow را بزن.

آدرس سایت: `https://USERNAME.github.io/compass/`

## APK

بعد از هر push روی شاخه main، workflow با نام Build Android APK اجرا می‌شود (حدود ۵ تا ۸ دقیقه).
فایل را از Actions، روی آخرین اجرا، بخش Artifacts و نام compass-apk بردار. داخلش `compass.apk` است.
برای نصب باید «نصب از منابع ناشناس» را برای مرورگر یا فایل‌منیجر روشن کنی.

برای ساخت نسخه Release با فایل APK ضمیمه:

```
git tag v1.0.0
git push origin v1.0.0
```

این APK با کلید debug امضا می‌شود و برای نصب شخصی کافی است. برای Google Play باید نسخه امضاشده با کلید خودت بسازی.

## تست روی خود گوشی (Termux)

```
cd ~/compass-app/www
python -m http.server 8000
```

در Chrome همان گوشی آدرس `http://localhost:8000` را باز کن. سنسورها روی localhost کار می‌کنند.

## ساختار

- `www/` خود اپ (index.html، style.css، app.js، math.js، sw.js، manifest و آیکون‌ها)
- `.github/workflows/pages.yml` انتشار روی GitHub Pages
- `.github/workflows/android.yml` ساخت APK
- `capacitor.config.json` و `package.json` تنظیمات ساخت APK

## نکات

- سنسور شمال مغناطیسی را نشان می‌دهد. برای شمال جغرافیایی و قبله دقیق‌تر، در تنظیمات اپ «انحراف مغناطیسی» منطقه‌ات را وارد کن (ایران حدود ۴ تا ۶ درجه شرقی).
- بعد از اولین بار، موقعیت ذخیره می‌شود و قبله بدون اینترنت هم کار می‌کند.
- اگر عقربه غیرمنطقی بود، گوشی را چند بار به شکل ۸ بچرخان.


## آسمان‌نما

از دکمهٔ ستاره در نوار بالای قطب‌نما باز می‌شود (`www/sky.html`). نقشهٔ زندهٔ آسمان با ستاره‌ها، صورت‌های فلکی،
خورشید، ماه، سیارات، راه شیری و جهت قبله. در حالت «رو به آسمان» با همان حسگر بومی قطب‌نما (`NativeCompass`) می‌چرخد.
موقعیت (`pos`) و انحراف مغناطیسی (`decl`) با قطب‌نما مشترک است. داده‌های ستاره‌ها در `www/sky-data.js` و محاسبات در `www/sky-core.js` هستند.
