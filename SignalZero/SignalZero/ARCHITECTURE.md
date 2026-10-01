# Architecture
```
UI (Compose) → AppViewModel → Room (single source of truth) ⇄ SyncManager ⇄ Supabase
                                  ↑                    ↑
                         MeshEngine ⇄ NearbyTransport   WorkManager + network callback + Realtime
```
- `mesh/` MeshPacket + RelayRules (pure, tested), NearbyTransport (Nearby Connections), MeshEngine (store→relay), MeshService (foreground).
- `security/crypto/` Tink wrapper. `sync/SyncManager` is the only class that talks to Supabase for data movement.
- `sos/` SosManager (pipeline) + SosService (audio, power-button). `location/` fresh-fix-or-labelled-last-known.
- Flow A→B→C(online)→Supabase→D: A stores+signs+encrypts → B/C store & forward (ttl-1, hop+1) → C `submit_packet` RPC → D pulls/Realtime → verify → decrypt → encrypted ACK back through the same paths.
- Delivery states: Queued → Relaying / Waiting for relay → Uploaded → Delivered (only on ACK); Expired / Failed.
