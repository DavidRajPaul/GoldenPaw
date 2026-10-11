-- GoldenPaw 1.3: backend-configurable pricing, push notifications, entitlements, and RLS that
-- scales with the number of rows. See docs/DATABASE_DESIGN.md for the reasoning behind each part.
--
-- Safe to run on a database that already has 0001 applied. Idempotent.

-- =================================================================== 1. RLS that scales
--
-- 0001 policies call gp_is_member(household_id) once PER ROW. On a pull of thousands of rows that is
-- thousands of function calls, each doing two lookups. Instead, compute the caller's household ids
-- once per statement (the `(select …)` makes Postgres evaluate it as an InitPlan) and test
-- membership with a hashed `= any(...)`.

create or replace function gp_my_household_ids() returns text[]
language sql stable security definer set search_path = public as $$
  select coalesce(array_agg(distinct hid), '{}') from (
    select h.id as hid from households h where h.owner_id = auth.uid() and h.deleted_at is null
    union all
    select m.household_id from household_members m
     where m.user_id = auth.uid() and m.status = 'ACTIVE'
       and (m.access_until is null or m.access_until > gp_now_ms())
  ) x
$$;

revoke all on function gp_my_household_ids() from public;
grant execute on function gp_my_household_ids() to authenticated;

-- Covering index for the membership lookup above.
create index if not exists household_members_user_active
  on household_members (user_id, household_id) where status = 'ACTIVE';
create index if not exists households_owner on households (owner_id);

do $$
declare t text;
begin
  foreach t in array array['pets','medications','dose_events','check_ins','weight_entries','symptom_entries','vet_visits'] loop
    execute format('drop policy if exists %I on %I', t || '_select', t);
    execute format('create policy %I on %I for select to authenticated using (household_id = any ((select gp_my_household_ids())))', t || '_select', t);
    execute format('drop policy if exists %I on %I', t || '_insert', t);
    execute format('create policy %I on %I for insert to authenticated with check (household_id = any ((select gp_my_household_ids())))', t || '_insert', t);
    execute format('drop policy if exists %I on %I', t || '_update', t);
    execute format('create policy %I on %I for update to authenticated using (household_id = any ((select gp_my_household_ids()))) with check (household_id = any ((select gp_my_household_ids())))', t || '_update', t);
  end loop;
end $$;

drop policy if exists members_select on household_members;
create policy members_select on household_members for select to authenticated
  using (household_id = any ((select gp_my_household_ids())));

-- Time-ordered reads per pet (charts, vet report, weekly digest on the server later).
create index if not exists dose_events_pet_time on dose_events (pet_id, scheduled_at);
create index if not exists check_ins_pet_day on check_ins (pet_id, epoch_day);
create index if not exists weight_entries_pet_day on weight_entries (pet_id, epoch_day);
create index if not exists symptom_entries_pet_time on symptom_entries (pet_id, logged_at);
create index if not exists vet_visits_pet_time on vet_visits (pet_id, at);
create index if not exists medications_pet on medications (pet_id);

-- =================================================================== 2. Pricing (no app release needed)

-- Bumped on every pricing change; clients never replace a newer cached catalog with an older one.
create sequence if not exists pricing_version_seq;

create table if not exists pricing_plans (
  id                  text primary key,                 -- e.g. plus_yearly
  title               text not null,                    -- "Yearly"
  price_label         text not null,                    -- "$39.99 / year" (display; store price wins once billing is wired)
  detail              text not null default '',         -- "≈ $3.33 / month · save 44%"
  period              text not null default 'P1M' check (period ~ '^P[0-9]+[DWMY]$'),
  trial_days          int  not null default 0 check (trial_days between 0 and 90),
  badge               text not null default '',
  is_default          boolean not null default false,
  sort_order          int  not null default 0,
  android_product_id  text not null default '',
  ios_product_id      text not null default '',
  platforms           text[] not null default '{android,ios,desktop}',
  countries           text[],                           -- ISO-3166 alpha-2; null = everywhere
  min_app_build       int  not null default 0,          -- hide plans older apps can't handle
  active              boolean not null default true,
  updated_at          timestamptz not null default now()
);

