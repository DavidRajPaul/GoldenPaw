-- In-app account deletion (Google Play and App Store requirement; GDPR / DPDP right to erasure).
--
-- gp_delete_my_account() removes, for the signed-in user:
--   * households they own, and with them every pet, log, member and invite in them (cascade),
--     so shared care data they created doesn't outlive their account;
--   * their membership in other people's households (marked REMOVED, unlinked from the user);
--   * their push devices;
--   * the auth user itself (subscriptions cascade).
-- Data stays on the person's own phone until they also use "Delete all data" in Settings.

create or replace function gp_delete_my_account()
returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'Sign in first' using errcode = '42501';
  end if;

  delete from households where owner_id = uid;

  -- Leaving other teams is a sanctioned change: let the member guard trigger through.
  perform set_config('goldenpaw.trusted', 'on', true);
  update household_members
     set status = 'REMOVED', user_id = null, updated_at = greatest(updated_at + 1, gp_now_ms())
   where user_id = uid;
  perform set_config('goldenpaw.trusted', 'off', true);

  delete from devices where user_id = uid;
  delete from auth.users where id = uid;
end $$;

revoke all on function gp_delete_my_account() from public;
grant execute on function gp_delete_my_account() to authenticated;
