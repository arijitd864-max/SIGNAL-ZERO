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
