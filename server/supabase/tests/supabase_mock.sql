-- Imitation minimale des schémas Supabase (auth, storage, rôles) pour tester la migration sur un PostgreSQL ordinaire.
create role anon nologin; create role authenticated nologin;
create schema auth; create schema storage; create schema extensions;
create table auth.users (id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
create table storage.buckets (id text primary key, name text, public boolean, file_size_limit bigint);
create table storage.objects (id uuid default gen_random_uuid(), bucket_id text, name text);
create function storage.foldername(name text) returns text[] language sql as $$ select (string_to_array(name, '/'))[1:array_length(string_to_array(name,'/'),1)-1] $$;
grant usage on schema public, auth, storage, extensions to anon, authenticated;
grant execute on all functions in schema auth to anon, authenticated;
