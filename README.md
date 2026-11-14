# Mi Cuaderno Tx

App de control de medicación y automonitoreo para pacientes con trasplante renal. Reemplaza el cuaderno de papel: recuerda cada toma a su hora, registra controles y análisis, y deja que el equipo de trasplante vea el seguimiento.

## Partes

- `supabase/` — base de datos (Postgres en Supabase): tablas, reglas de acceso y migraciones.
- `app/` — app Android (Expo / React Native): alarmas, registro de tomas, controles y análisis. *(en construcción)*
- `web/` — página para el equipo médico, de solo lectura. *(en construcción)*

## Acceso a los datos

- Cada paciente escribe solo sus propios datos.
- El paciente da acceso de lectura a un médico con `grant_access(email)`; el médico nunca puede modificar nada.
- Los cambios de dosis, horario o frecuencia se guardan como versiones con fecha (`med_versions`), así el historial conserva la dosis que correspondía cada día.

Esta app registra y recuerda. No da indicaciones médicas: las dosis las define el equipo de trasplante.
