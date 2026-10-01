# SignalZero

**Communication When the Network Doesn't.**

An offline-first Android app for nearby chat, store-and-forward mesh messaging, and SOS emergencies.
Idea: **STORE → RELAY → SYNCHRONIZE**. Built with Kotlin, Jetpack Compose and Supabase.

> **Status:** written but not yet compiled by the author. On your first build you may need to fix small library-version errors. See [Troubleshooting](#7-troubleshooting).

---

## What you need

| Item | Notes |
|---|---|
| Windows / Mac / Linux computer | 8 GB RAM or more is best |
| [Android Studio](https://developer.android.com/studio) (latest) | Installs the Android SDK and JDK for you |
| A free [Supabase](https://supabase.com) project | You already have one |
| 1 Android phone (Android 8+) | 2 phones to test chat, 4 to test the full mesh demo |
| A USB cable | To install the app on your phone |

---

## 1. Set up Supabase (once, about 5 minutes)

1. Open your Supabase project and click **SQL Editor** → **New query**.
2. Open the file `supabase/all_in_one.sql` from this project, copy everything, paste it, and click **Run**.
   You should see "Success". (It creates all tables, security rules and storage buckets.)
3. Go to **Authentication → Providers** and make sure **Email** is ON.
   - For easy testing, turn **Confirm email** OFF.
     If you leave it ON, each new user must click the link in their email before logging in.
4. Go to **Project Settings → API** and copy two values:
   - **Project URL**, for example `https://lsrlyhensaiwfbfphgjr.supabase.co`
   - **anon public** key. Copy it as **one single line with no spaces**.

> **Never** use the `service_role` key in this app or on GitHub.

---

## 2. Add your keys to the project

In the project's main folder (the same folder as `settings.gradle.kts`) create a file named **`local.properties`**:

```properties
sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https://lsrlyhensaiwfbfphgjr.supabase.co
SUPABASE_ANON_KEY=PASTE_YOUR_ANON_KEY_HERE_ON_ONE_LINE
```

- Android Studio normally creates the `sdk.dir` line for you when you open the project. If it already exists, just add the two Supabase lines under it.
- This file is in `.gitignore`, so it will **not** be uploaded to GitHub.

---

## 3. Open and build the project

1. Start **Android Studio** → **Open** → choose the `SignalZero` folder.
2. Wait for **Gradle sync** to finish (bottom bar). The first time can take 5–10 minutes.
3. If Android Studio asks to install SDK parts or create the Gradle wrapper, click **Yes / Install**.

---

## 4. How to SEE and run your app

### Option A: On your real phone (recommended, everything works)

1. On the phone: **Settings → About phone** → tap **Build number** 7 times to unlock Developer options.
2. **Settings → Developer options** → turn on **USB debugging**.
3. Plug the phone into the computer with USB and tap **Allow** on the phone.
4. In Android Studio, pick your phone in the device drop-down at the top, then press the green **▶ Run** button.
5. The app opens on your phone. Sign up, log in, and you will see the **SIGNALZERO** dashboard.

### Option B: Android emulator (screens only)

- **Tools → Device Manager → Create device** → pick a Pixel with a recent Android version → **▶ Run**.
- Login, dashboard, notes, contacts and the SOS screens work.
- **Bluetooth / Nearby chat and mesh do NOT work in an emulator.** Use real phones for those.

### Option C: Install the APK on any phone

1. In Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
2. Click **locate** in the popup. The file is `app/build/outputs/apk/debug/app-debug.apk`.
3. Send the file to your phone (WhatsApp, Telegram, Drive or USB), open it, and allow **Install unknown apps** when asked.

Command-line version: `./gradlew assembleDebug`

---

## 5. Try the features

1. **Sign up** two accounts on two phones (different emails). Each user gets a unique ID such as `SZ7K92P` (see **Profile**).
2. **Friends → My QR** on one phone, **Friends → Add → Scan QR** on the other. Accept the request on the first phone.
3. **Keep both phones online once** so their encryption keys upload to Supabase.
4. **Chat:** open the friend and send a message (works online through Supabase).
5. **Nearby Chat:** turn on airplane mode, then switch Bluetooth and Wi-Fi back ON. Open **Nearby Chat**, switch scanning ON and allow permissions. Phones within about 10 to 50 m find each other. Then chat.
6. **SOS:** **Emergency Contacts** → add a contact (add their SignalZero ID so they receive it). Then **SOS** → tap the button → wait for or cancel the countdown. Check **SOS History**.
7. **Mesh demo:** follow the **Mesh Demo** screen in the app, or `TESTING.md`.
8. **Safe Check-In:** on the SOS screen, pick 30, 60 or 120 minutes. If you do not tap "I'm safe", SOS fires automatically.

---

## 6. Upload to GitHub

1. Create an account at [github.com](https://github.com), then **New repository** (name it `signalzero`).
2. In a terminal inside the project folder:

```bash
git init
git add .
git commit -m "SignalZero first version"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/signalzero.git
git push -u origin main
```

(You can also drag the files into GitHub with the **Upload files** button, but do **not** upload `local.properties`.)

3. **Optional, to build the APK on GitHub automatically:** repository → **Settings → Secrets and variables → Actions → New repository secret**. Add:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`

   Then open the **Actions** tab. After a build finishes, download the APK from **Artifacts**.

> GitHub only **stores your code**. It does not run an Android app or host it like a website. Users install the APK (or you publish on Google Play later).

---

## 7. Troubleshooting

| Problem | Fix |
|---|---|
| App says "Supabase is not configured" | `SUPABASE_ANON_KEY` is missing in `local.properties`. Add it and rebuild. |
| Sign up works but login says "Email not confirmed" | Turn off **Confirm email** in Supabase, or click the link in the email. |
| "No keys for SZxxxx yet" when sending | Both users must open the app online once so keys upload. |
| Gradle sync fails / red errors | Use **File → Sync Project with Gradle Files**. Make sure JDK 17 is used (**Settings → Build → Gradle → Gradle JDK**). If an error remains, copy the **first** error line and ask for help. |
| Nearby finds no devices | Use real phones, turn ON Bluetooth, Wi-Fi and Location, allow all permissions, keep both apps open. |
| SOS has no location | Turn on GPS and allow Location. The app will show "last known" if live GPS isn't available. |
| Messages stay "Waiting for relay" | No other phone is in range and nobody has internet yet. This is correct: the message is safely stored. |

---

## 8. Project layout

```
SignalZero/
├── app/src/main/java/com/signalzero/
│   ├── mesh/        packet format, relay rules, Nearby transport, mesh engine
│   ├── security/    Tink-based end-to-end encryption and signatures
│   ├── sync/        SyncManager (uploads/downloads with Supabase)
│   ├── sos/         SOS pipeline, foreground service, power-button trigger
│   ├── location/    GPS (live or labelled last-known)
│   ├── audio/ video/ SOS recording
│   ├── data/        Room database
│   ├── network/     Supabase client + DTOs
│   ├── worker/      WorkManager sync and Safe Check-In
│   └── ui/          Jetpack Compose screens
├── supabase/        SQL migrations + all_in_one.sql + tests
├── ARCHITECTURE.md  SECURITY.md  TESTING.md
└── .github/workflows/android.yml
```

---

## 9. Honest limitations

- Bluetooth/Wi-Fi range is short (about 10–100 m per hop). The mesh only works if other SignalZero phones are in between.
- Mesh delivery is best-effort and labelled **experimental**. "Delivered" shows only after the receiver confirms.
- **5× power-button SOS** is best-effort on Android. The big on-screen SOS button is the reliable way.
- **SOS video** starts only while the app is on screen (Android camera rules). Audio runs in the background.
- SOS does **not** call emergency services (112 / 100 / 911). Never rely on it as your only lifeline.
- Push notifications while the app is closed need Firebase (roadmap).
- Android only, and it needs Google Play services.

## 10. Roadmap

Firebase push notifications · image and file messages · group chats · safety-number key verification · encrypted local database (SQLCipher) · Google Play release.

## Security

See `SECURITY.md`. Only the public anon key is used in the app. Your data is protected by Supabase Row Level Security and end-to-end encryption. If you ever shared your anon key publicly, you can rotate it in Supabase for free.
