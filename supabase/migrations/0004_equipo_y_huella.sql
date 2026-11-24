-- 0004 · Niveles y tolerancia por medicamento, agua, personal de la unidad y huella de cambios.

-- ---------- medicamentos: nivel de alarma, tolerancia y motivo del cambio ----------
alter table public.med_versions add column if not exists level text check (level in ('critical', 'prophylactic', 'flexible'));
alter table public.med_versions add column if not exists tol int check (tol >= 0);       -- minutos de tolerancia
alter table public.med_versions add column if not exists change_reason text;              -- motivo de la última escritura

-- ---------- agua ----------
alter table public.days add column if not exists agua int;                                -- mL tomados en el día
alter table public.patients add column if not exists water_goal int not null default 3000; -- meta diaria en mL

-- ---------- personal de la unidad: todos ven a todos los pacientes ----------
create table if not exists public.staff (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('admin', 'doctor', 'nurse')),
  display_name text not null default '',
  active boolean not null default true,
  created_at timestamptz not null default now()
);
alter table public.staff enable row level security;

create or replace function public.staff_role() returns text
language sql stable security definer set search_path = public as $$
  select role from public.staff where user_id = auth.uid() and active
$$;
revoke all on function public.staff_role() from public, anon;
grant execute on function public.staff_role() to authenticated;

drop policy if exists staff_read on public.staff;
create policy staff_read on public.staff for select to authenticated
  using (user_id = auth.uid() or public.staff_role() is not null);
-- El personal solo se crea o cambia con las funciones del admin (sin políticas de escritura).

-- Lectura: el paciente, su equipo vinculado y todo el personal activo de la unidad.
create or replace function public.can_read_patient(pid uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select pid = auth.uid()
      or exists (select 1 from public.care_team c where c.patient_id = pid and c.clinician_id = auth.uid())
      or exists (select 1 from public.staff s where s.user_id = auth.uid() and s.active)
$$;

drop policy if exists profiles_read on public.profiles;
create policy profiles_read on public.profiles for select to authenticated using (
  id = auth.uid()
  or public.staff_role() is not null
  or exists (select 1 from public.care_team c where (c.patient_id = auth.uid() and c.clinician_id = profiles.id)
                                                 or (c.clinician_id = auth.uid() and c.patient_id = profiles.id))
);

-- Escritura clínica del personal: solo los médicos cambian recetas, análisis y fechas de dosaje.
drop policy if exists med_versions_doctor_write on public.med_versions;
create policy med_versions_doctor_write on public.med_versions for all to authenticated
  using (public.staff_role() = 'doctor') with check (public.staff_role() = 'doctor');
drop policy if exists labs_doctor_write on public.labs;
create policy labs_doctor_write on public.labs for all to authenticated
  using (public.staff_role() = 'doctor') with check (public.staff_role() = 'doctor');
drop policy if exists patients_doctor_update on public.patients;
create policy patients_doctor_update on public.patients for update to authenticated
  using (public.staff_role() = 'doctor') with check (public.staff_role() = 'doctor');

-- ---------- niveles por defecto de lo que ya existe ----------
update public.med_versions set
  level = case
    when strict then 'critical'
    when type in ('Antibiótico', 'Antiviral', 'Antifúngico') then 'prophylactic'
    else 'flexible' end,
  tol = case
    when strict then 1
    when type in ('Antibiótico', 'Antiviral', 'Antifúngico') then 15
    else 60 end
where level is null;

-- ---------- huella de cambios: la escribe la base de datos, nadie la puede editar ni borrar ----------
create table if not exists public.audit_log (
  id bigint generated always as identity primary key,
  at timestamptz not null default now(),
  actor uuid,
  actor_role text,
  actor_name text not null default '',
  patient_id uuid not null,
  entity text not null,
  entity_id text,
  action text not null check (action in ('insert', 'update', 'delete')),
  before jsonb,
  after jsonb,
  reason text
);
create index if not exists audit_log_patient_at on public.audit_log (patient_id, at desc);
alter table public.audit_log enable row level security;
drop policy if exists audit_read on public.audit_log;
create policy audit_read on public.audit_log for select to authenticated using (public.can_read_patient(patient_id));
revoke insert, update, delete, truncate on public.audit_log from authenticated, anon;

create or replace function public.audit_row() returns trigger
language plpgsql security definer set search_path = public as $$
declare
  b jsonb := case when tg_op in ('UPDATE', 'DELETE') then to_jsonb(old) - 'updated_at' - 'change_reason' end;
  a jsonb := case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) - 'updated_at' - 'change_reason' end;
  j jsonb := coalesce(a, b);
  pid uuid := coalesce(j ->> 'patient_id', j ->> 'id')::uuid;
  who uuid := auth.uid();
