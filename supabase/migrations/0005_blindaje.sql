-- 0005 · Blindaje tras la auditoría: formatos validados en la base, huella más estricta,
-- límites al médico y al admin, y registro de cambios del personal.

-- ---------- 1. Hora programada de cada toma (los registros ya no dependen del orden de los horarios) ----------
alter table public.doses add column if not exists sched text;
update public.doses d set sched = v.times[d.idx + 1]
from public.med_versions v
where d.sched is null and v.patient_id = d.patient_id and v.gid = d.med_gid
  and v.from_date <= d.day and (v.until_date is null or d.day <= v.until_date)
  and d.idx + 1 <= cardinality(v.times);

-- ---------- 2. Formatos: nada que no sea una hora, fecha, número o id válido entra a la base ----------
alter table public.doses
  add constraint doses_at_hhmm    check (at    is null or at    ~ '^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$'),
  add constraint doses_until_hhmm check (until is null or until ~ '^([01][0-9]|2[0-4]):[0-5][0-9]$'),
  add constraint doses_sched_hhmm check (sched is null or sched ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  add constraint doses_gid_fmt    check (med_gid ~ '^[A-Za-z0-9_-]{1,80}$'),
  add constraint doses_idx_rng    check (idx between 0 and 999),
  add constraint doses_status_ok  check (status in ('taken', 'skip', 'snooze')),
  add constraint doses_reason_len check (reason is null or length(reason) <= 200),
  add constraint doses_verif_fmt  check (verif is null or (jsonb_typeof(verif) = 'object'
      and coalesce(verif->>'kind', '') in ('photo', 'video', 'skipped')
      and (not verif ? 'at'   or (verif->>'at') ~ '^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$')
      and (not verif ? 'path' or ((verif->>'path') ~ '^[A-Za-z0-9/._-]{1,200}$' and (verif->>'path') not like '%..%'
                                  and (verif->>'path') like patient_id::text || '/%'))));
alter table public.readings
  add constraint readings_t_hhmm check (t is null or t ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  add constraint readings_id_fmt check (id ~ '^[A-Za-z0-9_-]{1,80}$');
alter table public.med_versions
  add constraint mv_ids_fmt   check (gid ~ '^[A-Za-z0-9_-]{1,80}$' and id ~ '^[A-Za-z0-9_-]{1,80}$'),
  add constraint mv_times_fmt check (cardinality(times) between 1 and 24
      and array_to_string(times, ',') ~ '^(([01][0-9]|2[0-3]):[0-5][0-9])(,([01][0-9]|2[0-3]):[0-5][0-9])*$'),
  add constraint mv_freq_fmt  check (freq->>'t' = 'd'
      or (freq->>'t' = 'alt' and coalesce(freq->>'start', '') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
      or (freq->>'t' = 'w' and coalesce(freq->>'dow', '') ~ '^[0-6]$')),
  add constraint mv_win_fmt   check (win is null or (jsonb_typeof(win->'before') = 'number' and jsonb_typeof(win->'after') = 'number')),
  add constraint mv_taper_fmt check (taper is null or (jsonb_typeof(taper->'mg') = 'number' and jsonb_typeof(taper->'n') = 'number'
      and jsonb_typeof(taper->'step') = 'number' and jsonb_typeof(taper->'every') = 'number'
      and coalesce(jsonb_typeof(taper->'min'), 'null') in ('number', 'null')
      and coalesce(taper->>'date', '') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')),
  add constraint mv_text_len  check (length(name) <= 120 and length(coalesce(strength, '')) <= 40 and length(coalesce(note, '')) <= 500
      and length(coalesce(change_reason, '')) <= 500);
alter table public.days     add constraint days_len     check (length(coalesce(nota, '')) <= 2000 and cardinality(sint) <= 20);
alter table public.profiles add constraint profiles_len check (length(coalesce(display_name, '')) <= 80);
alter table public.staff    add constraint staff_len    check (length(display_name) <= 80);

-- ---------- 3. Huella: motivo que no se hereda, pacientes que no se mueven, autor que no se falsifica ----------
create or replace function public.mv_clear_stale_reason() returns trigger
language plpgsql set search_path = public as $$
begin
  if new.change_reason is not distinct from old.change_reason then new.change_reason := null; end if;
  return new;
end $$;
drop trigger if exists med_versions_reason on public.med_versions;
create trigger med_versions_reason before update on public.med_versions
  for each row execute function public.mv_clear_stale_reason();

create or replace function public.keep_patient_id() returns trigger
language plpgsql set search_path = public as $$
begin
  if tg_table_name = 'patients' then
    if new.id is distinct from old.id then raise exception 'No se puede cambiar el paciente'; end if;
  elsif new.patient_id is distinct from old.patient_id then
    raise exception 'No se puede mover un registro a otro paciente';
  end if;
  return new;
end $$;
do $$ declare t text; begin
  foreach t in array array['patients', 'med_versions', 'labs', 'doses', 'days', 'readings', 'care_team'] loop
    execute format('drop trigger if exists %I_keep_pid on public.%I', t, t);
    execute format('create trigger %I_keep_pid before update on public.%I for each row execute function public.keep_patient_id()', t, t);
  end loop;
end $$;

-- El paciente crea y actualiza su ficha, pero no la borra (borrarla se llevaría todo sin dejar rastro).
drop policy if exists patients_write on public.patients;
drop policy if exists patients_insert on public.patients;
drop policy if exists patients_update on public.patients;
create policy patients_insert on public.patients for insert to authenticated with check (id = auth.uid());
create policy patients_update on public.patients for update to authenticated using (id = auth.uid()) with check (id = auth.uid());
drop trigger if exists patients_audit on public.patients;
create trigger patients_audit after insert or update or delete on public.patients
  for each row execute function public.audit_row();

-- El nombre en la huella sale solo del registro de personal (un paciente no puede hacerse pasar por médico).
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
    coalesce((select s.display_name from public.staff s where s.user_id = who), ''),
    pid, tg_table_name,
    case tg_table_name when 'med_versions' then j ->> 'id' when 'labs' then j ->> 'day' end,
    lower(tg_op), b, a,
    case when tg_op in ('INSERT', 'UPDATE') then to_jsonb(new) ->> 'change_reason' end
  );
  return null;
end $$;

-- ---------- 4. El médico solo cambia en la ficha lo que le toca (fechas de trasplante y de dosaje) ----------
create or replace function public.patients_doctor_cols() returns trigger
language plpgsql set search_path = public as $$
begin
  if auth.uid() is distinct from old.id and public.staff_role() is not null then
    if (to_jsonb(new) - 'tx_date' - 'lab_date' - 'lab_tentative' - 'updated_at')
       is distinct from (to_jsonb(old) - 'tx_date' - 'lab_date' - 'lab_tentative' - 'updated_at') then
      raise exception 'El personal solo puede cambiar la fecha de trasplante y la del dosaje';
    end if;
  end if;
  return new;
end $$;
drop trigger if exists patients_doctor_cols on public.patients;
create trigger patients_doctor_cols before update on public.patients
  for each row execute function public.patients_doctor_cols();

-- ---------- 5. Personal: el admin no puede cambiarse a sí mismo y todo cambio queda registrado ----------
create table if not exists public.staff_log (
  id bigint generated always as identity primary key,
  at timestamptz not null default now(),
  actor uuid, actor_name text not null default '',
  target uuid not null, action text not null,
  before jsonb, after jsonb
);
alter table public.staff_log enable row level security;
drop policy if exists staff_log_read on public.staff_log;
create policy staff_log_read on public.staff_log for select to authenticated using (public.staff_role() = 'admin');
revoke insert, update, delete, truncate on public.staff_log from authenticated, anon;

create or replace function public.staff_audit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  insert into public.staff_log (actor, actor_name, target, action, before, after)
  values (auth.uid(), coalesce((select s.display_name from public.staff s where s.user_id = auth.uid()), ''),
          coalesce(new.user_id, old.user_id), lower(tg_op),
          case when tg_op <> 'INSERT' then to_jsonb(old) end, case when tg_op <> 'DELETE' then to_jsonb(new) end);
  return null;
end $$;
drop trigger if exists staff_audit on public.staff;
create trigger staff_audit after insert or update or delete on public.staff
  for each row execute function public.staff_audit();

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
  if length(trim(coalesce(p_name, ''))) not between 1 and 80 then raise exception 'Escribe un nombre de hasta 80 letras'; end if;
  select id into uid from auth.users where lower(email) = em;
  if uid = auth.uid() then raise exception 'No puedes cambiar tu propia cuenta'; end if;
  if uid is not null and exists (select 1 from public.staff s where s.user_id = uid and s.role <> p_role) then
    raise exception 'Esa persona ya es del personal con otro rol';
  end if;
  if uid is null then
    if length(coalesce(p_password, '')) < 10 then raise exception 'La contraseña debe tener al menos 10 caracteres'; end if;
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
  on conflict (user_id) do update set display_name = excluded.display_name, active = true;
  return uid;
end $$;
revoke all on function public.admin_create_staff(text, text, text, text) from public, anon;
grant execute on function public.admin_create_staff(text, text, text, text) to authenticated;

-- ---------- 6. Limpieza de permisos ----------
drop function if exists public.grant_access(text);
revoke truncate, references, trigger on all tables in schema public from anon, authenticated;
revoke insert, update, delete on all tables in schema public from anon;
