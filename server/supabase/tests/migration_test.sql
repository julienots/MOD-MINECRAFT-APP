-- Test de la migration (après supabase_mock.sql et la migration). Toute anomalie lève une exception.
\set ON_ERROR_STOP 1
grant select, insert, update, delete on all tables in schema public to anon, authenticated;
insert into auth.users values ('00000000-0000-0000-0000-00000000000a'), ('00000000-0000-0000-0000-00000000000b');

set role authenticated; set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
insert into bms_servers (name, agent_key_hash) values ('Test', encode(extensions.digest('cle-secrete', 'sha256'), 'hex'));
insert into bms_server_commands (server_id, kind) select id, 'start' from bms_servers;
update bms_servers set status = 'online', players = '[{"name":"x"}]', name = 'Renommé';
do $$ begin
  if (select status from bms_servers) <> 'not_installed' then raise exception 'état falsifiable depuis l''application'; end if;
  if (select name from bms_servers) <> 'Renommé' then raise exception 'le nom devrait être modifiable'; end if;
end $$;
select bms_update_server((select id from bms_servers), '{"max_players": 3, "beta_apis": true}');
do $$ begin
  begin perform bms_update_server((select id from bms_servers), '{"max_players": 9}'); raise exception 'X';
  exception when check_violation then null; end;
  if (select max_players from bms_servers) <> 3 then raise exception 'max_players attendu 3'; end if;
end $$;

set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
do $$ begin
  if (select count(*) from bms_servers) <> 0 then raise exception 'B voit les serveurs de A'; end if;
  if (select count(*) from bms_server_commands) <> 0 then raise exception 'B voit les commandes de A'; end if;
end $$;
reset role; reset request.jwt.claim.sub;
select id as sid from bms_servers \gset
select set_config('bms.sid', :'sid', false);
set role authenticated; set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
do $$ begin
  begin perform bms_update_server(current_setting('bms.sid')::uuid, '{"name":"piraté"}'); exception when others then null; end;
end $$;
reset role;
do $$ begin if (select name from bms_servers) = 'piraté' then raise exception 'B a modifié le serveur de A'; end if; end $$;

set role anon;
do $$ begin
  if (select count(*) from bms_servers) <> 0 then raise exception 'anon lit la table'; end if;
  begin perform bms_agent_config((select null::uuid), 'x'); exception when others then null; end;
end $$;
do $$ declare ok boolean := false; begin
  begin perform bms_agent_heartbeat(current_setting('bms.sid')::uuid, 'mauvaise', 'online', '', '', '', '[]', '{}', null);
  exception when sqlstate '28000' then ok := true; end;
  if not ok then raise exception 'mauvaise clé acceptée'; end if;
end $$;
select count(*) as delivered from bms_agent_heartbeat(:'sid', 'cle-secrete', 'online', 'ok', '1.2.3.4:19132', '1.21', '[{"name":"Steve"}]', '{}', null) \gset
select count(*) as again from bms_agent_heartbeat(:'sid', 'cle-secrete', 'online', 'ok', '1.2.3.4:19132', '1.21', '[{"name":"Steve"}]', '{}', null) \gset
select bms_agent_finish(:'sid', 'cle-secrete', 1, true, 'démarré');
select bms_agent_logs(:'sid', 'cle-secrete', (select array_agg('ligne ' || g) from generate_series(1, 200) g));
reset role;
do $$ begin
  if (select status from bms_servers) <> 'online' then raise exception 'battement non enregistré'; end if;
  if (select state from bms_server_commands where id = 1) <> 'done' then raise exception 'commande non terminée'; end if;
  if (select count(*) from bms_server_logs) <> 200 then raise exception 'journal incomplet'; end if;
end $$;
\if :delivered
\else
  \echo 'ÉCHEC : commande non remise'
  select 1/0;
\endif
select :again = 0 as no_double_delivery \gset
\if :no_double_delivery
\else
  \echo 'ÉCHEC : commande remise deux fois'
  select 1/0;
\endif
\echo 'Migration : tous les tests passent'
