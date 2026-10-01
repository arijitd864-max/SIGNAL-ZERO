-- SignalZero 001: schema. Run in Supabase SQL Editor (or `supabase db push`).
create extension if not exists pgcrypto;

-- ---------- helpers ----------
create or replace function public.set_updated_at() returns trigger language plpgsql as $$
begin new.updated_at = now(); return new; end $$;

-- Unique SignalZero ID: "SZ" + 5 chars (no ambiguous letters)
create or replace function public.gen_sz_id() returns text language plpgsql as $$
declare chars text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; res text; i int;
begin
  loop
    res := 'SZ';
    for i in 1..5 loop res := res || substr(chars, 1 + floor(random()*length(chars))::int, 1); end loop;
    exit when not exists (select 1 from public.profiles where sz_id = res);
  end loop;
  return res;
end $$;

-- ---------- profiles ----------
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  sz_id text not null unique check (sz_id ~ '^SZ[A-Z0-9]{5}$'),
  username text not null unique check (char_length(username) between 3 and 30),
  full_name text not null check (char_length(full_name) between 1 and 80),
  phone text check (phone is null or char_length(phone) <= 20),
  avatar_path text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create trigger trg_profiles_updated before update on public.profiles
  for each row execute function public.set_updated_at();

-- auto-create profile on sign-up
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare uname text;
begin
  uname := coalesce(nullif(new.raw_user_meta_data->>'username',''), 'user' || substr(replace(new.id::text,'-',''),1,8));
  if exists (select 1 from public.profiles where username = uname) then
    uname := uname || substr(replace(new.id::text,'-',''),1,4);
  end if;
  insert into public.profiles (id, sz_id, username, full_name)
  values (new.id, public.gen_sz_id(), uname, coalesce(nullif(new.raw_user_meta_data->>'full_name',''), uname));
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- friends ----------
create table public.friend_requests (
  id uuid primary key default gen_random_uuid(),
  from_id uuid not null references public.profiles(id) on delete cascade,
  to_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted','rejected')),
  created_at timestamptz not null default now(),
  check (from_id <> to_id),
  unique (from_id, to_id)
);
create index on public.friend_requests (to_id, status);

create table public.friends (
  user_id uuid not null references public.profiles(id) on delete cascade,
  friend_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, friend_id),
  check (user_id <> friend_id)
);

-- ---------- devices & keys ----------
create table public.devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  name text check (char_length(name) <= 60),
  platform text not null default 'android',
  last_seen timestamptz not null default now(),
  created_at timestamptz not null default now()
);
create index on public.devices (user_id);

-- Public keys are PUBLIC by design (needed to encrypt to / verify a user).
create table public.public_keys (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  encryption_keyset text not null check (char_length(encryption_keyset) < 4096),
  signing_keyset text not null check (char_length(signing_keyset) < 4096),
  fingerprint text not null,
  updated_at timestamptz not null default now()
);

create table public.device_keys (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  fingerprint text not null,
  created_at timestamptz not null default now(),
  unique (device_id, fingerprint)
);

-- ---------- conversations / messages (online path) ----------
create table public.conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null default 'direct' check (kind in ('direct')),
  created_by uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table public.conversation_members (
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);
create index on public.conversation_members (user_id);

-- Ciphertext only. The server can never read plaintext.
create table public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  ciphertext text not null check (char_length(ciphertext) <= 20000),
  created_at timestamptz not null default now()
);
create index on public.messages (conversation_id, created_at);

-- ---------- mesh packets ----------
create table public.message_packets (
  packet_id text primary key check (char_length(packet_id) between 16 and 64),
  sender_id uuid not null references public.profiles(id) on delete cascade,
  receiver_id uuid not null references public.profiles(id) on delete cascade,
  message_type text not null default 'MSG' check (message_type in ('MSG','ACK','SOS')),
  encrypted_payload text not null check (char_length(encrypted_payload) <= 20000),
  signature text not null,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  ttl int not null check (ttl between 0 and 16),
  hop_count int not null default 0 check (hop_count between 0 and 16),
  status text not null default 'uploaded' check (status in ('uploaded','delivered','expired')),
  uploaded_by uuid references auth.users(id),
  uploaded_at timestamptz not null default now(),
  delivered_at timestamptz
);
create index on public.message_packets (receiver_id, status);
create index on public.message_packets (sender_id);

