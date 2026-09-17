# Walidacja WolfTaxi 0.5.2

- `node --check` / `npm run check`: OK dla backendu.
- Migracja SQL jest idempotentna (`ADD COLUMN IF NOT EXISTS target_region_id`).
- `KOD + KURSEM` wysyła `status=course` + `regionId`.
- `KOD + DOJAZD` wysyła `status=driving_to_pickup` + `regionId`.
- `OK` nadal zgłasza rejon / dołącza do kolejki.
- Rejon docelowy jest osobny od bieżącego (`target_region_id` vs `current_region_id`).
- Panel kierowcy i dyspozytora pokazują cel jako `→ REJON`.
- Workflow podpisu zawiera poprawki wykrywania `apksigner` i parsera SHA-256.