begin
  if tg_op = 'UPDATE' and a = b then return null; end if;
  insert into public.audit_log (actor, actor_role, actor_name, patient_id, entity, entity_id, action, before, after, reason)
  values (
    who,
    coalesce(public.staff_role(), case when who = pid then 'patient' end, case when who is null then 'system' else 'other' end),
    coalesce((select s.display_name from public.staff s where s.user_id = who),
             (select p.display_name from public.profiles p where p.id = who), ''),
    pid,
    tg_table_name,
    case tg_table_name when 'med_versions' then j ->> 'id' when 'labs' then j ->> 'day' end,
    lower(tg_op), b, a,
    case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) ->> 'change_reason' end
  );
  return null;
end $$;

drop trigger if exists med_versions_audit on public.med_versions;
create trigger med_versions_audit after insert or update or delete on public.med_versions
  for each row execute function public.audit_row();
drop trigger if exists labs_audit on public.labs;
create trigger labs_audit after insert or update or delete on public.labs
  for each row execute function public.audit_row();
drop trigger if exists patients_audit on public.patients;
create trigger patients_audit after update on public.patients
  for each row execute function public.audit_row();

-- ---------- funciones del admin para el personal ----------
create or replace function public.admin_create_staff(p_email text, p_name text, p_role text, p_password text)
returns uuid
language plpgsql security definer set search_path = public, auth, extensions as $$
declare
  uid uuid;
  em text := lower(trim(p_email));
begin
  if public.staff_role() is distinct from 'admin' then raise exception 'Solo el admin puede crear cuentas del personal'; end if;
  if p_role not in ('admin', 'doctor', 'nurse') then raise exception 'Rol inválido'; end if;
  if em !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then raise exception 'Correo inválido'; end if;
  select id into uid from auth.users where lower(email) = em;
  if uid is null then
    if length(coalesce(p_password, '')) < 8 then raise exception 'La contraseña debe tener al menos 8 caracteres'; end if;
    uid := gen_random_uuid();
    insert into auth.users (instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
                            raw_app_meta_data, raw_user_meta_data, created_at, updated_at,
                            confirmation_token, recovery_token, email_change_token_new, email_change)
    values ('00000000-0000-0000-0000-000000000000', uid, 'authenticated', 'authenticated', em,
            extensions.crypt(p_password, extensions.gen_salt('bf')), now(),
            '{"provider":"email","providers":["email"]}', jsonb_build_object('display_name', p_name),
            now(), now(), '', '', '', '');
    insert into auth.identities (provider_id, user_id, identity_data, provider, last_sign_in_at, created_at, updated_at)
    values (uid::text, uid, jsonb_build_object('sub', uid::text, 'email', em, 'email_verified', true), 'email', now(), now(), now());
  end if;
  insert into public.staff (user_id, role, display_name, active) values (uid, p_role, trim(p_name), true)
  on conflict (user_id) do update set role = excluded.role, display_name = excluded.display_name, active = true;
  return uid;
end $$;
revoke all on function public.admin_create_staff(text, text, text, text) from public, anon;
grant execute on function public.admin_create_staff(text, text, text, text) to authenticated;

create or replace function public.admin_set_staff_active(p_user uuid, p_active boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
  if public.staff_role() is distinct from 'admin' then raise exception 'Solo el admin puede cambiar al personal'; end if;
  if p_user = auth.uid() and not p_active then raise exception 'No puedes desactivar tu propia cuenta de admin'; end if;
  update public.staff set active = p_active where user_id = p_user;
end $$;
revoke all on function public.admin_set_staff_active(uuid, boolean) from public, anon;
grant execute on function public.admin_set_staff_active(uuid, boolean) to authenticated;