create table public.relay_packets (
  id uuid primary key default gen_random_uuid(),
  packet_id text not null references public.message_packets(packet_id) on delete cascade,
  relay_user_id uuid not null references auth.users(id) on delete cascade,
  hop_count int not null,
  uploaded_at timestamptz not null default now()
);
create index on public.relay_packets (relay_user_id, uploaded_at);

create table public.delivery_receipts (
  id uuid primary key default gen_random_uuid(),
  packet_id text not null references public.message_packets(packet_id) on delete cascade,
  receiver_id uuid not null references public.profiles(id) on delete cascade,
  status text not null check (status in ('delivered','read')),
  created_at timestamptz not null default now(),
  unique (packet_id, receiver_id, status)
);

-- ---------- emergency ----------
create table public.emergency_contacts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  name text not null check (char_length(name) between 1 and 80),
  phone text check (char_length(phone) <= 20),
  email text check (char_length(email) <= 120),
  sz_id text check (sz_id is null or sz_id ~ '^SZ[A-Z0-9]{5}$'),
  priority int not null default 1 check (priority between 1 and 10),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on public.emergency_contacts (user_id);
create trigger trg_ec_updated before update on public.emergency_contacts
  for each row execute function public.set_updated_at();

create table public.sos_history (
  id uuid primary key,                       -- generated on device so offline works
  user_id uuid not null references public.profiles(id) on delete cascade,
  message text not null check (char_length(message) <= 1000),
  status text not null default 'active' check (status in ('active','cancelled','resolved')),
  created_at timestamptz not null,
  resolved_at timestamptz,
  synced_at timestamptz not null default now()
);
create index on public.sos_history (user_id, created_at desc);

