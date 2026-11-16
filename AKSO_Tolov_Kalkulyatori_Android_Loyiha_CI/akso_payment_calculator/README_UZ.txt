AKSO BO‘LIB TO‘LASH KALKULYATORI — ANDROID LOYIHASI
===================================================

Ilova imkoniyatlari:
- Mahsulot summasini kiritish.
- Oldindan to‘lovni kiritish va mahsulot summasidan avtomatik ayirish.
- 3 oy: 0% ustama.
- 6 oy: 18% ustama.
- 9 oy: 27% ustama.
- 12 oy: 36% ustama.
- Oylik to‘lov, muddatli to‘lov jami va oldindan to‘lov bilan yakuniy jami.
- Natijalarni nusxalash.
- Internet talab qilmaydi.

HISOBLASH TARTIBI
-----------------
Qolgan summa = Mahsulot summasi - Oldindan to‘lov.
Muddatli to‘lov jami = Qolgan summa × (1 + ustama foizi).
Oylik to‘lov = Muddatli to‘lov jami / oylar soni.
Yakuniy jami = Oldindan to‘lov + Muddatli to‘lov jami.

ANDROID STUDIO ORQALI APK YIG‘ISH
---------------------------------
1. Kompyuterga Android Studio o‘rnating.
2. ZIP arxivni oching.
3. Android Studio'da File > Open orqali ochilgan
   `akso_payment_calculator` papkasini tanlang.
4. Gradle Sync tugashini kuting. Loyiha Gradle 8.9 va Android Gradle Plugin
   8.7.3 dan foydalanadi. Android SDK Platform 35 o‘rnatilishi so‘ralishi mumkin.
5. Build > Build Bundle(s) / APK(s) > Build APK(s) ni tanlang.
6. Tayyor APK odatda quyidagi joyda bo‘ladi:
   app/build/outputs/apk/debug/app-debug.apk

Ilova sozlamalari:
- Ilova nomi: AKSO To‘lov
- Application ID: uz.akso.kalkulyator
- Minimal Android: 6.0 (API 23)
- Compile/Target SDK: 35
- Rang: AKSO yashil (#0BB34B)

ESLATMA
-------
Ushbu arxiv Android Studio'da yig‘ish uchun tayyor native Android loyiha
manba kodidir. Ish muhiti Android SDK va Gradle'ni yuklab ola olmagani sababli
APK binar fayli bu arxiv ichida mavjud emas.

GITHUB ACTIONS ORQALI APK OLISH
-------------------------------
Loyihada `.github/workflows/build-apk.yml` avtomatik yig‘ish fayli ham bor.
Loyihani GitHub repozitoriyasiga `main` yoki `master` tarmog‘iga yuborilgach,
Actions bo‘limida “Build AKSO To'lov APK” jarayoni ishga tushadi. Jarayon
muvaffaqiyatli tugasa, Actions ishga tushirilgan sahifasidagi Artifacts
qismidan `AKSO-Tolov-APK` arxivini yuklab olib, ichidagi `app-debug.apk`
faylini Android telefonga o‘rnatish mumkin.

APKNI YIG‘ISH HAQIDA
-------------------
Ushbu chat ish muhitida Android SDK/Gradle o‘rnatilmagan va tashqi yuklab olish
manbalariga ulanish mavjud emas. Shu sabab bu yerda APK binar faylini hozir
kompilyatsiya qilishning imkoni bo‘lmadi. GitHub Actions fayli esa loyihani
GitHub serverida Android SDK bilan yig‘ish uchun qo‘shildi.

ILOVANI AVTOMATIK YANGILASH
--------------------------
Ilovada GitHub Releases orqali yangilanish tekshiruvi mavjud. Ilova ishga
tushganda internet orqali eng so'nggi ommaviy AKSO Finance release tekshiriladi.
Yangi versiya topilsa, "YANGILANISH MAVJUD" oynasi chiqadi.

Sotuvchining yangilash tartibi:
1. "HOZIR YANGILASH" tugmasini bosing.
2. APK yuklab olinadi. Yuklab olish tugagach, Android bildirishnomasini bosing.
3. Birinchi yangilashda Android "Ushbu manbadan ruxsat berish"ni so'rashi mumkin.
   AKSO Finance uchun shu ruxsatni yoqing.
4. Android ko'rsatgan o'rnatish oynasida "Yangilash"ni bosing.

YANGI VERSIYANI SOTUVCHILARGA TARQATISH
--------------------------------------
1. app/build.gradle ichidagi versionCode qiymatini har bir yangi release'da
   1 taga oshiring. versionName qiymatini ham yangilang.
   Misol: versionCode 3, versionName '1.2'.
2. O'zgarishlarni feature/wasyt-seller-workflow branchiga yuboring.
3. GitHub > Actions > "Publish AKSO Finance Update" jarayonini tanlang.
4. "Run workflow" tugmasida aynan feature/wasyt-seller-workflow branchini tanlang.
5. Sotuvchilarga ko'rinadigan release izohini yozib, jarayonni ishga tushiring.
6. Jarayon APK'ni GitHub Releases'ga AKSO-Finance.apk nomi bilan joylaydi.
   Ilovaning o'zi yangi release'ni topadi va sotuvchilarga yangilanishni bildiradi.

MUHIM:
- Dastlabki avtomatik yangilashni ishga tushiruvchi APKni sotuvchilarga bir marta
  qo'lda o'rnatish kerak. Shundan keyingi versiyalar ilova ichidan olinadi.
- Har bir yangilanish versionCode bo'yicha yuqoriroq bo'lishi shart.
- Release APK bir xil CI imzolash kaliti bilan yig'ilishi kerak. Publish workflow
  build-apk.yml bilan bir xil saqlangan CI debug imzolash kalitidan foydalanadi.
- Android xavfsizlik siyosati bo'yicha ilova APKni avtomatik, tasdiqsiz o'rnata
  olmaydi. Yuklab olingan faylni sotuvchi Android o'rnatish oynasida tasdiqlaydi.
