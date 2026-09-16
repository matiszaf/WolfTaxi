require('dotenv').config();

const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const bcrypt = require('bcryptjs');
const { randomUUID } = require('crypto');
const { pool, tx } = require('./db');
const { signDriver, requireAuth } = require('./auth');
const { detectArea, pointInPolygon } = require('./geo');
const { getSnapshot } = require('./snapshot');
const { offerOrder, expireOffers } = require('./dispatch');

for (const key of ['DATABASE_URL', 'JWT_SECRET']) {
  if (!process.env[key] || process.env[key].startsWith('CHANGE_ME')) {
    console.error(`[WolfTaxi] Brak poprawnej zmiennej ${key} w .env`);
    process.exit(1);
  }
}

const app = express();
app.set('trust proxy', 1);
app.use(helmet());
app.use(express.json({ limit: '64kb' }));

const loginLimiter = rateLimit({ windowMs: 10 * 60 * 1000, limit: 25, standardHeaders: true, legacyHeaders: false });

const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const ok = (res, message, extra = {}) => res.json({ ok: true, message, ...extra });

app.get('/health', asyncRoute(async (_req, res) => {
  await pool.query('SELECT 1');
  res.json({ ok: true, service: 'WolfTaxi Oracle API', version: '0.3.0', time: new Date().toISOString() });
}));

app.post('/api/v1/auth/login', loginLimiter, asyncRoute(async (req, res) => {
  const email = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  if (!email || !password) return res.status(400).json({ message: 'Podaj e-mail i hasło.' });
  const result = await pool.query('SELECT * FROM drivers WHERE lower(email)=lower($1) LIMIT 1', [email]);
  const driver = result.rows[0];
  if (!driver || !driver.enabled || !(await bcrypt.compare(password, driver.password_hash))) {
    return res.status(401).json({ message: 'Nieprawidłowy e-mail lub hasło.' });
  }
  const token = signDriver(driver);
  ok(res, 'Zalogowano', { token, userId: driver.id, taxiId: driver.taxi_id });
}));

app.post('/api/v1/auth/logout', requireAuth, (_req, res) => ok(res, 'Wylogowano'));

app.get('/api/v1/driver/snapshot', requireAuth, asyncRoute(async (req, res) => {
  res.json(await getSnapshot(pool, req.driverId));
}));

app.post('/api/v1/driver/shift/start', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    const result = await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId]);
    const d = result.rows[0];
    if (!d) throw statusError(404, 'Brak profilu kierowcy.');
    if (!d.enabled) throw statusError(403, 'Konto kierowcy jest zablokowane.');
    await client.query("UPDATE drivers SET on_shift=true, status='available', online=true, updated_at=now() WHERE id=$1", [req.driverId]);
  });
  ok(res, 'Zmiana rozpoczęta');
}));

app.post('/api/v1/driver/shift/end', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    const active = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (active.rows[0]) throw statusError(409, 'Najpierw zakończ lub odrzuć zlecenie.');
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET on_shift=false, status='offline', current_region_id=NULL, active_order_id=NULL, online=false, updated_at=now() WHERE id=$1", [req.driverId]);
  });
  ok(res, 'Zmiana zakończona');
}));

app.post('/api/v1/driver/status', requireAuth, asyncRoute(async (req, res) => {
  const status = String(req.body?.status || '');
  const allowed = new Set(['available', 'break', 'out_of_service']);
  if (!allowed.has(status)) throw statusError(400, 'Niedozwolony status.');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const busy = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (busy.rows[0]) throw statusError(409, 'Status jest sterowany przez aktywne zlecenie.');
    if (status !== 'in_queue') await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query('UPDATE drivers SET status=$2, updated_at=now() WHERE id=$1', [req.driverId, status]);
  });
  ok(res, `Status: ${status}`);
}));