create table public.sos_recipients (
  sos_id uuid not null references public.sos_history(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  primary key (sos_id, recipient_id)
);
create index on public.sos_recipients (recipient_id);

create table public.sos_locations (
  id uuid primary key default gen_random_uuid(),
  sos_id uuid not null references public.sos_history(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  accuracy_m real,
  provider text,
  is_last_known boolean not null default false,
  captured_at timestamptz not null
);
create index on public.sos_locations (sos_id, captured_at);

create table public.recordings (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  sos_id uuid references public.sos_history(id) on delete set null,
  storage_path text not null,
  duration_ms bigint not null default 0,
  size_bytes bigint not null default 0 check (size_bytes <= 104857600),
  created_at timestamptz not null default now()
);
create table public.videos (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  sos_id uuid references public.sos_history(id) on delete set null,
  storage_path text not null,
  duration_ms bigint not null default 0,
  size_bytes bigint not null default 0 check (size_bytes <= 524288000),
  created_at timestamptz not null default now()
);
create index on public.recordings (user_id);
create index on public.videos (user_id);

create table public.notes (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  title text not null default '' check (char_length(title) <= 200),
  body text not null default '' check (char_length(body) <= 20000),
  deleted boolean not null default false,
  updated_at timestamptz not null default now()
);
create index on public.notes (user_id, updated_at desc);

create table public.sync_queue (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  kind text not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'pending' check (status in ('pending','done','failed')),
  attempts int not null default 0,
  created_at timestamptz not null default now()
);
create index on public.sync_queue (user_id, status);

create table public.notification_tokens (
  token text primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  platform text not null default 'android',
  created_at timestamptz not null default now()
);

-- UPGRADE: safe check-in (dead-man's switch) state mirrored for emergency contacts
create table public.safe_checkins (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  due_at timestamptz not null,
  note text check (char_length(note) <= 300),
  status text not null default 'armed' check (status in ('armed','confirmed','triggered')),
  created_at timestamptz not null default now()
);
-- SignalZero 002: RPC functions, Row Level Security, realtime.

-- ---------- RPC (security definer, narrow) ----------
-- Look up someone by SignalZero ID (exposes only non-sensitive fields).
create or replace function public.lookup_profile(sz text)
returns table (id uuid, sz_id text, username text, full_name text)
language sql security definer set search_path = public stable as $$
  select p.id, p.sz_id, p.username, p.full_name from public.profiles p
  where p.sz_id = upper(trim(sz)) and auth.uid() is not null limit 1 $$;


-- Name of someone who sent YOU a pending friend request (nothing else is exposed).
create or replace function public.lookup_profile_by_id(pid uuid)
returns table (id uuid, sz_id text, username text, full_name text)
language sql security definer set search_path = public stable as $$
  select p.id, p.sz_id, p.username, p.full_name from public.profiles p
  where p.id = pid and exists (select 1 from public.friend_requests r
        where r.from_id = pid and r.to_id = auth.uid() and r.status = 'pending') $$;

create or replace function public.send_friend_request(target_sz text) returns uuid
language plpgsql security definer set search_path = public as $$
declare tid uuid; rid uuid;
begin
  select id into tid from profiles where sz_id = upper(trim(target_sz));
  if tid is null then raise exception 'SignalZero ID not found'; end if;
  if tid = auth.uid() then raise exception 'Cannot add yourself'; end if;
  if exists (select 1 from friends where user_id = auth.uid() and friend_id = tid) then
    raise exception 'Already friends'; end if;
  insert into friend_requests(from_id, to_id) values (auth.uid(), tid)
    on conflict (from_id, to_id) do update set status = 'pending' returning id into rid;
  return rid;
end $$;

create or replace function public.respond_friend_request(req_id uuid, accept boolean) returns void
language plpgsql security definer set search_path = public as $$
declare r friend_requests;
begin
  select * into r from friend_requests where id = req_id and to_id = auth.uid() and status = 'pending';
  if r.id is null then raise exception 'Request not found'; end if;
  update friend_requests set status = case when accept then 'accepted' else 'rejected' end where id = req_id;
  if accept then
    insert into friends(user_id, friend_id) values (r.from_id, r.to_id), (r.to_id, r.from_id) on conflict do nothing;
  end if;
end $$;

create or replace function public.remove_friend(fid uuid) returns void
language sql security definer set search_path = public as $$
  delete from friends where (user_id = auth.uid() and friend_id = fid) or (user_id = fid and friend_id = auth.uid());
  delete from friend_requests where (from_id = auth.uid() and to_id = fid) or (from_id = fid and to_id = auth.uid());
$$;

-- Relay upload. ANY authenticated phone may upload an encrypted packet it carries.
-- Validation: size, expiry, TTL, rate limit (120 uploads/min per user), dedupe by packet_id.
create or replace function public.submit_packet(
  p_packet_id text, p_sender uuid, p_receiver uuid, p_type text, p_payload text, p_signature text,
  p_created timestamptz, p_expires timestamptz, p_ttl int, p_hop int
) returns text
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'auth required'; end if;
  if p_expires <= now() then return 'expired'; end if;
  if p_ttl < 0 or p_ttl > 16 or p_hop < 0 or p_hop > 16 then raise exception 'bad ttl/hop'; end if;
  if char_length(p_payload) > 20000 then raise exception 'payload too large'; end if;
  if p_type not in ('MSG','ACK','SOS') then raise exception 'bad type'; end if;
  if (select count(*) from relay_packets where relay_user_id = auth.uid() and uploaded_at > now() - interval '1 minute') >= 120 then
    raise exception 'rate limited'; end if;
  insert into message_packets(packet_id, sender_id, receiver_id, message_type, encrypted_payload, signature,
                              created_at, expires_at, ttl, hop_count, uploaded_by)
  values (p_packet_id, p_sender, p_receiver, p_type, p_payload, p_signature, p_created, p_expires, p_ttl, p_hop, auth.uid())
  on conflict (packet_id) do nothing;
  insert into relay_packets(packet_id, relay_user_id, hop_count) values (p_packet_id, auth.uid(), p_hop);
  return 'uploaded';
end $$;

create or replace function public.ack_packet(p_packet_id text) returns void
language plpgsql security definer set search_path = public as $$
begin
  update message_packets set status = 'delivered', delivered_at = now()
   where packet_id = p_packet_id and receiver_id = auth.uid();
  if found then
    insert into delivery_receipts(packet_id, receiver_id, status) values (p_packet_id, auth.uid(), 'delivered')
    on conflict do nothing;
  end if;
end $$;

-- Resolve SignalZero IDs to user IDs (for SOS recipients)
create or replace function public.resolve_sz_ids(ids text[]) returns table (id uuid, sz_id text)
language sql security definer set search_path = public stable as $$
  select p.id, p.sz_id from profiles p where p.sz_id = any (select upper(trim(x)) from unnest(ids) x) $$;

-- Housekeeping: expire old packets (schedule with pg_cron if enabled)
create or replace function public.expire_packets() returns void language sql security definer as $$
  update public.message_packets set status = 'expired' where status = 'uploaded' and expires_at < now();
  delete from public.message_packets where expires_at < now() - interval '7 days';
$$;

-- Admin-safe system stats: aggregate counts only, no content.
create or replace view public.system_stats as
  select (select count(*) from public.profiles) as users,
         (select count(*) from public.message_packets) as packets,
         (select count(*) from public.message_packets where status='delivered') as delivered,
         (select count(distinct relay_user_id) from public.relay_packets) as relays,
         (select count(*) from public.sos_history) as sos_events;
revoke all on public.system_stats from anon, authenticated;

-- ---------- RLS ----------
do $$ declare t text; begin
  foreach t in array array['profiles','friend_requests','friends','devices','device_keys','public_keys','conversations',
    'conversation_members','messages','message_packets','relay_packets','delivery_receipts','emergency_contacts',
    'sos_history','sos_recipients','sos_locations','recordings','videos','notes','sync_queue','notification_tokens','safe_checkins']
  loop execute format('alter table public.%I enable row level security', t); end loop; end $$;

-- profiles: self or friends can read; only self can update. Insert is done by trigger.
create policy profiles_select on public.profiles for select to authenticated
  using (id = auth.uid() or exists (select 1 from public.friends f where f.user_id = auth.uid() and f.friend_id = profiles.id));
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- friend_requests: both parties can read; writes go through RPC
create policy fr_select on public.friend_requests for select to authenticated
  using (from_id = auth.uid() or to_id = auth.uid());
-- friends: own rows readable; writes only via RPC
create policy friends_select on public.friends for select to authenticated using (user_id = auth.uid());

-- devices / device_keys: own
create policy devices_all on public.devices for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy device_keys_all on public.device_keys for all to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- public_keys: readable by any signed-in user (public by design), writable only by owner
create policy pk_select on public.public_keys for select to authenticated using (true);
create policy pk_insert on public.public_keys for insert to authenticated with check (user_id = auth.uid());
create policy pk_update on public.public_keys for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- conversations: members only
create or replace function public.is_member(cid uuid) returns boolean
language sql security definer set search_path = public stable as $$
  select exists (select 1 from conversation_members where conversation_id = cid and user_id = auth.uid()) $$;
create policy conv_select on public.conversations for select to authenticated using (public.is_member(id));
create policy conv_insert on public.conversations for insert to authenticated with check (created_by = auth.uid());
create policy cm_select on public.conversation_members for select to authenticated using (public.is_member(conversation_id));
create policy cm_insert on public.conversation_members for insert to authenticated
  with check (user_id = auth.uid() or exists (select 1 from public.conversations c where c.id = conversation_id and c.created_by = auth.uid()));
create policy msg_select on public.messages for select to authenticated using (public.is_member(conversation_id));
create policy msg_insert on public.messages for insert to authenticated
  with check (sender_id = auth.uid() and public.is_member(conversation_id));

-- mesh packets: only sender or receiver can read. Relays cannot read what they carried.
create policy mp_select on public.message_packets for select to authenticated
  using (sender_id = auth.uid() or receiver_id = auth.uid());
create policy relay_select on public.relay_packets for select to authenticated using (relay_user_id = auth.uid());
create policy dr_select on public.delivery_receipts for select to authenticated
  using (receiver_id = auth.uid() or exists (select 1 from public.message_packets m where m.packet_id = delivery_receipts.packet_id and m.sender_id = auth.uid()));

-- emergency contacts, notes, queue, tokens, checkins: owner only
create policy ec_all on public.emergency_contacts for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy notes_all on public.notes for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy sq_all on public.sync_queue for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy nt_all on public.notification_tokens for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy sc_all on public.safe_checkins for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- SOS: owner full access; chosen recipients can read (and only read)
create policy sos_owner on public.sos_history for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy sos_recipient_read on public.sos_history for select to authenticated
  using (exists (select 1 from public.sos_recipients r where r.sos_id = sos_history.id and r.recipient_id = auth.uid()));
create policy sosr_owner on public.sos_recipients for all to authenticated
  using (exists (select 1 from public.sos_history s where s.id = sos_id and s.user_id = auth.uid()))
  with check (exists (select 1 from public.sos_history s where s.id = sos_id and s.user_id = auth.uid()));
create policy sosr_self_read on public.sos_recipients for select to authenticated using (recipient_id = auth.uid());
create policy sosl_owner on public.sos_locations for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy sosl_recipient_read on public.sos_locations for select to authenticated
  using (exists (select 1 from public.sos_recipients r where r.sos_id = sos_locations.sos_id and r.recipient_id = auth.uid()));
create policy rec_owner on public.recordings for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy vid_owner on public.videos for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy rec_recipient_read on public.recordings for select to authenticated
  using (exists (select 1 from public.sos_recipients r where r.sos_id = recordings.sos_id and r.recipient_id = auth.uid()));
create policy vid_recipient_read on public.videos for select to authenticated
  using (exists (select 1 from public.sos_recipients r where r.sos_id = videos.sos_id and r.recipient_id = auth.uid()));

-- ---------- grants: functions callable by signed-in users only ----------
revoke execute on all functions in schema public from public, anon;
grant execute on function public.lookup_profile(text), public.lookup_profile_by_id(uuid), public.send_friend_request(text),
  public.respond_friend_request(uuid, boolean), public.remove_friend(uuid),
  public.submit_packet(text,uuid,uuid,text,text,text,timestamptz,timestamptz,int,int),
  public.ack_packet(text), public.resolve_sz_ids(text[]), public.is_member(uuid) to authenticated;

-- ---------- realtime (never required for offline operation) ----------
alter publication supabase_realtime add table public.message_packets, public.friend_requests,
  public.delivery_receipts, public.sos_history, public.friends;
-- SignalZero 003: private storage buckets + policies.
-- Object path convention:  {user_id}/{sos_id_or_name}/{file}
insert into storage.buckets (id, name, public, file_size_limit) values
  ('recordings',     'recordings',     false, 104857600),
  ('videos',         'videos',         false, 524288000),
  ('profile-images', 'profile-images', false, 5242880),
  ('chat-files',     'chat-files',     false, 20971520)
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit;

-- owner full access to own folder in every bucket
create policy "sz_owner_rw" on storage.objects for all to authenticated
  using (bucket_id in ('recordings','videos','profile-images','chat-files')
         and (storage.foldername(name))[1] = auth.uid()::text)
  with check (bucket_id in ('recordings','videos','profile-images','chat-files')
         and (storage.foldername(name))[1] = auth.uid()::text);

-- friends can read each other's profile images
create policy "sz_friend_avatar_read" on storage.objects for select to authenticated
  using (bucket_id = 'profile-images'
         and exists (select 1 from public.friends f
                     where f.user_id = auth.uid() and f.friend_id::text = (storage.foldername(name))[1]));

-- SOS recipients can read SOS media for that SOS id only (path: uid/sos_id/file)
create policy "sz_sos_recipient_read" on storage.objects for select to authenticated
  using (bucket_id in ('recordings','videos')
         and exists (select 1 from public.sos_recipients r
                     where r.recipient_id = auth.uid()
                       and r.sos_id::text = (storage.foldername(name))[2]));
