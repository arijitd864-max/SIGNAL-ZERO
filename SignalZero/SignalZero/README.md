# SignalZero — *Communication When the Network Doesn't.*

An offline-first communication and emergency system for Android (Kotlin + Jetpack Compose + Supabase).
Core idea: **STORE → RELAY → SYNCHRONIZE**.

| System | What it does |
|---|---|
| 💬 Nearby Chat | Encrypted messages between phones in radio range (Google Nearby Connections: Bluetooth/BLE/Wi-Fi). |
| 📡 Offline mesh | Store-and-forward: encrypted packets hop phone → phone; any phone with internet uploads to Supabase, which delivers to the receiver. |
| 🆘 SOS | Separate from chat. Saved locally first (location, message, audio, video), sent to chosen contacts, synced when a link exists. |

## Free messaging
No paid SMS/push gateway is needed: online delivery uses **Supabase Postgres + Realtime** (free tier). Offline delivery uses the mesh.
(Push notifications while the app is closed would need Firebase Cloud Messaging — see Roadmap.)

## Upgrades included beyond the brief
- **Safe Check-In (dead-man's switch):** set 30/60/120 min; if you don't tap "I'm safe", SOS fires automatically.
- **Rotating nearby tags:** peers advertise a daily-rotating hash, not your ID, so scanners can't track you.
- **SOS recipients can see your SOS + media** (RLS + storage policies) — nobody else, not even admins.
- **Server-side rate limiting & validation** of relay uploads (`submit_packet` RPC), packet expiry cleanup, aggregate-only `system_stats`.
- **Encrypted ACKs** over the mesh so "Delivered" is only shown when the receiver truly confirmed.
- **GitHub Actions** workflow that builds the APK and runs tests; RLS audit SQL.

## 1. Supabase setup (10 min)
1. Open your project → **SQL Editor** → run, in order: `supabase/migrations/001_schema.sql`, `002_functions_rls.sql`, `003_storage.sql`
   (or paste `supabase/all_in_one.sql` once).
2. **Authentication → Providers:** Email enabled. (Optional) turn email confirmation on/off. Add `signalzero://auth` under redirect URLs if you use confirmation links.
3. **Project Settings → API:** copy the **anon public** key *as one single line*.
   The key in your brief had a space in the middle, so it was deliberately **not** embedded. **Never** use the `service_role` key.
4. Optional: enable `pg_cron` and schedule `select public.expire_packets();` hourly.

## 2. Android setup
1. Install Android Studio (Koala+) and JDK 17. Open the `SignalZero/` folder (VS Code can edit the files, but building/running needs Android Studio or the Gradle CLI + Android SDK).
2. Create `local.properties` in the project root (it is git-ignored):
   ```
   sdk.dir=/path/to/Android/sdk
   SUPABASE_URL=https://lsrlyhensaiwfbfphgjr.supabase.co
   SUPABASE_ANON_KEY=<your anon key, one line>
   ```
3. Android Studio will create the Gradle wrapper on first sync (or run `gradle wrapper --gradle-version 8.9`).
4. Run on **real phones** (Nearby/Bluetooth/GPS don't work in the emulator). Needs Google Play services.

## 3. Build an APK
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Release: create a keystore, configure `signingConfigs`, run `./gradlew assembleRelease` (never commit the keystore).

## 4. GitHub
```
git init && git add . && git commit -m "SignalZero 1.0"
git branch -M main && git remote add origin https://github.com/<you>/signalzero.git && git push -u origin main
```
Add repo secrets `SUPABASE_URL` and `SUPABASE_ANON_KEY` so the Actions workflow can build. Note: GitHub *hosts the code*; the app itself is an APK (or Play Store listing), not a web page.

## 5. Permissions (asked only when needed)
Nearby/Bluetooth + Location (Nearby Chat), Location + Microphone (SOS), Camera (QR/SOS video), Notifications (Android 13+).

## 6. Honest limitations
- Range is Bluetooth/Wi-Fi range (≈10–100 m per hop). The mesh only works if other SignalZero phones are physically in between.
- Background: Android/OEM battery managers can kill background work; the foreground service helps but doesn't guarantee it.
- **Power-button SOS** is best-effort (screen on/off toggle counting). No public Android API gives apps the power key. The on-screen button is the reliable path.
- **SOS video** only starts while the app is visible (Android camera background restrictions). Audio runs in the foreground service.
- Mesh status "Delivered" requires an encrypted ACK back from the receiver; otherwise you'll see Relaying / Waiting for relay / Uploaded.
- Nearby Connections needs Google Play services (no iOS, no de-Googled ROMs).
- SOS does **not** call emergency services (112/100/911). Don't rely on it as your only lifeline.
- First contact needs internet once: friends' public keys are exchanged through Supabase.
- This code was written without an Android SDK in the authoring environment, so it has **not been compiled here**. Expect to fix small API/version mismatches on first sync (esp. supabase-kt and CameraX). See TESTING.md.

## Roadmap
FCM push for closed-app notifications · image/file messages (chat-files bucket) · group chats · BLE-only fallback transport · Wi-Fi Aware · key-change warnings/safety numbers · emergency-contact SMS fallback (user-initiated) · iOS.
