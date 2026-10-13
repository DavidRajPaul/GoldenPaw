-- GoldenPaw calm promotions (docs/MONETIZATION_ADS.md). Idempotent.
--
-- ad_config          one row per key; the policy the app's AdEngine enforces (kill switch included)
-- ad_house_creatives our own promotion cards (Plus, seasonal offers, feature news)
-- ad_daily_stats     aggregate counts per creative per day; no user, device or install id, ever

create table if not exists ad_config (
  key        text primary key check (key = 'policy'),
  value      jsonb not null,
  updated_at timestamptz not null default now()
);

-- Ships OFF. Turn on with: update ad_config set value = jsonb_set(value, '{enabled}', 'true') where key = 'policy';
insert into ad_config (key, value) values ('policy', '{
  "enabled": false,
  "min_days_since_install": 7,
  "min_sessions": 3,
  "max_per_session": 1,
  "max_per_day": 2,
  "min_gap_minutes": 240,
  "hide_cooldown_hours": 72,
  "max_rewarded_per_day": 1,
  "quiet_on_hard_day": true,
  "holdout_percent": 10,
  "placements": ["insights_feed", "journal_feed"],
  "network_percent": 0
}'::jsonb) on conflict (key) do nothing;

create table if not exists ad_house_creatives (
  id          text primary key,
  title       text not null check (length(title) <= 60),
  body        text not null default '' check (length(body) <= 160),
  cta         text not null default 'Learn more' check (length(cta) <= 24),
  open        text not null default 'paywall',
  offer_id    text references promo_offers(id) on delete set null,
  placements  text[] not null default '{insights_feed,journal_feed}',
  audience    text not null default 'free' check (audience in ('free', 'plus', 'all')),
  weight      int  not null default 1 check (weight between 0 and 100),
  platforms   text[] not null default '{android,ios,desktop}',
  countries   text[],
  min_app_build int not null default 0,
  starts_at   timestamptz,
  ends_at     timestamptz,
  active      boolean not null default true,
  updated_at  timestamptz not null default now()
);

create table if not exists ad_daily_stats (
  day         date not null default (now() at time zone 'utc')::date,
  creative_id text not null references ad_house_creatives(id) on delete cascade,
  impressions int not null default 0,
  clicks      int not null default 0,
  hides       int not null default 0,
  primary key (day, creative_id)
);

alter table ad_config enable row level security;          -- read only through gp_ads_config()
alter table ad_house_creatives enable row level security; -- read only through gp_ads_config()
alter table ad_daily_stats enable row level security;     -- written only through gp_record_ad_event()

-- Reuse the pricing version sequence so any edit invalidates cached catalogs.
do $$
declare t text;
begin
  foreach t in array array['ad_config','ad_house_creatives'] loop
    execute format('drop trigger if exists %I on %I', t || '_version', t);
    execute format('create trigger %I before insert or update or delete on %I for each row execute function gp_bump_pricing_version()', t || '_version', t);
  end loop;
end $$;

create or replace function gp_ads_config(p_platform text default 'android', p_country text default '', p_app_build int default 0)
returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'version', (select coalesce(last_value, 0) from pricing_version_seq),
    'policy', coalesce((select value from ad_config where key = 'policy'), '{}'::jsonb),
    'creatives', coalesce((
      select json_agg(json_build_object(
        'id', c.id, 'title', c.title, 'body', c.body, 'cta', c.cta, 'open', c.open, 'offer_id', c.offer_id,
        'placements', c.placements, 'audience', c.audience, 'weight', c.weight,
        'starts_at_ms', (extract(epoch from c.starts_at) * 1000)::bigint,
        'ends_at_ms', (extract(epoch from c.ends_at) * 1000)::bigint
      ) order by c.id)
      from ad_house_creatives c
      where c.active and p_platform = any (c.platforms) and c.min_app_build <= p_app_build
        and (c.countries is null or upper(p_country) = any (c.countries))
        and (c.ends_at is null or c.ends_at > now())
    ), '[]'::json)
  )
$$;

create or replace function gp_record_ad_event(p_creative text, p_kind text)
returns void
language plpgsql security definer set search_path = public as $$
begin
  if p_kind not in ('impression', 'click', 'hide') then return; end if;
  if not exists (select 1 from ad_house_creatives where id = p_creative) then return; end if;
  insert into ad_daily_stats (creative_id, impressions, clicks, hides)
  values (p_creative, (p_kind = 'impression')::int, (p_kind = 'click')::int, (p_kind = 'hide')::int)
  on conflict (day, creative_id) do update set
    impressions = ad_daily_stats.impressions + excluded.impressions,
    clicks      = ad_daily_stats.clicks + excluded.clicks,
    hides       = ad_daily_stats.hides + excluded.hides;
end $$;

revoke all on function gp_ads_config(text, text, int) from public;
revoke all on function gp_record_ad_event(text, text) from public;
grant execute on function gp_ads_config(text, text, int) to anon, authenticated;
grant execute on function gp_record_ad_event(text, text) to anon, authenticated;
