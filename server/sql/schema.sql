CREATE TABLE IF NOT EXISTS drivers (
  id uuid PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS messages (
  id bigserial PRIMARY KEY,
  type text NOT NULL DEFAULT 'info',
  title text NOT NULL DEFAULT '',
  body text NOT NULL DEFAULT '',
  requires_ack boolean NOT NULL DEFAULT false,
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now()
);