create table if not exists promo_offers (
  id               text primary key,                    -- referenced by push data "offerId"
  title            text not null,
  body             text not null default '',
  badge            text not null default '',
  plan_id          text references pricing_plans(id) on delete set null,
  price_label      text not null default '',
  -- Store-side promotional offer ids, for when billing is wired.
  android_offer_id text not null default '',
  ios_offer_id     text not null default '',
  starts_at        timestamptz,
  ends_at          timestamptz,
  show_on_paywall  boolean not null default true,       -- false = only when opened from its push
  platforms        text[] not null default '{android,ios,desktop}',
  countries        text[],
  active           boolean not null default true,
  updated_at       timestamptz not null default now(),
  check (ends_at is null or starts_at is null or ends_at > starts_at)
);

-- Paywall copy (headline, button text). One row per key; value is the text.
create table if not exists paywall_copy (
  key         text primary key check (key in ('headline', 'trial_cta', 'buy_cta', 'footer')),
  value       text not null,
  updated_at  timestamptz not null default now()
);

create or replace function gp_bump_pricing_version() returns trigger
language plpgsql as $$
begin
  perform nextval('pricing_version_seq');
  if tg_op <> 'DELETE' then
    new.updated_at := now();
    return new;
  end if;
  return old;
end $$;

do $$
declare t text;
begin
  foreach t in array array['pricing_plans','promo_offers','paywall_copy'] loop
    execute format('drop trigger if exists %I on %I', t || '_version', t);
    execute format('create trigger %I before insert or update or delete on %I for each row execute function gp_bump_pricing_version()', t || '_version', t);
    execute format('alter table %I enable row level security', t);
    -- No policies: clients read only through gp_pricing_catalog(); edits use the dashboard / service role.
  end loop;
end $$;

-- The whole paywall in one call. Anonymous: signed-out users see prices too.
create or replace function gp_pricing_catalog(p_platform text default 'android', p_country text default '', p_app_version int default 0)
returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'version', (select coalesce(last_value, 0) from pricing_version_seq),
    'plans', coalesce((
      select json_agg(json_build_object(
        'id', p.id, 'title', p.title, 'price_label', p.price_label, 'detail', p.detail,
        'period', p.period, 'trial_days', p.trial_days, 'badge', p.badge, 'is_default', p.is_default,
        'android_product_id', p.android_product_id, 'ios_product_id', p.ios_product_id
      ) order by p.sort_order, p.id)
      from pricing_plans p
      where p.active and p_platform = any (p.platforms) and p.min_app_build <= p_app_version
        and (p.countries is null or upper(p_country) = any (p.countries))
    ), '[]'::json),
    'offers', coalesce((
      select json_agg(json_build_object(
        'id', o.id, 'title', o.title, 'body', o.body, 'badge', o.badge, 'plan_id', o.plan_id,
        'price_label', o.price_label, 'show_on_paywall', o.show_on_paywall,
        'starts_at_ms', (extract(epoch from o.starts_at) * 1000)::bigint,
        'ends_at_ms', (extract(epoch from o.ends_at) * 1000)::bigint
      ) order by o.starts_at nulls first, o.id)
      from promo_offers o
      where o.active and p_platform = any (o.platforms)
        and (o.countries is null or upper(p_country) = any (o.countries))
        and (o.ends_at is null or o.ends_at > now())
    ), '[]'::json),
    'paywall', coalesce((select json_object_agg(c.key, c.value) from paywall_copy c), '{}'::json)
  )
$$;

revoke all on function gp_pricing_catalog(text, text, int) from public;
grant execute on function gp_pricing_catalog(text, text, int) to anon, authenticated;

-- Starting catalog = what the app bundles today. Edit freely afterwards.
insert into pricing_plans (id, title, price_label, detail, period, trial_days, badge, is_default, sort_order, android_product_id, ios_product_id)
values
  ('plus_yearly', 'Yearly', '$39.99 / year', '≈ $3.33 / month · save 44%', 'P1Y', 7, 'Best value', true, 0, 'goldenpaw_plus_yearly', 'com.goldenpaw.plus.yearly'),
  ('plus_monthly', 'Monthly', '$5.99 / month', 'Cancel any time', 'P1M', 7, '', false, 1, 'goldenpaw_plus_monthly', 'com.goldenpaw.plus.monthly')
on conflict (id) do nothing;

-- =================================================================== 3. Entitlements (Plus)
--
-- Written only by the server (store webhooks / RevenueCat via the service role). The app reads its
-- own row; it never grants itself Plus once billing is live.