app.post('/api/v1/driver/queue/join', requireAuth, asyncRoute(async (req, res) => {
  const regionId = String(req.body?.regionId || '');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const busy = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (busy.rows[0]) throw statusError(409, 'Nie możesz wejść do kolejki podczas zlecenia.');
    const region = (await client.query('SELECT * FROM regions WHERE id=$1 AND active=true', [regionId])).rows[0];
    if (!region || !region.queue_enabled) throw statusError(400, 'Ten region nie przyjmuje kierowców.');

    if (Array.isArray(region.polygon) && region.polygon.length >= 3) {
      if (!d.last_lat || !d.last_lng || !d.last_location_at || (Date.now() - new Date(d.last_location_at).getTime()) > 60000) {
        throw statusError(409, 'Brak świeżej lokalizacji GPS.');
      }
      if (!pointInPolygon(Number(d.last_lat), Number(d.last_lng), region.polygon)) {
        throw statusError(409, 'Nie znajdujesz się w wybranym regionie.');
      }
    }

    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query('INSERT INTO queue_entries(region_id,driver_id,joined_at) VALUES($1,$2,now())', [regionId, req.driverId]);
    await client.query("UPDATE drivers SET current_region_id=$2, status='in_queue', updated_at=now() WHERE id=$1", [req.driverId, regionId]);
  });
  ok(res, `Dołączono do kolejki ${regionId}`);
}));

app.post('/api/v1/driver/queue/leave', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN 'available' ELSE 'offline' END, updated_at=now() WHERE id=$1", [req.driverId]);
  });
  ok(res, 'Opuszczono kolejkę');
}));

app.post('/api/v1/driver/tariff', requireAuth, asyncRoute(async (req, res) => {
  const tariffId = String(req.body?.tariffId || '');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d) throw statusError(404, 'Brak profilu kierowcy.');
    if (!d.manual_tariff_allowed) throw statusError(403, 'Centrala zablokowała ręczną zmianę taryfy.');
    const tariff = (await client.query('SELECT * FROM tariffs WHERE id=$1 AND active=true', [tariffId])).rows[0];
    if (!tariff) throw statusError(400, 'Taryfa jest niedostępna.');
    await client.query('UPDATE drivers SET current_tariff_id=$2, updated_at=now() WHERE id=$1', [req.driverId, tariffId]);
  });
  ok(res, `Ustawiono ${tariffId}`);
}));

app.post('/api/v1/driver/location', requireAuth, asyncRoute(async (req, res) => {
  const lat = Number(req.body?.lat), lng = Number(req.body?.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) throw statusError(400, 'Nieprawidłowa lokalizacja.');
  const speed = finite(req.body?.speed), heading = finite(req.body?.heading), accuracy = finite(req.body?.accuracy);
  let offer = null;
  await tx(async client => {
    const detectedRegion = await detectArea(client, 'regions', lat, lng);
    const fareZone = await detectArea(client, 'fare_zones', lat, lng);
    await client.query(`
      UPDATE drivers SET last_lat=$2,last_lng=$3,last_speed=$4,last_heading=$5,last_accuracy=$6,
        last_location_at=now(), online=true, detected_region_id=$7, current_fare_zone_id=$8, updated_at=now()
      WHERE id=$1
    `, [req.driverId, lat, lng, speed, heading, accuracy, detectedRegion, fareZone]);
    offer = (await client.query("SELECT id,pickup_address FROM orders WHERE offered_driver_id=$1 AND status='offered' AND offer_expires_at>now() ORDER BY offer_expires_at DESC LIMIT 1", [req.driverId])).rows[0] || null;
  });
  res.json({ ok: true, offerId: offer?.id || '', pickupAddress: offer?.pickup_address || '' });
}));

app.post('/api/v1/driver/presence/offline', requireAuth, asyncRoute(async (req, res) => {
  await pool.query('UPDATE drivers SET online=false, updated_at=now() WHERE id=$1', [req.driverId]);
  ok(res, 'Offline');
}));

app.post('/api/v1/orders/:id/accept', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || order.status !== 'offered' || String(order.offered_driver_id) !== req.driverId || !order.offer_expires_at || new Date(order.offer_expires_at) <= new Date()) {
      throw statusError(409, 'Oferta nie jest już aktywna.');
    }
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id, req.driverId]);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',updated_at=now() WHERE id=$1", [req.driverId, order.id]);
  });
  ok(res, 'Zlecenie przyjęte');
}));

app.post('/api/v1/orders/:id/reject', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || order.status !== 'offered' || String(order.offered_driver_id) !== req.driverId) throw statusError(409, 'Oferta nie jest już aktywna.');
    const queued = (await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1', [req.driverId])).rows[0];
    await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, queued ? 'in_queue' : 'available']);
    await client.query("UPDATE orders SET status='searching_driver',offered_driver_id=NULL,offer_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id]);
    await offerOrder(client, order.id, req.driverId);
  });
  ok(res, 'Oferta odrzucona');
}));

