-- =====================================================================
-- Neribo Vault: cloud sync schema (Phase 15)
-- Paste this whole script into the Supabase SQL editor and press Run.
-- It is safe to run more than once.
--
-- Step 1. Create one table, vault_items. Every vault row from the app is
--         stored as a JSON object in this single table, keyed by the
--         signed-in user, the kind (the app table name) and the row id.
-- Step 2. Add an index so "what changed since my last sync" is fast.
-- Step 3. Turn on Row Level Security so each person can only ever see
--         and change their own rows.
-- Step 4. Add four policies (select, insert, update, delete), each
--         restricted to the row owner.
--
-- Also decide, in Authentication settings in the Supabase dashboard,
-- whether email confirmation is required before people can sign in.
-- To remove your cloud data later, delete the rows in the Table editor.
-- =====================================================================

create table if not exists public.vault_items (
    user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
    kind       text    not null,
    id         text    not null,
    updated_at bigint  not null,
    is_deleted boolean not null default false,
    data       jsonb   not null,
    primary key (user_id, kind, id)
);

create index if not exists vault_items_user_updated_idx
    on public.vault_items (user_id, updated_at);

alter table public.vault_items enable row level security;

drop policy if exists "vault_items_select_own" on public.vault_items;
create policy "vault_items_select_own" on public.vault_items
    for select to authenticated
    using (auth.uid() = user_id);

drop policy if exists "vault_items_insert_own" on public.vault_items;
create policy "vault_items_insert_own" on public.vault_items
    for insert to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "vault_items_update_own" on public.vault_items;
create policy "vault_items_update_own" on public.vault_items
    for update to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "vault_items_delete_own" on public.vault_items;
create policy "vault_items_delete_own" on public.vault_items
    for delete to authenticated
    using (auth.uid() = user_id);
