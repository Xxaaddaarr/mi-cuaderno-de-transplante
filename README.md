# Mi Cuaderno Tx

App de control de medicación y automonitoreo para pacientes con trasplante renal. Reemplaza el cuaderno de papel: recuerda cada toma a su hora, registra controles y análisis, y deja que el equipo de trasplante vea el seguimiento.

Esta app registra y recuerda. No da indicaciones médicas: las dosis las define el equipo de trasplante.

## Partes

| Carpeta | Qué es |
|---|---|
| `web/` | La interfaz (una sola página). Se publica en GitHub Pages para el equipo médico y va incluida dentro de la app de Android. |
| `app/` | App de Android (Expo). Muestra la página y programa las alarmas nativas. |
| `supabase/` | Base de datos: tablas y reglas de acceso. |

## Cómo funciona

- **Datos:** Supabase (Postgres). La página guarda primero en el celular y sube los cambios cuando hay internet (`outbox` en `web/src/app.html`).
- **Acceso:** cada paciente escribe solo sus datos. El paciente da acceso de solo lectura a un médico con `grant_access(email)`.
- **Cambios de tratamiento:** cada cambio de dosis, horario o frecuencia es una versión con fecha en `med_versions`, así el historial conserva la dosis de cada día.
- **Alarmas:** la página envía a la app las tomas de los próximos días y la app las programa como alarmas de reloj (`setAlarmClock`), que Android no retrasa por ahorro de batería. Si no se confirma, repite a los 10, 20, 30, 45 y 60 minutos.

## Comandos

```bash
cd web && npm install && npm run build      # genera web/dist/index.html y app/src/webapp.js
```

```bash
cd app && npx eas-cli@latest build -p android --profile preview   # APK en la nube de Expo
```

La página publicada vive en la rama `gh-pages` (copia de `web/dist/index.html`).
