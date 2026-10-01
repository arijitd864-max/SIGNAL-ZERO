# Testing
**Automated (JVM):** `./gradlew testDebugUnitTest` — relay rules (TTL, hops, size, expiry, duplicates, loop prevention), packet round-trip, signature scope.
**Database:** run `supabase/tests/rls_smoke_test.sql` (policy audit + cross-user isolation checks).
**Recommended to add (needs devices):** instrumented tests for Tink encrypt/decrypt/sign round-trip, Room DAOs, WorkManager sync.

## Manual 4-phone test
1. Install the APK on phones A–D, create 4 accounts, make A↔D friends (QR). Keep everyone online once so public keys upload.
2. Nearby: A and D near each other, both airplane mode + Bluetooth + Wi-Fi on, Nearby Chat ON → chat. Expect connected + Delivered ticks.
3. Relay: A airplane mode; B airplane mode (Nearby ON); C online (Nearby ON); D online. A→D message. Watch A: Relaying → Uploaded → Delivered.
4. Gap: turn C off. Message stays "Waiting for relay"/stored on B. Turn C on (or B online) → continues.
5. Expiry: set a short lifetime in `MeshPacket.DEFAULT_LIFETIME_MS` for testing; confirm Expired, no loop.
6. SOS offline: airplane mode, press SOS, cancel test, then real test. Check History shows "pending sync"; disable airplane mode and confirm it flips to synced and recipient sees it.
7. Check-in: arm 30-min timer (change to 1 min in code for testing) and let it expire.
8. Permissions: deny each one and confirm graceful messages, SOS still saved.
