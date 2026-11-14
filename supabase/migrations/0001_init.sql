-- Mi Cuaderno Tx · esquema inicial
-- Un paciente escribe solo sus datos. Su equipo médico (care_team) solo puede leerlos.
-- Los ids de filas creadas en el celular son texto generado en el cliente, para poder trabajar sin internet.

-- ---------- tablas ----------
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  role text not null default 'patient' check (role in ('patient','clinician')),
  display_name text not null default '',
  created_at timestamptz not null default now()
);

create table public.patients (
  id uuid primary key references auth.users(id) on delete cascade,
  tx_date date,                 -- fecha del trasplante
  lab_date date,                -- próximo dosaje de tacrolimus
  lab_tentative boolean not null default false,
  base_date date not null default current_date,  -- desde cuándo se registran cambios de tratamiento
  updated_at timestamptz not null default now()
);

create table public.care_team (
  patient_id uuid not null references public.patients(id) on delete cascade,
  clinician_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (patient_id, clinician_id)
);

-- Cada cambio de dosis, horario o frecuencia es una versión nueva con fechas de vigencia.
-- gid identifica al medicamento a lo largo de sus versiones.
create table public.med_versions (
  patient_id uuid not null references public.patients(id) on delete cascade,
  id text not null,
  gid text not null,
  name text not null,
  strength text not null default '',
  unit text not null default 'tableta',
  qty numeric,                  -- unidades por toma (null si la dosis va en descenso)
  type text not null default 'Otro',
  times text[] not null,        -- horas HH:MM
  freq jsonb not null default '{"t":"d"}',   -- {t:'d'} | {t:'alt',start} | {t:'w',dow}
  note text not null default '',
  strict boolean not null default false,     -- horario estricto (inmunosupresores)
  taper jsonb,                  -- {date,mg,n,step,every,min}
  from_date date not null,
  until_date date,
  updated_at timestamptz not null default now(),
  primary key (patient_id, id)
);

-- Una fila por toma registrada (idx = posición del horario en la versión vigente ese día).
create table public.doses (
  patient_id uuid not null references public.patients(id) on delete cascade,
  day date not null,
  med_gid text not null,
  idx int not null,
  status text not null check (status in ('taken','skip','snooze')),
  at text,                      -- hora real HH:MM
  until text,                   -- pospuesta hasta HH:MM
  qty numeric,
  mg numeric,
  n int,
  reason text,
  nt boolean not null default false,  -- sin horario fijo de referencia (historial del cuaderno)
  updated_at timestamptz not null default now(),
  primary key (patient_id, day, med_gid, idx)
);

create table public.days (
  patient_id uuid not null references public.patients(id) on delete cascade,
  day date not null,
  peso numeric,
  diu int,                      -- diuresis 24 h, mL
  dren int,                     -- drenaje, mL
  sint text[] not null default '{}',
  nota text not null default '',
  updated_at timestamptz not null default now(),
  primary key (patient_id, day)
);

create table public.readings (
  patient_id uuid not null references public.patients(id) on delete cascade,
  id text not null,
  day date not null,
  t text,                       -- hora HH:MM
  pas int,
  pad int,
  temp numeric,
  updated_at timestamptz not null default now(),
  primary key (patient_id, id)
);

create table public.labs (
  patient_id uuid not null references public.patients(id) on delete cascade,
  day date not null,
  tac numeric,                  -- dosaje de tacrolimus, ng/mL
  hb numeric,                   -- hemoglobina, g/dL
  urea numeric,                 -- mg/dL
  crea numeric,                 -- creatinina, mg/dL
  updated_at timestamptz not null default now(),
  primary key (patient_id, day)
);

create index doses_patient_day on public.doses (patient_id, day);
create index readings_patient_day on public.readings (patient_id, day);

-- ---------- updated_at ----------
create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$ begin new.updated_at = now(); return new; end $$;

do $$ declare t text; begin
  foreach t in array array['patients','med_versions','doses','days','readings','labs'] loop
    execute format('create trigger %I_touch before update on public.%I for each row execute function public.touch_updated_at()', t, t);
  end loop;
end $$;

-- ---------- perfil automático al registrarse ----------
create or replace function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, role, display_name)
  values (
    new.id,
    case when new.raw_user_meta_data->>'role' = 'clinician' then 'clinician' else 'patient' end,
    coalesce(new.raw_user_meta_data->>'display_name', '')
  );
  return new;
end $$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------- acceso ----------
create or replace function public.can_read_patient(pid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select pid = auth.uid()
      or exists (select 1 from public.care_team c where c.patient_id = pid and c.clinician_id = auth.uid());
$$;
revoke all on function public.can_read_patient(uuid) from public, anon;
grant execute on function public.can_read_patient(uuid) to authenticated;

alter table public.profiles enable row level security;
alter table public.patients enable row level security;
alter table public.care_team enable row level security;
alter table public.med_versions enable row level security;
alter table public.doses enable row level security;
alter table public.days enable row level security;
alter table public.readings enable row level security;
alter table public.labs enable row level security;

-- perfiles: cada uno el suyo; paciente y médico vinculados se ven entre sí
create policy profiles_read on public.profiles for select to authenticated using (
  id = auth.uid()
  or exists (select 1 from public.care_team c where (c.patient_id = auth.uid() and c.clinician_id = profiles.id)
                                                 or (c.clinician_id = auth.uid() and c.patient_id = profiles.id))
);
create policy profiles_update on public.profiles for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- paciente
create policy patients_read on public.patients for select to authenticated using (public.can_read_patient(id));
create policy patients_write on public.patients for all to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

-- equipo médico: el paciente ve y quita; el médico ve sus vínculos. Agregar solo con grant_access().
create policy care_team_read on public.care_team for select to authenticated
  using (patient_id = auth.uid() or clinician_id = auth.uid());
create policy care_team_delete on public.care_team for delete to authenticated
  using (patient_id = auth.uid());

-- datos clínicos: lee el paciente o su equipo; escribe solo el paciente
do $$ declare t text; begin
  foreach t in array array['med_versions','doses','days','readings','labs'] loop
    execute format('create policy %I_read on public.%I for select to authenticated using (public.can_read_patient(patient_id))', t, t);
    execute format('create policy %I_write on public.%I for all to authenticated using (patient_id = auth.uid()) with check (patient_id = auth.uid())', t, t);
  end loop;
end $$;

-- El paciente da acceso de lectura a un médico que ya tiene cuenta.
create or replace function public.grant_access(clinician_email text) returns text
language plpgsql security definer set search_path = public, auth as $$
declare cid uuid;
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  if not exists (select 1 from public.patients where id = auth.uid()) then return 'no_patient'; end if;
  select u.id into cid from auth.users u where lower(u.email) = lower(trim(clinician_email));
  if cid is null then return 'not_found'; end if;
  if cid = auth.uid() then return 'self'; end if;
  insert into public.care_team (patient_id, clinician_id) values (auth.uid(), cid) on conflict do nothing;
  return 'ok';
end $$;
revoke all on function public.grant_access(text) from public, anon;
grant execute on function public.grant_access(text) to authenticated;