app.post('/api/v1/orders/:id/expire', requireAuth, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || order.status !== 'offered' || String(order.offered_driver_id) !== req.driverId) return;
    if (order.offer_expires_at && new Date(order.offer_expires_at) > new Date()) return;
    const queued = (await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1', [req.driverId])).rows[0];
    await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, queued ? 'in_queue' : 'available']);
    await client.query("UPDATE orders SET status='searching_driver',offered_driver_id=NULL,offer_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id]);
    await offerOrder(client, order.id, req.driverId);
  });
  ok(res, 'Oferta wygasła');
}));

app.post('/api/v1/orders/:id/advance', requireAuth, asyncRoute(async (req, res) => {
  const next = String(req.body?.nextStatus || '');
  const transitions = { accepted:'en_route', en_route:'arrived', arrived:'in_progress', in_progress:'completed' };
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 AND assigned_driver_id=$2 FOR UPDATE', [req.params.id, req.driverId])).rows[0];
    if (!order) throw statusError(404, 'Brak aktywnego zlecenia.');
    if (transitions[order.status] !== next) throw statusError(409, 'Niedozwolona zmiana statusu.');
    const driverStatus = { en_route:'driving_to_pickup', arrived:'at_pickup', in_progress:'in_ride', completed:'available' }[next];
    if (next === 'completed') {
      const finalPrice = Number(order.final_price || order.estimated_price || 0);
      await client.query("UPDATE orders SET status='completed',final_price=$2,completed_at=now(),updated_at=now() WHERE id=$1", [order.id, finalPrice]);
      await client.query("UPDATE drivers SET active_order_id=NULL,status='available',updated_at=now() WHERE id=$1", [req.driverId]);
    } else {
      await client.query('UPDATE orders SET status=$2,updated_at=now() WHERE id=$1', [order.id, next]);
      await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, driverStatus]);
    }
  });
  ok(res, next === 'completed' ? 'Kurs zakończony' : 'Zaktualizowano zlecenie');
}));

app.post('/api/v1/dev/simulate-offer', requireAuth, asyncRoute(async (req, res) => {
  if (String(process.env.DEV_SIMULATION || '').toLowerCase() !== 'true') throw statusError(404, 'Tryb testowy jest wyłączony.');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const busy = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (busy.rows[0]) throw statusError(409, 'Masz już aktywne zlecenie.');
    const id = `WT-${Date.now()}`;
    const regionId = d.current_region_id || (await client.query('SELECT id FROM regions WHERE active=true ORDER BY priority,id LIMIT 1')).rows[0]?.id || null;
    const tariffId = d.current_tariff_id || (await client.query('SELECT id FROM tariffs WHERE active=true ORDER BY sort_order,id LIMIT 1')).rows[0]?.id || null;
    await client.query(`
      INSERT INTO orders(id,pickup_address,destination_address,pickup_region_id,pickup_fare_zone_id,destination_fare_zone_id,tariff_id,
        passenger_name,passenger_phone,notes,passenger_count,card_required,estimated_price,payment_method,status,offered_driver_id,offer_expires_at)
      VALUES($1,'Dworcowa 12','Portowa 7',$2,$3,$3,$4,'Jan','*** *** 321','2 osoby · płatność kartą',2,true,67,'card','offered',$5,
        now() + ($6 || ' seconds')::interval)
    `, [id, regionId, d.current_fare_zone_id, tariffId, req.driverId, String(Math.max(5, Number(process.env.OFFER_TIMEOUT_SECONDS || 20)))]);
    await client.query("UPDATE drivers SET status='offer_received',updated_at=now() WHERE id=$1", [req.driverId]);
  });
  ok(res, 'Nowa oferta testowa');
}));

app.use((error, _req, res, _next) => {
  const code = Number(error.statusCode || 500);
  if (code >= 500) console.error(error);
  res.status(code).json({ message: error.message || 'Błąd serwera.' });
});

function statusError(statusCode, message) { return Object.assign(new Error(message), { statusCode }); }
function finite(value) { const n = Number(value); return Number.isFinite(n) ? n : 0; }

const port = Math.max(1, Number(process.env.PORT || 8081));
const host = process.env.HOST || '127.0.0.1';
const server = app.listen(port, host, () => console.log(`[WolfTaxi] API działa na http://${host}:${port}`));

const timer = setInterval(() => expireOffers().catch(error => console.error('[WolfTaxi] expireOffers:', error)), 3000);
timer.unref();

async function shutdown() {
  clearInterval(timer);
  server.close(async () => {
    await pool.end();
    process.exit(0);
  });
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
