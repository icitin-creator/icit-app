# ICIT Dhampur – Android App

Ye app ICIT ki website (`app/src/main/assets/www/index.html`) ko phone par app ki tarah chalata hai.
Data wahi Firebase database (`icit-website`) se aata hai, isliye website aur app dono me same students, courses, notices dikhenge.

## APK kaise banayein

### Tarika 1: Android Studio (computer par)
1. Android Studio install karein: https://developer.android.com/studio
2. Is zip ko extract karein, phir Android Studio me **File > Open** se `ICIT-App` folder kholein.
3. Pehli baar Gradle sync hone dein (internet chahiye, 5–10 minute lag sakte hain).
4. **Build > Build App Bundle(s) / APK(s) > Build APK(s)** dabayein.
5. APK yahan milega: `app/build/outputs/apk/debug/app-debug.apk` – ise phone par bhej kar install karein
   (phone me "Unknown apps install" ki permission deni hogi).

### Tarika 2: GitHub se (bina Android Studio ke)
1. GitHub par ek naya repository banayein aur is folder ki saari files upload karein.
2. Repository ke **Actions** tab me "Build APK" apne aap chalega (5–8 minute).
3. Kaam pura hone par usi run me neeche **ICIT-Dhampur-APK** download karein – usme APK hai.

### Play Store par dalne ke liye
Android Studio me **Build > Generate Signed App Bundle / APK** se signed `.aab` banayein aur Google Play Console par upload karein.
Keystore file aur password sambhal kar rakhein – agle update ke liye wahi chahiye.

## Website badalne par app update karna
1. Nayi `index.html` ko `app/src/main/assets/www/index.html` par replace karein.
2. `app/build.gradle` me `versionCode` 1 badha dein (jaise 1 → 2) aur `versionName` bhi (1.0 → 1.1).
3. Dobara APK banayein.

Students, fees, notices jaisa data Firebase me hai – uske liye app update ki zarurat nahi.

## App me kya-kya kaam karta hai
- Poori website: courses, notices, student zone, certificate verification, online exam, admin panel
- Admin login (Firebase email/password)
- Photo, Excel/CSV aur backup file upload
- Excel/CSV download → phone ke **Downloads** folder me save hota hai
- WhatsApp, Telegram, Google Maps, phone/email links apni-apni app me khulte hain
- Back button: pehle khula popup band karta hai; exam ke beech app band nahi hone deta
