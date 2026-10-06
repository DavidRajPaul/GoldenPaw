-- GoldenPaw shared care schema (Supabase / Postgres 15).
--
-- Model
--   * A household is a care team. Pets and every log row carry household_id.
--   * Ids are client-generated text (UUIDs, or "petId:epochDay" for check-ins) so devices can create
--     rows offline.
--   * Clients send updated_at (epoch millis). Last write wins: an older update is ignored.
--   * The server stamps synced_at on every accepted write. Devices pull "rows with synced_at > my cursor".
--   * Nothing is hard-deleted by clients; rows carry deleted_at instead.
--
-- Roles: OWNER (everything), FAMILY (pets, meds, logs), SITTER (logs only; can update a medication's
-- remaining supply, nothing else; access can end at access_until).

create extension if not exists pgcrypto;

-- ------------------------------------------------------------------ helpers

create or replace function gp_now_ms() returns bigint
language sql stable as $$ select (extract(epoch from now()) * 1000)::bigint $$;

-- ------------------------------------------------------------------ tables

create table if not exists households (
  id          text primary key,
  name        text not null,
  owner_id    uuid references auth.users(id) on delete set null,
  created_at  bigint not null,
  updated_at  bigint not null,
  deleted_at  bigint,
  synced_at   timestamptz not null default clock_timestamp()
);

create table if not exists household_members (
  id            text primary key,
  household_id  text not null references households(id) on delete cascade,
  user_id       uuid references auth.users(id) on delete set null,
  display_name  text not null,
  role          text not null check (role in ('OWNER', 'FAMILY', 'SITTER')),
  color_index   int not null default 0,
  access_until  bigint,
  status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'INVITED', 'REMOVED')),
  created_at    bigint not null,
  updated_at    bigint not null,
  synced_at     timestamptz not null default clock_timestamp()
);
create index if not exists household_members_user on household_members (user_id);
create index if not exists household_members_household on household_members (household_id);

create table if not exists pets (
  id              text primary key,
  household_id    text not null references households(id) on delete cascade,
  name            text not null,
  species         text not null,
  breed           text not null default '',
  sex             text not null default 'UNKNOWN',
  birth_epoch_day bigint,
  conditions      text not null default '',
  vet_name        text not null default '',
  vet_phone       text not null default '',
  notes           text not null default '',
  created_at      bigint not null,
  updated_at      bigint not null,
  archived_at     bigint,
  deleted_at      bigint,
  synced_at       timestamptz not null default clock_timestamp()
);

create table if not exists medications (
  id                text primary key,
  household_id      text not null references households(id) on delete cascade,
  pet_id            text not null references pets(id) on delete cascade,
  name              text not null,
  dosage            text not null default '',
  unit              text not null default '',
  route             text not null default '',
  reason            text not null default '',
  prescribed_by     text not null default '',
  with_food         boolean not null default false,
  schedule_type     text not null,
  times             text not null default '',
  interval_days     int not null default 1,
  weekdays          text not null default '',
  taper_steps       text not null default '',
  start_epoch_day   bigint not null,
  end_epoch_day     bigint,
  supply_remaining  double precision,
  refill_alert_days int not null default 5,
  notes             text not null default '',
  is_active         boolean not null default true,
  created_at        bigint not null,
  updated_at        bigint not null,
  deleted_at        bigint,
  synced_at         timestamptz not null default clock_timestamp()
);

create table if not exists dose_events (
  id            text primary key,
  household_id  text not null references households(id) on delete cascade,
  medication_id text not null references medications(id) on delete cascade,
  pet_id        text not null references pets(id) on delete cascade,
  scheduled_at  bigint not null,
  actual_at     bigint not null,
  status        text not null check (status in ('GIVEN', 'SKIPPED')),
  dosage_given  text not null default '',
  given_by      text not null default '',
  given_by_id   text,
  notes         text not null default '',
  created_at    bigint not null,
  updated_at    bigint not null,
  deleted_at    bigint,
  synced_at     timestamptz not null default clock_timestamp()
);
create index if not exists dose_events_slot on dose_events (medication_id, scheduled_at);

