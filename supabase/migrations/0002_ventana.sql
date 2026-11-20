-- Ventana sin comida ni otras pastillas alrededor de una toma (por ejemplo tacrolimus: 1 h antes y 1 h después).
-- {"before": minutos, "after": minutos}; null si el medicamento no tiene ventana.
alter table public.med_versions add column if not exists win jsonb;
