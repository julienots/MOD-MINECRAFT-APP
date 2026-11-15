-- Bedrock Mod Studio — hébergement de serveurs Minecraft Bedrock
--
-- Supabase sert de « tableau de bord » : comptes, liste des serveurs, packs à installer, commandes
-- (démarrer, arrêter…), console et état en direct. Le serveur Minecraft lui-même tourne sur une machine
-- (VM gratuite, vieux PC…) où l'agent `bms_agent.py` est installé. Supabase ne peut pas exécuter un
-- serveur de jeu : ses fonctions s'arrêtent au bout de quelques secondes et n'ouvrent pas de port UDP.
--
-- Sécurité :
-- - chaque utilisateur ne voit et ne modifie que SES serveurs (RLS sur auth.uid()) ;
-- - l'agent n'a ni compte ni clé « service_role » : il s'identifie avec une clé propre au serveur,
--   dont seule l'empreinte SHA-256 est stockée, et ne passe que par les fonctions agent_* ci-dessous.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- Tables

create table if not exists public.bms_servers (
    id uuid primary key default gen_random_uuid(),
    owner uuid not null default auth.uid() references auth.users (id) on delete cascade,
    name text not null check (char_length(name) between 1 and 48),
    -- Réglages du monde (écrits dans server.properties par l'agent)
    gamemode text not null default 'survival' check (gamemode in ('survival', 'creative', 'adventure')),
    difficulty text not null default 'normal' check (difficulty in ('peaceful', 'easy', 'normal', 'hard')),
    max_players int not null default 5 check (max_players between 1 and 5),
    level_name text not null default 'Monde' check (char_length(level_name) between 1 and 32),
    level_seed text not null default '' check (char_length(level_seed) <= 32),
    view_distance int not null default 12 check (view_distance between 5 and 32),
    tick_distance int not null default 4 check (tick_distance between 4 and 12),
    allow_cheats boolean not null default false,
    beta_apis boolean not null default false,
    server_port int not null default 19132 check (server_port between 1024 and 65535),
    -- Empreinte de la clé de l'agent (la clé elle-même n'est jamais stockée)
    agent_key_hash text not null check (char_length(agent_key_hash) = 64),
    -- État publié par l'agent
    status text not null default 'not_installed'
        check (status in ('not_installed', 'offline', 'installing', 'starting', 'online', 'stopping', 'crashed', 'error')),
    status_message text not null default '',
    public_address text not null default '',
    bds_version text not null default '',
    players jsonb not null default '[]'::jsonb,
    metrics jsonb not null default '{}'::jsonb,
    backups jsonb not null default '[]'::jsonb,
    last_seen timestamptz,
    created_at timestamptz not null default now()
);
create index if not exists bms_servers_owner on public.bms_servers (owner);

create table if not exists public.bms_server_packs (
    id uuid primary key default gen_random_uuid(),
    server_id uuid not null references public.bms_servers (id) on delete cascade,
    name text not null check (char_length(name) between 1 and 80),
    kind text not null check (kind in ('behavior', 'resource')),
    -- Chemin dans le bucket privé « bms-packs » et lien signé (lecture seule, ce fichier uniquement)
    storage_path text not null,
    download_url text not null,
    size_bytes bigint not null default 0,
    position int not null default 0,
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);
create index if not exists bms_server_packs_server on public.bms_server_packs (server_id, position);

create table if not exists public.bms_server_commands (
    id bigint generated always as identity primary key,
    server_id uuid not null references public.bms_servers (id) on delete cascade,
    kind text not null check (kind in ('start', 'stop', 'restart', 'command', 'backup', 'sync_packs', 'update_bds', 'apply_settings')),
    payload text not null default '' check (char_length(payload) <= 500),
    state text not null default 'pending' check (state in ('pending', 'running', 'done', 'error')),
    result text not null default '',
    created_at timestamptz not null default now(),
    delivered_at timestamptz,
    finished_at timestamptz
);
create index if not exists bms_server_commands_pending on public.bms_server_commands (server_id, state, id);

create table if not exists public.bms_server_logs (
    id bigint generated always as identity primary key,
    server_id uuid not null references public.bms_servers (id) on delete cascade,
    line text not null,
    created_at timestamptz not null default now()
);
create index if not exists bms_server_logs_server on public.bms_server_logs (server_id, id desc);

