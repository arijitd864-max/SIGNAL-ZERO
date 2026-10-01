# Security model
- **No home-made crypto.** Google Tink: ECIES (P-256 ECDH + HKDF + AES-128-GCM) for E2EE, Ed25519 for signatures. Private keysets are encrypted by an Android Keystore master key.
- **Binding:** ciphertext context = `packetId|sender|receiver`; packet signature covers id, parties, type, timestamps and payload. TTL/hop count are mutable by design and are excluded.
- **Relays** see: packet id, sender id, receiver id, type, timestamps, TTL/hops, ciphertext length. They cannot read, alter (signature) or redirect (signature + context) content.
- **Replay/duplicates:** unique `packet_id` (server PK) + local seen-set; expiry (48 h default); hop cap 8; size cap 16 KB; store cap 500.
- **Supabase:** RLS on every table; no `using (true)` on private data (only `public_keys`, public by design); relay uploads only through a validating, rate-limited RPC; private storage buckets with per-user folders + SOS-recipient read policy.
- **Secrets:** only the public anon key ships in the app (via local.properties). `service_role` must never be in the app or repo.
- **Known gaps:** no safety-number/key-change verification UI yet; metadata (who talks to whom) is visible to Supabase; Nearby peers can see your rotating tag; no certificate pinning; local Room DB is not encrypted (protected by Android sandbox — add SQLCipher for stronger at-rest protection); rooted devices can read app data.
- **Rotate your anon key** from the Supabase dashboard if it was ever shared publicly in chat (it is low-risk, RLS protects data, but rotating is free).