create table if not exists subscriptions (
  user_id             uuid primary key references auth.users(id) on delete cascade,
  plan_id             text references pricing_plans(id) on delete set null,
  store               text not null check (store in ('play', 'app_store', 'promo', 'manual')),
  status              text not null check (status in ('trial', 'active', 'grace', 'paused', 'cancelled', 'expired')),
  original_txn_id     text,
  offer_id            text references promo_offers(id) on delete set null,
  current_period_end  timestamptz,
  auto_renew          boolean not null default true,
  updated_at          timestamptz not null default now()
);
create index if not exists subscriptions_status on subscriptions (status, current_period_end);

alter table subscriptions enable row level security;
drop policy if exists subscriptions_own on subscriptions;
create policy subscriptions_own on subscriptions for select to authenticated using (user_id = (select auth.uid()));

-- =================================================================== 4. Push notifications

-- One row per app install (not per user): signed-out installs can still get offers and app news.
create table if not exists devices (
  install_id     text primary key,                      -- random per install, generated on device
  user_id        uuid references auth.users(id) on delete set null,
  platform       text not null check (platform in ('android', 'ios', 'desktop')),
  push_token     text,
  token_status   text not null default 'valid' check (token_status in ('valid', 'invalid')),
  app_version    text not null default '',
  app_build      int  not null default 0,
  locale         text not null default '',
  country        text not null default '',              -- derived from locale, used for targeting
  timezone       text not null default 'UTC',
  notify_offers  boolean not null default false,        -- marketing: opt-in
  notify_product boolean not null default true,
  notify_account boolean not null default true,
  created_at     timestamptz not null default now(),
  last_seen_at   timestamptz not null default now()
);
create index if not exists devices_user on devices (user_id) where user_id is not null;
create unique index if not exists devices_token on devices (push_token) where push_token is not null;
create index if not exists devices_targeting on devices (platform, country) where token_status = 'valid' and push_token is not null;

alter table devices enable row level security;
drop policy if exists devices_own on devices;
create policy devices_own on devices for select to authenticated using (user_id = (select auth.uid()));

-- Upsert this install. Called by the app on launch, token change, sign-in/out and when the owner
-- changes notification choices. Links the row to the caller (or unlinks it when signed out).
create or replace function gp_register_device(
  p_install_id text, p_platform text, p_push_token text, p_app_version text, p_app_build int,
  p_locale text, p_timezone text, p_offers boolean, p_product boolean, p_account boolean
) returns void
language plpgsql security definer set search_path = public as $$
declare
  v_country text := upper(coalesce(substring(p_locale from '[-_]([A-Za-z]{2})$'), ''));
begin
  if p_install_id is null or length(p_install_id) not between 8 and 64 then
    raise exception 'bad install id' using errcode = '22023';
  end if;
  -- A token moves with the install; drop it from any stale row first (reinstalls, restores).
  if p_push_token is not null then
    update devices set push_token = null where push_token = p_push_token and install_id <> p_install_id;
  end if;
  insert into devices as d (install_id, user_id, platform, push_token, token_status, app_version, app_build, locale, country,
                            timezone, notify_offers, notify_product, notify_account, last_seen_at)
  values (p_install_id, auth.uid(), p_platform, p_push_token, 'valid', left(coalesce(p_app_version, ''), 32), coalesce(p_app_build, 0),
          left(coalesce(p_locale, ''), 35), v_country, left(coalesce(nullif(p_timezone, ''), 'UTC'), 64),
          coalesce(p_offers, false), coalesce(p_product, true), coalesce(p_account, true), now())
  on conflict (install_id) do update set
    user_id = auth.uid(),
    platform = excluded.platform,
    push_token = excluded.push_token,
    token_status = 'valid',
    app_version = excluded.app_version,
    app_build = excluded.app_build,
    locale = excluded.locale,
    country = excluded.country,
    timezone = excluded.timezone,
    notify_offers = excluded.notify_offers,
    notify_product = excluded.notify_product,
    notify_account = excluded.notify_account,
    last_seen_at = now();
end $$;

revoke all on function gp_register_device(text, text, text, text, int, text, text, boolean, boolean, boolean) from public;
grant execute on function gp_register_device(text, text, text, text, int, text, text, boolean, boolean, boolean) to anon, authenticated;

