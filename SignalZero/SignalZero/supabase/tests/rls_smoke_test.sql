-- RLS smoke test. Run in the SQL editor AFTER creating two test users via the app (replace UUIDs).
-- Each block impersonates a user; the "must be 0 rows" checks prove isolation.
-- begin;
--   set local role authenticated;
--   select set_config('request.jwt.claims', '{"sub":"<USER_A_UUID>","role":"authenticated"}', true);
--   select count(*) from public.notes where user_id = '<USER_B_UUID>';              -- must be 0
--   select count(*) from public.emergency_contacts where user_id = '<USER_B_UUID>'; -- must be 0
--   select count(*) from public.sos_history where user_id = '<USER_B_UUID>';        -- must be 0
--   select count(*) from public.message_packets
--     where sender_id <> '<USER_A_UUID>' and receiver_id <> '<USER_A_UUID>';        -- must be 0
-- rollback;
-- Policy audit: no private table may use "using (true)":
select tablename, policyname, qual from pg_policies
 where schemaname = 'public' and qual = 'true';   -- only public_keys.pk_select is expected
