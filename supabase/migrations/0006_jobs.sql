-- Background jobs: scheduled push campaigns, new-ticket alerts and data retention. Idempotent.
--
-- Needs two Vault secrets (scripts/setup-supabase.sh prints the SQL to create them):
--   project_url       https://<ref>.supabase.co
--   push_admin_token  same value as the PUSH_ADMIN_TOKEN function secret
-- Until both exist every job below is a silent no-op, so `db push` is safe on a fresh project.

create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;

-- Calls one of our admin-only Edge Functions (send-push, support-alert) asynchronously.
-- Returns the pg_net request id, or null when the Vault secrets aren't set yet.
create or replace function gp_call_admin_function(p_name text, p_body jsonb)
returns bigint
language plpgsql security definer set search_path = public, extensions as $$
declare
  v_url   text;
  v_token text;
begin
  select decrypted_secret into v_url from vault.decrypted_secrets where name = 'project_url';
  select decrypted_secret into v_token from vault.decrypted_secrets where name = 'push_admin_token';
  if v_url is null or v_token is null then
    return null;
  end if;
  return net.http_post(
    url     := rtrim(v_url, '/') || '/functions/v1/' || p_name,
    headers := jsonb_build_object('Authorization', 'Bearer ' || v_token, 'Content-Type', 'application/json'),
    body    := p_body
  );
end $$;

revoke all on function gp_call_admin_function(text, jsonb) from public, anon, authenticated;

-- ----------------------------------------------------------------- scheduled push campaigns

-- Hands due campaigns to send-push (at most 5 per minute; send-push is idempotent per device).
create or replace function gp_dispatch_due_campaigns()
returns int
language plpgsql security definer set search_path = public as $$
declare
  c record;
  n int := 0;
begin
  for c in
    select id from notification_campaigns
     where status = 'scheduled' and send_at <= now()
     order by send_at
     limit 5
  loop
    if gp_call_admin_function('send-push', jsonb_build_object('campaign_id', c.id)) is not null then
      n := n + 1;
    end if;
  end loop;
  return n;
end $$;

revoke all on function gp_dispatch_due_campaigns() from public, anon, authenticated;

select cron.schedule('gp-send-due-push', '* * * * *', 'select gp_dispatch_due_campaigns()');

-- ----------------------------------------------------------------- support ticket alerts

-- Emails the developer when a support request arrives (support-alert function, via Resend).
create or replace function gp_support_request_alert()
returns trigger
language plpgsql security definer set search_path = public as $$
begin
  perform gp_call_admin_function('support-alert', jsonb_build_object('id', new.id));
  return new;
exception when others then
  -- Never block a ticket because the alert couldn't be queued.
  return new;
end $$;

drop trigger if exists support_requests_alert on support_requests;
create trigger support_requests_alert
  after insert on support_requests
  for each row execute function gp_support_request_alert();

-- ----------------------------------------------------------------- retention (privacy policy)

-- Support messages: kept at most 24 months. Dead push tokens: dropped after 90 days.
create or replace function gp_apply_retention()
returns void
language plpgsql security definer set search_path = public as $$
begin
  delete from support_requests where created_at < now() - interval '24 months';
  update devices set push_token = null
   where token_status = 'invalid' and push_token is not null and last_seen_at < now() - interval '90 days';
end $$;

revoke all on function gp_apply_retention() from public, anon, authenticated;

select cron.schedule('gp-retention', '17 3 * * *', 'select gp_apply_retention()');
