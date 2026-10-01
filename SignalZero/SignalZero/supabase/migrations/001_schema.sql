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