create table if not exists check_ins (
  id            text primary key,           -- "petId:epochDay": one per pet per day
  household_id  text not null references households(id) on delete cascade,
  pet_id        text not null references pets(id) on delete cascade,
  epoch_day     bigint not null,
  appetite      int not null,
  water         int not null,
  mobility      int not null,
  mood          int not null,
  pain          int not null,
  hygiene       int not null,
  sleep         int not null,
  notes         text not null default '',
  logged_by     text not null default '',
  logged_by_id  text,
  created_at    bigint not null,
  updated_at    bigint not null,
  synced_at     timestamptz not null default clock_timestamp()
);

create table if not exists weight_entries (
  id            text primary key,
  household_id  text not null references households(id) on delete cascade,
  pet_id        text not null references pets(id) on delete cascade,
  epoch_day     bigint not null,
  weight_kg     double precision not null,
  notes         text not null default '',
  logged_by     text not null default '',
  logged_by_id  text,
  created_at    bigint not null,
  updated_at    bigint not null,
  deleted_at    bigint,
  synced_at     timestamptz not null default clock_timestamp()
);

create table if not exists symptom_entries (
  id            text primary key,
  household_id  text not null references households(id) on delete cascade,
  pet_id        text not null references pets(id) on delete cascade,
  epoch_day     bigint not null,
  logged_at     bigint not null,
  type          text not null,
  severity      int not null,
  tags          text not null default '',
  notes         text not null default '',
  logged_by     text not null default '',
  logged_by_id  text,
  created_at    bigint not null,
  updated_at    bigint not null,
  deleted_at    bigint,
  synced_at     timestamptz not null default clock_timestamp()
);

create table if not exists vet_visits (
  id            text primary key,
  household_id  text not null references households(id) on delete cascade,
  pet_id        text not null references pets(id) on delete cascade,
  title         text not null,
  clinic        text not null default '',
  at            bigint not null,
  notes         text not null default '',
  completed     boolean not null default false,
  logged_by     text not null default '',
  logged_by_id  text,
  created_at    bigint not null,
  updated_at    bigint not null,
  deleted_at    bigint,
  synced_at     timestamptz not null default clock_timestamp()
);

create table if not exists invites (
  code          text primary key,
  household_id  text not null references households(id) on delete cascade,
  role          text not null check (role in ('FAMILY', 'SITTER')),
  access_until  bigint,
  created_by    uuid not null references auth.users(id) on delete cascade,
  expires_at    timestamptz not null,
  used_by       uuid references auth.users(id) on delete set null,
  used_at       timestamptz
);

-- Pull cursors page through (household_id, synced_at).
do $$
declare t text;
begin
  foreach t in array array['pets','medications','dose_events','check_ins','weight_entries','symptom_entries','vet_visits'] loop
    execute format('create index if not exists %I on %I (household_id, synced_at)', t || '_sync', t);
  end loop;
end $$;

-- ------------------------------------------------------------------ membership

-- Role of the current user in a household, or null. Owners always count; others must be ACTIVE and
-- within any access_until window. SECURITY DEFINER so policies can call it without recursion.
create or replace function gp_member_role(hid text) returns text
language sql stable security definer set search_path = public as $$
  select coalesce(
    (select 'OWNER' from households h where h.id = hid and h.owner_id = auth.uid()),
    (select m.role from household_members m
      where m.household_id = hid and m.user_id = auth.uid() and m.status = 'ACTIVE'
        and (m.access_until is null or m.access_until > gp_now_ms())
      limit 1)
  )
$$;

create or replace function gp_is_member(hid text) returns boolean
language sql stable security definer set search_path = public as $$
  select gp_member_role(hid) is not null
$$;

