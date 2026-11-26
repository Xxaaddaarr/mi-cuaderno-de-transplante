-- 0006 · Balance de líquidos de 7:00 a 7:00.
-- Cada vez que el paciente toma líquidos u orina, anota solo esa cantidad (con su hora).
-- "day" es el día en que empezó el periodo: de las 7:00 de ese día a las 7:00 del siguiente.
-- Los totales del periodo se guardan en days.agua (líquidos tomados) y days.diu (orina).

create table if not exists public.fluids (
  patient_id uuid not null references public.patients(id) on delete cascade,
  id text not null check (id ~ '^[A-Za-z0-9_-]{1,80}$'),
  day date not null,
  t text not null check (t ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  kind text not null check (kind in ('in', 'out')),   -- in: líquidos tomados · out: orina
  ml int not null check (ml between 1 and 5000),
  updated_at timestamptz not null default now(),
  primary key (patient_id, id)
);
create index if not exists fluids_patient_day on public.fluids (patient_id, day);
alter table public.fluids enable row level security;

drop policy if exists fluids_read on public.fluids;
create policy fluids_read on public.fluids for select to authenticated using (public.can_read_patient(patient_id));
drop policy if exists fluids_write on public.fluids;
create policy fluids_write on public.fluids for all to authenticated
  using (patient_id = auth.uid()) with check (patient_id = auth.uid());

drop trigger if exists fluids_touch on public.fluids;
create trigger fluids_touch before update on public.fluids for each row execute function public.touch_updated_at();
drop trigger if exists fluids_keep_pid on public.fluids;
create trigger fluids_keep_pid before update on public.fluids for each row execute function public.keep_patient_id();

revoke all on public.fluids from anon;
revoke truncate, references, trigger on public.fluids from authenticated;
grant select, insert, update, delete on public.fluids to authenticated;