-- A campaign = one message to a segment (offers, app news) or to specific users (account events).
create table if not exists notification_campaigns (
  id            uuid primary key default gen_random_uuid(),
  category      text not null check (category in ('offers', 'product', 'account')),
  title         text not null check (length(title) <= 80),
  body          text not null check (length(body) <= 240),
  open          text not null default 'today',          -- in-app destination
  offer_id      text references promo_offers(id) on delete set null,
  -- Segment. All filters are ANDed; null = no filter.
  platforms     text[],
  countries     text[],
  min_app_build int,
  user_ids      uuid[],                                  -- account messages target users directly
  plus_only     boolean,                                 -- true = Plus users, false = free users, null = everyone
  status        text not null default 'draft' check (status in ('draft', 'scheduled', 'sending', 'sent', 'failed', 'cancelled')),
  send_at       timestamptz,
  created_by    uuid references auth.users(id) on delete set null,
  created_at    timestamptz not null default now(),
  sent_at       timestamptz,
  stats         jsonb not null default '{}'::jsonb
);
create index if not exists campaigns_due on notification_campaigns (send_at) where status = 'scheduled';

-- One row per (campaign, device). Partition by month once it grows past ~50M rows (see docs).
create table if not exists notification_deliveries (
  campaign_id   uuid not null references notification_campaigns(id) on delete cascade,
  install_id    text not null references devices(install_id) on delete cascade,
  status        text not null check (status in ('sent', 'failed', 'invalid_token', 'opened')),
  error         text,
  sent_at       timestamptz not null default now(),
  opened_at     timestamptz,
  primary key (campaign_id, install_id)
);
create index if not exists deliveries_device on notification_deliveries (install_id, sent_at desc);

alter table notification_campaigns enable row level security;   -- service role only
alter table notification_deliveries enable row level security;  -- service role only

-- Devices a campaign should reach, honouring each device's opt-ins. Used by the send-push function.
create or replace function gp_campaign_targets(p_campaign uuid)
returns table (install_id text, platform text, push_token text)
language sql stable security definer set search_path = public as $$
  select d.install_id, d.platform, d.push_token
    from notification_campaigns c
    join devices d on d.push_token is not null and d.token_status = 'valid'
   where c.id = p_campaign
     and case c.category
           when 'offers'  then d.notify_offers
           when 'product' then d.notify_product
           when 'account' then d.notify_account
         end
     and (c.platforms is null or d.platform = any (c.platforms))
     and (c.countries is null or d.country = any (c.countries))
     and (c.min_app_build is null or d.app_build >= c.min_app_build)
     and (c.user_ids is null or d.user_id = any (c.user_ids))
     and (c.plus_only is null or c.plus_only = exists (
           select 1 from subscriptions s
            where s.user_id = d.user_id and s.status in ('trial', 'active', 'grace')))
     and not exists (select 1 from notification_deliveries x where x.campaign_id = c.id and x.install_id = d.install_id)
$$;

revoke all on function gp_campaign_targets(uuid) from public;
grant execute on function gp_campaign_targets(uuid) to service_role;

-- Queue an account notification to specific users (all their devices that allow account pushes).
-- The dispatcher (pg_cron → send-push, see docs) picks up scheduled campaigns that are due.
create or replace function gp_queue_account_push(p_user_ids uuid[], p_title text, p_body text, p_open text default 'today')
returns uuid
language sql security definer set search_path = public as $$
  insert into notification_campaigns (category, title, body, open, user_ids, status, send_at)
  values ('account', left(p_title, 80), left(p_body, 240), p_open, p_user_ids, 'scheduled', now())
  returning id
$$;

revoke all on function gp_queue_account_push(uuid[], text, text, text) from public;
grant execute on function gp_queue_account_push(uuid[], text, text, text) to service_role;

-- Example of a user-related push: tell the owner when someone joins their care team.
create or replace function gp_notify_owner_member_joined() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  v_owner uuid;
begin
  if new.user_id is null or new.status <> 'ACTIVE' or new.role = 'OWNER' then
    return new;
  end if;
  if tg_op = 'UPDATE' and old.status = 'ACTIVE' and old.user_id is not distinct from new.user_id then
    return new; -- not a (re)join
  end if;
  select owner_id into v_owner from households where id = new.household_id;
  if v_owner is not null and v_owner <> new.user_id then
    perform gp_queue_account_push(
      array[v_owner],
      new.display_name || ' joined your care team',
      'They can now see today''s doses and log care. You can change their access any time.',
      'careteam'
    );
  end if;
  return new;
end $$;

drop trigger if exists household_members_notify on household_members;
create trigger household_members_notify after insert or update on household_members
  for each row execute function gp_notify_owner_member_joined();
