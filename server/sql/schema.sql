CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY,
  email text UNIQUE NOT NULL,
  password_hash text NOT NULL,
  display_name text NOT NULL DEFAULT '',
  roles text[] NOT NULL DEFAULT ARRAY['driver']::text[],
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS drivers (
  id uuid PRIMARY KEY,
  user_id uuid UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  taxi_id text UNIQUE NOT NULL,
  number integer NOT NULL,
  name text NOT NULL,
  email text UNIQUE NOT NULL,
  password_hash text NOT NULL,
  vehicle_id text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  on_shift boolean NOT NULL DEFAULT false,
  status text NOT NULL DEFAULT 'offline',
  manual_tariff_allowed boolean NOT NULL DEFAULT true,
  current_region_id text,
  detected_region_id text,
  current_tariff_id text,
  current_fare_zone_id text,
  active_order_id text,
  last_lat double precision,
  last_lng double precision,
  last_speed double precision,
  last_heading double precision,
  last_accuracy double precision,
  last_location_at timestamptz,
  online boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Migracja instalacji 0.3.x: dodaj user_id i przenieś istniejące konta kierowców do users.
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS user_id uuid;
INSERT INTO users(id,email,password_hash,display_name,roles,enabled,created_at,updated_at)
SELECT d.id,d.email,d.password_hash,d.name,ARRAY['driver']::text[],d.enabled,d.created_at,d.updated_at
FROM drivers d
ON CONFLICT (email) DO UPDATE SET
  password_hash=EXCLUDED.password_hash,
  display_name=CASE WHEN users.display_name='' THEN EXCLUDED.display_name ELSE users.display_name END,
  roles=CASE WHEN 'driver'=ANY(users.roles) THEN users.roles ELSE array_append(users.roles,'driver') END,
  enabled=EXCLUDED.enabled,
  updated_at=now();
UPDATE drivers d SET user_id=u.id FROM users u WHERE d.user_id IS NULL AND lower(d.email)=lower(u.email);
CREATE UNIQUE INDEX IF NOT EXISTS idx_drivers_user_id ON drivers(user_id) WHERE user_id IS NOT NULL;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='drivers_user_id_fkey') THEN
    ALTER TABLE drivers ADD CONSTRAINT drivers_user_id_fkey FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE;
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS tariffs (
  id text PRIMARY KEY,
  name text NOT NULL,
  short_name text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  start_fee numeric(10,2) NOT NULL DEFAULT 0,
  price_per_km numeric(10,2) NOT NULL DEFAULT 0,
  waiting_price_per_hour numeric(10,2) NOT NULL DEFAULT 0,
  minimum_fare numeric(10,2) NOT NULL DEFAULT 0,
  sort_order integer NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS regions (
  id text PRIMARY KEY,
  name text NOT NULL,
  short_name text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  queue_enabled boolean NOT NULL DEFAULT true,
  priority integer NOT NULL DEFAULT 0,
  polygon jsonb NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE IF NOT EXISTS fare_zones (
  id text PRIMARY KEY,
  name text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  multiplier numeric(10,4) NOT NULL DEFAULT 1,
  default_tariff_id text,
  priority integer NOT NULL DEFAULT 0,
  polygon jsonb NOT NULL DEFAULT '[]'::jsonb
);

CREATE TABLE IF NOT EXISTS queue_entries (
  region_id text NOT NULL REFERENCES regions(id) ON DELETE CASCADE,
  driver_id uuid NOT NULL REFERENCES drivers(id) ON DELETE CASCADE,
  joined_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(region_id, driver_id),
  UNIQUE(driver_id)
);

CREATE TABLE IF NOT EXISTS orders (
  id text PRIMARY KEY,
  pickup_address text NOT NULL DEFAULT '',
  destination_address text NOT NULL DEFAULT '',
  pickup_region_id text,
  pickup_fare_zone_id text,
  destination_fare_zone_id text,
  tariff_id text,
  assigned_driver_id uuid REFERENCES drivers(id),
  offered_driver_id uuid REFERENCES drivers(id),
  passenger_name text NOT NULL DEFAULT '',
  passenger_phone text NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  passenger_count integer NOT NULL DEFAULT 1,
  card_required boolean NOT NULL DEFAULT false,
  estimated_price numeric(10,2) NOT NULL DEFAULT 0,
  final_price numeric(10,2) NOT NULL DEFAULT 0,
  payment_method text NOT NULL DEFAULT 'cash',
  status text NOT NULL DEFAULT 'created',
  offer_expires_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_orders_offered_driver ON orders(offered_driver_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_assigned_driver ON orders(assigned_driver_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_status_updated ON orders(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS messages (
  id bigserial PRIMARY KEY,
  type text NOT NULL DEFAULT 'info',
  title text NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  requires_ack boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS created_by uuid REFERENCES users(id);

CREATE TABLE IF NOT EXISTS audit_log (
  id bigserial PRIMARY KEY,
  user_id uuid REFERENCES users(id),
  action text NOT NULL,
  entity_type text NOT NULL DEFAULT '',
  entity_id text NOT NULL DEFAULT '',
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON audit_log(created_at DESC);

-- WolfTaxi 0.5 / RT3000-core --------------------------------------------------
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS priority_points integer NOT NULL DEFAULT 0;
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS blocked_reason text NOT NULL DEFAULT '';
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS tts_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS exchange_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE queue_entries ADD COLUMN IF NOT EXISTS priority_score integer NOT NULL DEFAULT 0;
ALTER TABLE queue_entries ADD COLUMN IF NOT EXISTS penalty_until timestamptz;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS dispatch_mode text NOT NULL DEFAULT 'queue';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS forced boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS source text NOT NULL DEFAULT 'dispatch';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS scheduled_for timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS luggage boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS pet boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS english_required boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS mine_warning boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS requirements jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS accepted_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS arrived_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS started_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancelled_reason text NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_orders_exchange ON orders(status,dispatch_mode,scheduled_for,created_at DESC);

ALTER TABLE messages ADD COLUMN IF NOT EXISTS target_type text NOT NULL DEFAULT 'all';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS target_id text NOT NULL DEFAULT '';
ALTER TABLE messages ADD COLUMN IF NOT EXISTS voice_read boolean NOT NULL DEFAULT true;

CREATE TABLE IF NOT EXISTS message_ack (
  message_id bigint NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  acknowledged_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(message_id,user_id)
);

CREATE TABLE IF NOT EXISTS safety_alerts (
  id bigserial PRIMARY KEY,
  driver_id uuid NOT NULL REFERENCES drivers(id) ON DELETE CASCADE,
  alert_type text NOT NULL DEFAULT 'sos',
  status text NOT NULL DEFAULT 'active',
  note text NOT NULL DEFAULT '',
  lat double precision,
  lng double precision,
  created_at timestamptz NOT NULL DEFAULT now(),
  acknowledged_at timestamptz,
  acknowledged_by uuid REFERENCES users(id),
  closed_at timestamptz,
  closed_by uuid REFERENCES users(id)
);
CREATE INDEX IF NOT EXISTS idx_safety_alerts_active ON safety_alerts(status,created_at DESC);

CREATE TABLE IF NOT EXISTS driver_events (
  id bigserial PRIMARY KEY,
  driver_id uuid REFERENCES drivers(id) ON DELETE CASCADE,
  event_type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_driver_events_driver ON driver_events(driver_id,created_at DESC);


-- WolfTaxi 0.5.2 / RT3000 region+action --------------------------------------
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS target_region_id text;

-- WolfTaxi 0.5.1 / RT3000 terminal -------------------------------------------
CREATE TABLE IF NOT EXISTS message_response (
  message_id bigint NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  answer text NOT NULL CHECK (answer IN ('yes','no')),
  responded_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(message_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_message_response_message ON message_response(message_id,responded_at DESC);

-- WolfTaxi 0.6 FULL RT3000 ----------------------------------------------------
ALTER TABLE regions ADD COLUMN IF NOT EXISTS numeric_code text;
CREATE UNIQUE INDEX IF NOT EXISTS idx_regions_numeric_code ON regions(numeric_code) WHERE numeric_code IS NOT NULL AND numeric_code<>'';

ALTER TABLE drivers ADD COLUMN IF NOT EXISTS supports_card boolean NOT NULL DEFAULT true;
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS pet_allowed boolean NOT NULL DEFAULT true;
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS luggage_capacity integer NOT NULL DEFAULT 2;
ALTER TABLE drivers ADD COLUMN IF NOT EXISTS english_level integer NOT NULL DEFAULT 0;

ALTER TABLE orders ADD COLUMN IF NOT EXISTS client_id bigint;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS company_id bigint;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS voucher_code text NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cost_center text NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS booking_ref text NOT NULL DEFAULT '';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS settlement_status text NOT NULL DEFAULT 'open';
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cashless boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS clients (
  id bigserial PRIMARY KEY,
  name text NOT NULL DEFAULT '',
  phone text NOT NULL DEFAULT '',
  email text NOT NULL DEFAULT '',
  notes text NOT NULL DEFAULT '',
  blocked boolean NOT NULL DEFAULT false,
  rides_count integer NOT NULL DEFAULT 0,
  total_spend numeric(12,2) NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_clients_phone ON clients(phone);
CREATE INDEX IF NOT EXISTS idx_clients_name ON clients(lower(name));

CREATE TABLE IF NOT EXISTS companies (
  id bigserial PRIMARY KEY,
  name text NOT NULL,
  nip text NOT NULL DEFAULT '',
  billing_email text NOT NULL DEFAULT '',
  phone text NOT NULL DEFAULT '',
  active boolean NOT NULL DEFAULT true,
  monthly_limit numeric(12,2) NOT NULL DEFAULT 0,
  notes text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_companies_nip ON companies(nip) WHERE nip<>'';

CREATE TABLE IF NOT EXISTS vouchers (
  code text PRIMARY KEY,
  company_id bigint REFERENCES companies(id) ON DELETE SET NULL,
  client_id bigint REFERENCES clients(id) ON DELETE SET NULL,
  amount numeric(10,2) NOT NULL DEFAULT 0,
  remaining_amount numeric(10,2) NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true,
  valid_from timestamptz NOT NULL DEFAULT now(),
  valid_until timestamptz,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  used_at timestamptz
);

CREATE TABLE IF NOT EXISTS settlements (
  id bigserial PRIMARY KEY,
  order_id text UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
  driver_id uuid REFERENCES drivers(id) ON DELETE SET NULL,
  company_id bigint REFERENCES companies(id) ON DELETE SET NULL,
  client_id bigint REFERENCES clients(id) ON DELETE SET NULL,
  payment_method text NOT NULL DEFAULT 'cash',
  gross_amount numeric(12,2) NOT NULL DEFAULT 0,
  driver_amount numeric(12,2) NOT NULL DEFAULT 0,
  company_amount numeric(12,2) NOT NULL DEFAULT 0,
  voucher_amount numeric(12,2) NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'open',
  created_at timestamptz NOT NULL DEFAULT now(),
  settled_at timestamptz
);
CREATE INDEX IF NOT EXISTS idx_settlements_driver_created ON settlements(driver_id,created_at DESC);

CREATE TABLE IF NOT EXISTS shift_sessions (
  id bigserial PRIMARY KEY,
  driver_id uuid NOT NULL REFERENCES drivers(id) ON DELETE CASCADE,
  started_at timestamptz NOT NULL DEFAULT now(),
  ended_at timestamptz,
  start_odometer numeric(12,2),
  end_odometer numeric(12,2),
  cash_total numeric(12,2) NOT NULL DEFAULT 0,
  card_total numeric(12,2) NOT NULL DEFAULT 0,
  company_total numeric(12,2) NOT NULL DEFAULT 0,
  rides_count integer NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_shift_sessions_driver ON shift_sessions(driver_id,started_at DESC);

CREATE TABLE IF NOT EXISTS order_events (
  id bigserial PRIMARY KEY,
  order_id text NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  actor_driver_id uuid REFERENCES drivers(id) ON DELETE SET NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_order_events_order ON order_events(order_id,created_at ASC);

CREATE TABLE IF NOT EXISTS system_settings (
  key text PRIMARY KEY,
  value jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT now()
);

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='orders_client_id_fkey') THEN
    ALTER TABLE orders ADD CONSTRAINT orders_client_id_fkey FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE SET NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname='orders_company_id_fkey') THEN
    ALTER TABLE orders ADD CONSTRAINT orders_company_id_fkey FOREIGN KEY(company_id) REFERENCES companies(id) ON DELETE SET NULL;
  END IF;
END $$;

-- WolfTaxi 0.7 TAXIMETER + LIVE TRACKING --------------------------------------
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_token text;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_enabled boolean NOT NULL DEFAULT true;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tracking_expires_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_active boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_started_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_last_at timestamptz;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_last_lat double precision;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_last_lng double precision;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_distance_m double precision NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_waiting_seconds double precision NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_amount numeric(10,2) NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS meter_updated_at timestamptz;
CREATE UNIQUE INDEX IF NOT EXISTS idx_orders_tracking_token ON orders(tracking_token) WHERE tracking_token IS NOT NULL AND tracking_token<>'';
