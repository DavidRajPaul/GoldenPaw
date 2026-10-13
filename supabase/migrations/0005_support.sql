-- In-app support inbox (docs/SUPPORT.md). Idempotent.
--
-- The app posts through gp_submit_support_request(); nobody can read the table except the service
-- role (Supabase dashboard / your support tooling). Diagnostics hold app state only, no pet data.

create table if not exists support_requests (
  id           bigint generated always as identity primary key,
  created_at   timestamptz not null default now(),
  install_id   text not null,
  user_id      uuid references auth.users(id) on delete set null,
  topic        text not null check (topic in ('billing', 'reminders', 'sync', 'bug', 'idea', 'other')),
  message      text not null check (length(message) between 10 and 4000),
  contact_email text check (contact_email is null or contact_email ~* '^[^@\s]+@[^@\s]+\.[^@\s]{2,}$'),
  diagnostics  jsonb not null default '{}'::jsonb,
  status       text not null default 'open' check (status in ('open', 'waiting', 'closed')),
  answered_at  timestamptz,
  notes        text not null default ''
);
create index if not exists support_requests_open on support_requests (status, created_at) where status <> 'closed';
create index if not exists support_requests_install on support_requests (install_id, created_at desc);

alter table support_requests enable row level security; -- no policies: service role only

create or replace function gp_submit_support_request(
  p_install_id text, p_topic text, p_message text, p_email text, p_diagnostics jsonb
) returns bigint
language plpgsql security definer set search_path = public as $$
declare
  new_id bigint;
begin
  if p_install_id is null or length(p_install_id) not between 8 and 64 then
    raise exception 'bad install id' using errcode = '22023';
  end if;
  -- Gentle abuse guard: at most 5 messages per install per day.
  if (select count(*) from support_requests
       where install_id = p_install_id and created_at > now() - interval '1 day') >= 5 then
    raise exception 'You''ve sent a few messages today. We''ll reply to those first.' using errcode = 'P0001';
  end if;
  insert into support_requests (install_id, user_id, topic, message, contact_email, diagnostics)
  values (p_install_id, auth.uid(), p_topic, trim(p_message), nullif(trim(p_email), ''), coalesce(p_diagnostics, '{}'::jsonb))
  returning id into new_id;
  return new_id;
end $$;

revoke all on function gp_submit_support_request(text, text, text, text, jsonb) from public;
grant execute on function gp_submit_support_request(text, text, text, text, jsonb) to anon, authenticated;