-- ------------------------------------------------------------------ triggers

-- Last write wins on updated_at, and every accepted write gets a fresh synced_at.
create or replace function gp_touch() returns trigger
language plpgsql as $$
begin
  if tg_op = 'UPDATE' and new.updated_at < old.updated_at then
    return old;  -- stale write from an offline device: keep the newer row
  end if;
  new.synced_at := clock_timestamp();
  return new;
end $$;

do $$
declare t text;
begin
  foreach t in array array['households','household_members','pets','medications','dose_events','check_ins','weight_entries','symptom_entries','vet_visits'] loop
    execute format('drop trigger if exists %I on %I', t || '_touch', t);
    execute format('create trigger %I before insert or update on %I for each row execute function gp_touch()', t || '_touch', t);
  end loop;
end $$;

-- Sitters: can't create or change pets/medications, except a medication's remaining supply (they
-- give doses and pick up refills). Upserts hit BEFORE INSERT first, so existing rows are let through
-- there and narrowed in the UPDATE branch.
create or replace function gp_guard_sitter() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  supply double precision;
  stamp bigint;
  exists_already boolean;
begin
  if gp_member_role(new.household_id) is distinct from 'SITTER' then
    return new;
  end if;
  if tg_op = 'INSERT' then
    execute format('select exists(select 1 from %I where id = $1)', tg_table_name) into exists_already using new.id;
    if exists_already then return new; end if;
    raise exception 'Sitters can''t add %', tg_table_name using errcode = '42501';
  end if;
  if tg_table_name = 'medications' then
    supply := new.supply_remaining;
    stamp := new.updated_at;
    new := old;
    new.supply_remaining := supply;
    new.updated_at := greatest(stamp, old.updated_at);
    return new;
  end if;
  return old;
end $$;

drop trigger if exists pets_guard on pets;
create trigger pets_guard before insert or update on pets for each row execute function gp_guard_sitter();
drop trigger if exists medications_guard on medications;
create trigger medications_guard before insert or update on medications for each row execute function gp_guard_sitter();

-- Members may rename themselves, but only the owner changes roles, status or access windows.
create or replace function gp_guard_member() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if gp_member_role(new.household_id) = 'OWNER' or current_setting('goldenpaw.trusted', true) = 'on' then
    return new;
  end if;
  if tg_op = 'INSERT' then
    raise exception 'Only the owner can add people' using errcode = '42501';
  end if;
  if new.role is distinct from old.role or new.status is distinct from old.status
     or new.access_until is distinct from old.access_until or new.user_id is distinct from old.user_id then
    raise exception 'Only the owner can change roles or access' using errcode = '42501';
  end if;
  return new;
end $$;

drop trigger if exists household_members_guard on household_members;
create trigger household_members_guard before insert or update on household_members
  for each row execute function gp_guard_member();

-- ------------------------------------------------------------------ row level security

alter table households enable row level security;
alter table household_members enable row level security;
alter table invites enable row level security;

drop policy if exists households_select on households;
create policy households_select on households for select using (owner_id = auth.uid() or gp_is_member(id));
drop policy if exists households_insert on households;
create policy households_insert on households for insert with check (owner_id = auth.uid());
drop policy if exists households_update on households;
create policy households_update on households for update using (owner_id = auth.uid()) with check (owner_id = auth.uid());

drop policy if exists members_select on household_members;
create policy members_select on household_members for select using (gp_is_member(household_id));
drop policy if exists members_insert on household_members;
create policy members_insert on household_members for insert with check (gp_member_role(household_id) = 'OWNER');
drop policy if exists members_update on household_members;
create policy members_update on household_members for update
  using (gp_member_role(household_id) = 'OWNER' or user_id = auth.uid());

-- Invites are only touched through the RPCs below.
drop policy if exists invites_owner on invites;
create policy invites_owner on invites for select using (gp_member_role(household_id) = 'OWNER');