-- ---------------------------------------------------------------- RLS (accès des utilisateurs de l'application)

alter table public.bms_servers enable row level security;
alter table public.bms_server_packs enable row level security;
alter table public.bms_server_commands enable row level security;
alter table public.bms_server_logs enable row level security;

drop policy if exists "servers: owner" on public.bms_servers;
create policy "servers: owner" on public.bms_servers
    for all to authenticated using (owner = (select auth.uid())) with check (owner = (select auth.uid()));

drop policy if exists "packs: owner" on public.bms_server_packs;
create policy "packs: owner" on public.bms_server_packs
    for all to authenticated
    using (exists (select 1 from public.bms_servers s where s.id = server_id and s.owner = (select auth.uid())))
    with check (exists (select 1 from public.bms_servers s where s.id = server_id and s.owner = (select auth.uid())));

drop policy if exists "commands: owner" on public.bms_server_commands;
create policy "commands: owner" on public.bms_server_commands
    for all to authenticated
    using (exists (select 1 from public.bms_servers s where s.id = server_id and s.owner = (select auth.uid())))
    with check (exists (select 1 from public.bms_servers s where s.id = server_id and s.owner = (select auth.uid())));

drop policy if exists "logs: owner reads" on public.bms_server_logs;
create policy "logs: owner reads" on public.bms_server_logs
    for select to authenticated
    using (exists (select 1 from public.bms_servers s where s.id = server_id and s.owner = (select auth.uid())));

-- L'état publié par l'agent ne doit pas être falsifiable depuis l'application.
create or replace function public.bms_protect_agent_columns() returns trigger
language plpgsql set search_path = '' as $$
begin
    if current_user in ('authenticated', 'anon') then
        new.status := old.status; new.status_message := old.status_message; new.public_address := old.public_address;
        new.bds_version := old.bds_version; new.players := old.players; new.metrics := old.metrics;
        new.backups := old.backups; new.last_seen := old.last_seen; new.owner := old.owner;
    end if;
    return new;
end $$;
drop trigger if exists bms_protect_agent_columns on public.bms_servers;
create trigger bms_protect_agent_columns before update on public.bms_servers
    for each row execute function public.bms_protect_agent_columns();

-- ---------------------------------------------------------------- Fonctions de l'agent (clé du serveur)

create or replace function public.bms_agent_check(p_server uuid, p_key text) returns public.bms_servers
language plpgsql security definer set search_path = '' as $$
declare s public.bms_servers;
begin
    select * into s from public.bms_servers where id = p_server;
    if not found or s.agent_key_hash <> encode(extensions.digest(coalesce(p_key, ''), 'sha256'), 'hex') then
        raise exception 'Clé d''agent invalide' using errcode = '28000';
    end if;
    return s;
end $$;
revoke all on function public.bms_agent_check(uuid, text) from public, anon, authenticated;

-- Configuration complète du serveur et liste des packs à installer.
create or replace function public.bms_agent_config(p_server uuid, p_key text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare s public.bms_servers;
begin
    s := public.bms_agent_check(p_server, p_key);
    return jsonb_build_object(
        'server', to_jsonb(s) - 'agent_key_hash' - 'owner',
        'packs', coalesce((select jsonb_agg(jsonb_build_object('id', p.id, 'name', p.name, 'kind', p.kind,
                    'url', p.download_url, 'size', p.size_bytes, 'position', p.position) order by p.position, p.created_at)
                from public.bms_server_packs p where p.server_id = p_server and p.enabled), '[]'::jsonb));
end $$;

-- Battement de cœur : état, joueurs, mesures. Renvoie les commandes en attente (marquées « running »).
create or replace function public.bms_agent_heartbeat(
    p_server uuid, p_key text, p_status text, p_message text, p_address text, p_version text,
    p_players jsonb, p_metrics jsonb, p_backups jsonb
) returns setof public.bms_server_commands
language plpgsql security definer set search_path = '' as $$
begin
    perform public.bms_agent_check(p_server, p_key);
    update public.bms_servers set
        status = case when p_status in ('offline', 'installing', 'starting', 'online', 'stopping', 'crashed', 'error') then p_status else status end,
        status_message = left(coalesce(p_message, ''), 300), public_address = left(coalesce(p_address, ''), 100),
        bds_version = left(coalesce(p_version, ''), 40), players = coalesce(p_players, '[]'::jsonb),
        metrics = coalesce(p_metrics, '{}'::jsonb), backups = coalesce(p_backups, backups), last_seen = now()
    where id = p_server;
    return query
        -- Une commande remise mais jamais terminée (réponse perdue, agent redémarré) est remise de nouveau après 10 minutes.
        update public.bms_server_commands set state = 'running', delivered_at = now()
        where id in (select c.id from public.bms_server_commands c where c.server_id = p_server
                     and (c.state = 'pending' or (c.state = 'running' and c.delivered_at < now() - interval '10 minutes'))
                     order by c.id limit 10)
        returning *;
end $$;

create or replace function public.bms_agent_finish(p_server uuid, p_key text, p_command bigint, p_ok boolean, p_result text) returns void
language plpgsql security definer set search_path = '' as $$
begin
    perform public.bms_agent_check(p_server, p_key);
    update public.bms_server_commands set state = case when p_ok then 'done' else 'error' end,
        result = left(coalesce(p_result, ''), 1000), finished_at = now()
    where id = p_command and server_id = p_server;
end $$;

-- Lignes de la console (on garde les 1000 dernières par serveur).
create or replace function public.bms_agent_logs(p_server uuid, p_key text, p_lines text[]) returns void
language plpgsql security definer set search_path = '' as $$
begin
    perform public.bms_agent_check(p_server, p_key);
    insert into public.bms_server_logs (server_id, line)
        select p_server, left(l, 500) from unnest(p_lines[1:200]) as l;
    delete from public.bms_server_logs where server_id = p_server and id < (
        select min(id) from (select id from public.bms_server_logs where server_id = p_server order by id desc limit 1000) t);
end $$;

grant execute on function public.bms_agent_config(uuid, text) to anon, authenticated;
grant execute on function public.bms_agent_heartbeat(uuid, text, text, text, text, text, jsonb, jsonb, jsonb) to anon, authenticated;
grant execute on function public.bms_agent_finish(uuid, text, bigint, boolean, text) to anon, authenticated;
grant execute on function public.bms_agent_logs(uuid, text, text[]) to anon, authenticated;

-- Les commandes terminées depuis plus d'une semaine sont nettoyées à chaque nouvelle commande.
create or replace function public.bms_cleanup_commands() returns trigger
language plpgsql set search_path = '' as $$
begin
    delete from public.bms_server_commands where server_id = new.server_id and state in ('done', 'error') and created_at < now() - interval '7 days';
    return new;
end $$;
drop trigger if exists bms_cleanup_commands on public.bms_server_commands;
create trigger bms_cleanup_commands after insert on public.bms_server_commands
    for each row execute function public.bms_cleanup_commands();

-- ---------------------------------------------------------------- Modifications depuis l'application
-- (fonctions « security invoker » : les règles RLS s'appliquent, chacun ne modifie que ses serveurs)

create or replace function public.bms_update_server(p_server uuid, p_settings jsonb) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    update public.bms_servers set
        name = coalesce(p_settings->>'name', name),
        gamemode = coalesce(p_settings->>'gamemode', gamemode),
        difficulty = coalesce(p_settings->>'difficulty', difficulty),
        max_players = coalesce((p_settings->>'max_players')::int, max_players),
        level_seed = coalesce(p_settings->>'level_seed', level_seed),
        view_distance = coalesce((p_settings->>'view_distance')::int, view_distance),
        tick_distance = coalesce((p_settings->>'tick_distance')::int, tick_distance),
        allow_cheats = coalesce((p_settings->>'allow_cheats')::boolean, allow_cheats),
        beta_apis = coalesce((p_settings->>'beta_apis')::boolean, beta_apis),
        agent_key_hash = coalesce(p_settings->>'agent_key_hash', agent_key_hash)
    where id = p_server;
    if not found then raise exception 'Serveur introuvable'; end if;
end $$;

create or replace function public.bms_update_pack(p_pack uuid, p_position int, p_enabled boolean) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    update public.bms_server_packs set position = coalesce(p_position, position), enabled = coalesce(p_enabled, enabled)
    where id = p_pack;
end $$;

revoke execute on function public.bms_update_server(uuid, jsonb) from public, anon;
revoke execute on function public.bms_update_pack(uuid, int, boolean) from public, anon;
grant execute on function public.bms_update_server(uuid, jsonb) to authenticated;
grant execute on function public.bms_update_pack(uuid, int, boolean) to authenticated;

-- ---------------------------------------------------------------- Stockage des packs (bucket privé)

insert into storage.buckets (id, name, public, file_size_limit)
values ('bms-packs', 'bms-packs', false, 52428800)
on conflict (id) do nothing;

-- Chemins : <uid>/<server_id>/<fichier>.mcpack — chacun ne touche qu'à son dossier.
drop policy if exists "bms-packs: owner folder" on storage.objects;
create policy "bms-packs: owner folder" on storage.objects
    for all to authenticated
    using (bucket_id = 'bms-packs' and (storage.foldername(name))[1] = (select auth.uid())::text)
    with check (bucket_id = 'bms-packs' and (storage.foldername(name))[1] = (select auth.uid())::text);
