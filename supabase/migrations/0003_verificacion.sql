-- Verificación de tomas con foto (versión A) o video (versión B) en vivo.
-- patients.verify: {"mode": "off" | "photo" | "video", "scope": "strict" | "all"}
alter table public.patients add column if not exists verify jsonb not null default '{"mode":"off","scope":"strict"}'::jsonb;
-- doses.verif: {"kind": "photo" | "video" | "skipped", "path": "...", "at": "HH:MM:SS", "up": true|false}
alter table public.doses add column if not exists verif jsonb;

-- Archivos privados: cada paciente sube solo a su carpeta (<su id>/...);
-- lo ven el paciente y quien pueda ver al paciente (can_read_patient).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('verificaciones', 'verificaciones', false, 8388608, array['image/jpeg', 'video/webm', 'video/mp4'])
on conflict (id) do nothing;

drop policy if exists verif_insert on storage.objects;
create policy verif_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'verificaciones' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists verif_read on storage.objects;
create policy verif_read on storage.objects for select to authenticated
  using (bucket_id = 'verificaciones' and public.can_read_patient(((storage.foldername(name))[1])::uuid));
