-- Aplicada en Supabase el 2026-10-23 (migración widget_accesos)
create table if not exists public.widget_accesos (
  id bigint generated always as identity primary key,
  nombre text not null,
  token_hash text not null unique,
  activo boolean not null default true,
  created_at timestamptz not null default now(),
  last_used_at timestamptz
);
alter table public.widget_accesos enable row level security;
