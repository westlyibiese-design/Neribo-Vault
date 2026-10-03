-- =====================================================================
-- Neribo Vault: account deletion function
-- Paste this whole script into the Supabase SQL editor and press Run.
-- It is safe to run more than once.
--
-- It adds one function, delete_my_account(). When a signed-in person
-- taps "Delete my cloud data and account" in the app, the function:
--   1. removes every one of their rows in vault_items, and
--   2. removes their account.
-- It only ever acts on the person who calls it, and nobody who is not
-- signed in can call it.
-- =====================================================================

create or replace function public.delete_my_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    caller uuid := auth.uid();
begin
    if caller is null then
        raise exception 'Not signed in' using errcode = '28000';
    end if;
    delete from public.vault_items where user_id = caller;
    delete from auth.users where id = caller;
end;
$$;

revoke all on function public.delete_my_account() from public;
revoke all on function public.delete_my_account() from anon;
revoke all on function public.delete_my_account() from authenticated;
grant execute on function public.delete_my_account() to authenticated;