do $$
declare t text;
begin
  foreach t in array array['pets','medications','dose_events','check_ins','weight_entries','symptom_entries','vet_visits'] loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists %I on %I', t || '_select', t);
    execute format('create policy %I on %I for select using (gp_is_member(household_id))', t || '_select', t);
    execute format('drop policy if exists %I on %I', t || '_insert', t);
    execute format('create policy %I on %I for insert with check (gp_is_member(household_id))', t || '_insert', t);
    execute format('drop policy if exists %I on %I', t || '_update', t);
    execute format('create policy %I on %I for update using (gp_is_member(household_id)) with check (gp_is_member(household_id))', t || '_update', t);
  end loop;
end $$;

-- ------------------------------------------------------------------ invites

create or replace function gp_create_invite(p_household text, p_role text, p_access_until bigint default null)
returns json
language plpgsql security definer set search_path = public as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  raw text;
  new_code text;
  expires timestamptz := now() + interval '7 days';
  i int;
begin
  if gp_member_role(p_household) is distinct from 'OWNER' then
    raise exception 'Only the owner can invite people' using errcode = '42501';
  end if;
  if p_role not in ('FAMILY', 'SITTER') then
    raise exception 'Unknown role %', p_role;
  end if;
  loop
    raw := '';
    for i in 1..8 loop
      raw := raw || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
    end loop;
    new_code := substr(raw, 1, 4) || '-' || substr(raw, 5, 4);
    exit when not exists (select 1 from invites i where i.code = new_code);
  end loop;
  insert into invites (code, household_id, role, access_until, created_by, expires_at)
  values (new_code, p_household, p_role, p_access_until, auth.uid(), expires);
  return json_build_object(
    'code', new_code, 'household_id', p_household, 'role', p_role,
    'access_until', p_access_until, 'expires_at', to_char(expires at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  );
end $$;

create or replace function gp_accept_invite(p_code text, p_display_name text)
returns json
language plpgsql security definer set search_path = public as $$
declare
  inv invites%rowtype;
  caller uuid := auth.uid();
  existing_id text;
  next_color int;
  now_ms bigint := gp_now_ms();
begin
  if caller is null then
    raise exception 'Sign in first' using errcode = '42501';
  end if;
  select * into inv from invites
   where code = upper(trim(p_code)) and used_at is null and expires_at > now()
   for update;
  if not found then
    raise exception 'invalid_code' using errcode = 'P0002';
  end if;

  -- This RPC is the sanctioned way into a household: let the member guard trigger through.
  perform set_config('goldenpaw.trusted', 'on', true);

  select id into existing_id from household_members where household_id = inv.household_id and user_id = caller;
  if existing_id is null then
    select coalesce(max(color_index), 0) + 1 into next_color from household_members where household_id = inv.household_id;
    existing_id := gen_random_uuid()::text;
    insert into household_members (id, household_id, user_id, display_name, role, color_index, access_until, status, created_at, updated_at)
    values (existing_id, inv.household_id, caller, left(coalesce(nullif(trim(p_display_name), ''), 'Helper'), 40),
            inv.role, next_color, inv.access_until, 'ACTIVE', now_ms, now_ms);
  else
    -- Re-joining (e.g. a sitter booked again): reactivate with the new role and window.
    update household_members
       set role = inv.role, access_until = inv.access_until, status = 'ACTIVE', updated_at = greatest(updated_at + 1, now_ms)
     where id = existing_id;
  end if;

  update invites set used_by = caller, used_at = now() where code = inv.code;
  perform set_config('goldenpaw.trusted', 'off', true);
  return json_build_object('household_id', inv.household_id, 'member_id', existing_id);
end $$;

revoke all on function gp_create_invite(text, text, bigint) from public;
revoke all on function gp_accept_invite(text, text) from public;
grant execute on function gp_create_invite(text, text, bigint) to authenticated;
grant execute on function gp_accept_invite(text, text) to authenticated;
