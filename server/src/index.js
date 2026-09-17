require('dotenv').config();

const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const bcrypt = require('bcryptjs');
const path = require('path');
const { randomUUID } = require('crypto');
const { pool, tx } = require('./db');
const { signUser, requireAuth, requireRole, requireDriver, normalizeRoles } = require('./auth');
const { detectArea, pointInPolygon } = require('./geo');
const { getSnapshot } = require('./snapshot');
const { getOperatorSnapshot } = require('./operator');
const { offerOrder, expireOffers, releaseScheduledOrders } = require('./dispatch');
const realtime = require('./realtime');

for (const key of ['DATABASE_URL', 'JWT_SECRET']) {
  if (!process.env[key] || process.env[key].startsWith('CHANGE_ME')) {
    console.error(`[WolfTaxi] Brak poprawnej zmiennej ${key} w .env`);
    process.exit(1);
  }
}

const app = express();
app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));
app.use(express.json({ limit: '256kb' }));

const loginLimiter = rateLimit({ windowMs: 10 * 60 * 1000, limit: 25, standardHeaders: true, legacyHeaders: false });
const adminLimiter = rateLimit({ windowMs: 60 * 1000, limit: 120, standardHeaders: true, legacyHeaders: false });
const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const ok = (res, message, extra = {}) => res.json({ ok: true, message, ...extra });

app.get('/health', asyncRoute(async (_req, res) => {
  await pool.query('SELECT 1');
  res.json({ ok: true, service: 'WolfTaxi Oracle API', version: '0.5.0', time: new Date().toISOString() });
}));

// Panel WWW. Publiczny Apache może mapować /dispatch/ bezpośrednio tutaj.
const dispatchPublic = path.join(__dirname, '..', 'public', 'dispatch');
app.use('/dispatch', express.static(dispatchPublic, { index: 'index.html', maxAge: 0 }));

app.post('/api/v1/auth/login', loginLimiter, asyncRoute(async (req, res) => {
  const email = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  if (!email || !password) return res.status(400).json({ message: 'Podaj e-mail i hasło.' });

  const result = await pool.query('SELECT * FROM users WHERE lower(email)=lower($1) LIMIT 1', [email]);
  const user = result.rows[0];
  if (!user || !user.enabled || !(await bcrypt.compare(password, user.password_hash))) {
    return res.status(401).json({ message: 'Nieprawidłowy e-mail lub hasło.' });
  }
  const roles = normalizeRoles(user.roles);
  if (roles.length === 0) return res.status(403).json({ message: 'Konto nie ma przypisanej roli.' });

  const driver = roles.includes('driver')
    ? (await pool.query('SELECT id,taxi_id,number,name FROM drivers WHERE user_id=$1 LIMIT 1', [user.id])).rows[0]
    : null;
  const token = signUser(user);
  ok(res, 'Zalogowano', {
    token,
    user: {
      id: String(user.id), email: user.email, name: user.display_name || '', roles,
      driverId: driver ? String(driver.id) : '', taxiId: driver?.taxi_id || '', taxiNumber: driver?.number || 0
    }
  });
}));

app.get('/api/v1/auth/me', requireAuth, asyncRoute(async (req, res) => {
  const user = (await pool.query('SELECT id,email,display_name,roles,enabled FROM users WHERE id=$1', [req.userId])).rows[0];
  if (!user || !user.enabled) return res.status(401).json({ message: 'Konto jest nieaktywne.' });
  res.json({ id:String(user.id), email:user.email, name:user.display_name || '', roles:normalizeRoles(user.roles), enabled:!!user.enabled });
}));
app.post('/api/v1/auth/logout', requireAuth, (_req, res) => ok(res, 'Wylogowano'));

// ---------------- DRIVER ----------------
const driverGuard = [requireAuth, requireDriver];

app.get('/api/v1/driver/snapshot', ...driverGuard, asyncRoute(async (req, res) => {
  res.json(await getSnapshot(pool, req.driverId));
}));

app.post('/api/v1/driver/shift/start', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const result = await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId]);
    const d = result.rows[0];
    if (!d) throw statusError(404, 'Brak profilu kierowcy.');
    if (!d.enabled) throw statusError(403, 'Konto kierowcy jest zablokowane.');
    await client.query("UPDATE drivers SET on_shift=true, status='available', online=true, updated_at=now() WHERE id=$1", [req.driverId]);
    await audit(client, req.userId, 'driver.shift.start', 'driver', req.driverId);
  });
  ok(res, 'Zmiana rozpoczęta');
}));

app.post('/api/v1/driver/shift/end', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const active = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (active.rows[0]) throw statusError(409, 'Najpierw zakończ lub odrzuć zlecenie.');
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET on_shift=false, status='offline', current_region_id=NULL, active_order_id=NULL, online=false, updated_at=now() WHERE id=$1", [req.driverId]);
    await audit(client, req.userId, 'driver.shift.end', 'driver', req.driverId);
  });
  ok(res, 'Zmiana zakończona');
}));

app.post('/api/v1/driver/status', ...driverGuard, asyncRoute(async (req, res) => {
  const status = String(req.body?.status || '');
  const allowed = new Set(['available', 'break', 'out_of_service', 'busy', 'course', 'driving_to_pickup']);
  if (!allowed.has(status)) throw statusError(400, 'Niedozwolony status.');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const busy = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (busy.rows[0]) throw statusError(409, 'Status jest sterowany przez aktywne zlecenie.');
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query('UPDATE drivers SET status=$2, updated_at=now() WHERE id=$1', [req.driverId, status]);
    await audit(client, req.userId, 'driver.status', 'driver', req.driverId, { status });
  });
  ok(res, `Status: ${status}`);
}));

app.post('/api/v1/driver/queue/join', ...driverGuard, asyncRoute(async (req, res) => {
  const regionId = String(req.body?.regionId || '').trim();
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
    await client.query('INSERT INTO queue_entries(region_id,driver_id,joined_at,priority_score) VALUES($1,$2,now(),0)', [regionId, req.driverId]);
    await client.query("UPDATE drivers SET current_region_id=$2, status='in_queue', updated_at=now() WHERE id=$1", [req.driverId, regionId]);
    await audit(client, req.userId, 'driver.queue.join', 'region', regionId, { driverId:req.driverId });
  });
  ok(res, `Dołączono do kolejki ${regionId}`);
}));

app.post('/api/v1/driver/queue/leave', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN 'available' ELSE 'offline' END, current_region_id=NULL, updated_at=now() WHERE id=$1", [req.driverId]);
    await audit(client, req.userId, 'driver.queue.leave', 'driver', req.driverId);
  });
  ok(res, 'Opuszczono kolejkę');
}));

app.post('/api/v1/driver/tariff', ...driverGuard, asyncRoute(async (req, res) => {
  const tariffId = String(req.body?.tariffId || '').trim();
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d) throw statusError(404, 'Brak profilu kierowcy.');
    if (!d.manual_tariff_allowed) throw statusError(403, 'Centrala zablokowała ręczną zmianę taryfy.');
    const tariff = (await client.query('SELECT * FROM tariffs WHERE id=$1 AND active=true', [tariffId])).rows[0];
    if (!tariff) throw statusError(400, 'Taryfa jest niedostępna.');
    await client.query('UPDATE drivers SET current_tariff_id=$2, updated_at=now() WHERE id=$1', [req.driverId, tariffId]);
    await audit(client, req.userId, 'driver.tariff', 'tariff', tariffId, { driverId:req.driverId });
  });
  ok(res, `Ustawiono ${tariffId}`);
}));

app.post('/api/v1/driver/location', ...driverGuard, asyncRoute(async (req, res) => {
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
  realtime.broadcastOperators('driver.location', { driverId:req.driverId, lat, lng, speed, heading, accuracy, at:Date.now() });
  res.json({ ok: true, offerId: offer?.id || '', pickupAddress: offer?.pickup_address || '' });
}));

app.post('/api/v1/driver/presence/offline', ...driverGuard, asyncRoute(async (req, res) => {
  await pool.query('UPDATE drivers SET online=false, updated_at=now() WHERE id=$1', [req.driverId]);
  ok(res, 'Offline');
}));

app.post('/api/v1/orders/:id/accept', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || order.status !== 'offered' || String(order.offered_driver_id) !== req.driverId || !order.offer_expires_at || new Date(order.offer_expires_at) <= new Date()) {
      throw statusError(409, 'Oferta nie jest już aktywna.');
    }
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),updated_at=now() WHERE id=$1", [order.id, req.driverId]);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',updated_at=now() WHERE id=$1", [req.driverId, order.id]);
    await audit(client, req.userId, 'order.accept', 'order', order.id, { driverId:req.driverId });
  });
  ok(res, 'Zlecenie przyjęte');
}));

app.post('/api/v1/orders/:id/reject', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || order.status !== 'offered' || String(order.offered_driver_id) !== req.driverId) throw statusError(409, 'Oferta nie jest już aktywna.');
    if (order.forced) throw statusError(403, 'Zlecenie z nakazu nie może zostać odrzucone przez kierowcę.');
    const queued = (await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1', [req.driverId])).rows[0];
    await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, queued ? 'in_queue' : 'available']);
    await client.query("UPDATE orders SET status='searching_driver',offered_driver_id=NULL,offer_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id]);
    await offerOrder(client, order.id, req.driverId);
    await audit(client, req.userId, 'order.reject', 'order', order.id, { driverId:req.driverId });
  });
  ok(res, 'Oferta odrzucona');
}));

app.post('/api/v1/orders/:id/expire', ...driverGuard, asyncRoute(async (req, res) => {
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

app.post('/api/v1/orders/:id/advance', ...driverGuard, asyncRoute(async (req, res) => {
  const next = String(req.body?.nextStatus || '');
  const transitions = { accepted:'en_route', en_route:'arrived', arrived:'in_progress', in_progress:'completed' };
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 AND assigned_driver_id=$2 FOR UPDATE', [req.params.id, req.driverId])).rows[0];
    if (!order) throw statusError(404, 'Brak aktywnego zlecenia.');
    if (transitions[order.status] !== next) throw statusError(409, 'Niedozwolona zmiana statusu.');
    const driverStatus = { en_route:'driving_to_pickup', arrived:'at_pickup', in_progress:'in_ride', completed:'available' }[next];
    if (next === 'completed') {
      const finalPrice = Number(req.body?.finalPrice ?? order.final_price ?? order.estimated_price ?? 0);
      const paymentMethod = String(req.body?.paymentMethod || order.payment_method || 'cash');
      await client.query("UPDATE orders SET status='completed',final_price=$2,payment_method=$3,completed_at=now(),updated_at=now() WHERE id=$1", [order.id, finalPrice, paymentMethod]);
      await client.query("UPDATE drivers SET active_order_id=NULL,status='available',updated_at=now() WHERE id=$1", [req.driverId]);
    } else {
      await client.query(`UPDATE orders SET status=$2, arrived_at=CASE WHEN $2='arrived' THEN now() ELSE arrived_at END, started_at=CASE WHEN $2='in_progress' THEN now() ELSE started_at END, updated_at=now() WHERE id=$1`, [order.id, next]);
      await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, driverStatus]);
    }
    await audit(client, req.userId, 'order.advance', 'order', order.id, { next });
  });
  ok(res, next === 'completed' ? 'Kurs zakończony' : 'Zaktualizowano zlecenie');
}));

app.post('/api/v1/dev/simulate-offer', ...driverGuard, asyncRoute(async (req, res) => {
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

// ---------------- DISPATCHER ----------------
const dispatchGuard = [requireAuth, requireRole('dispatcher', 'admin')];

app.get('/api/v1/dispatch/snapshot', ...dispatchGuard, asyncRoute(async (_req, res) => {
  res.json(await getOperatorSnapshot(pool, false));
}));

app.post('/api/v1/dispatch/orders', ...dispatchGuard, asyncRoute(async (req, res) => {
  const pickupAddress = String(req.body?.pickupAddress || '').trim();
  const destinationAddress = String(req.body?.destinationAddress || '').trim();
  const pickupRegionId = nullable(req.body?.pickupRegionId);
  const tariffId = nullable(req.body?.tariffId);
  const dispatchMode = ['queue','exchange'].includes(String(req.body?.dispatchMode || 'queue')) ? String(req.body?.dispatchMode || 'queue') : 'queue';
  if (!pickupAddress) throw statusError(400, 'Podaj adres podstawienia.');
  const id = `WT-${Date.now().toString(36).toUpperCase()}-${Math.floor(Math.random()*1296).toString(36).toUpperCase().padStart(2,'0')}`;
  let offered = false;
  await tx(async client => {
    if (pickupRegionId) {
      const region = (await client.query('SELECT id FROM regions WHERE id=$1 AND active=true', [pickupRegionId])).rows[0];
      if (!region) throw statusError(400, 'Wybrany region jest nieaktywny.');
    }
    const scheduledFor = req.body?.scheduledFor ? new Date(req.body.scheduledFor) : null;
    if (scheduledFor && Number.isNaN(scheduledFor.getTime())) throw statusError(400, 'Nieprawidłowa data zlecenia.');
    const initialStatus = dispatchMode === 'exchange' ? 'exchange' : (scheduledFor && scheduledFor.getTime() > Date.now()+15*60*1000 ? 'created' : 'searching_driver');
    await client.query(`
      INSERT INTO orders(id,pickup_address,destination_address,pickup_region_id,tariff_id,passenger_name,passenger_phone,notes,
        passenger_count,card_required,estimated_price,payment_method,status,dispatch_mode,source,scheduled_for,luggage,pet,english_required,mine_warning,requirements)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21::jsonb)
    `, [
      id, pickupAddress, destinationAddress, pickupRegionId, tariffId,
      String(req.body?.passengerName || '').trim(), String(req.body?.passengerPhone || '').trim(), String(req.body?.notes || '').trim(),
      Math.max(1, Math.min(20, Number(req.body?.passengerCount || 1))), !!req.body?.cardRequired,
      finite(req.body?.estimatedPrice), String(req.body?.paymentMethod || 'cash'), initialStatus, dispatchMode,
      String(req.body?.source || 'dispatch'), scheduledFor, !!req.body?.luggage, !!req.body?.pet, !!req.body?.englishRequired,
      !!req.body?.mineWarning, JSON.stringify(req.body?.requirements && typeof req.body.requirements==='object' ? req.body.requirements : {})
    ]);
    if (initialStatus === 'searching_driver') offered = await offerOrder(client, id);
    await audit(client, req.userId, 'dispatch.order.create', 'order', id, { pickupRegionId, offered, dispatchMode, scheduledFor });
  });
  realtime.broadcastDrivers('refresh', { reason:'order.created', orderId:id });
  realtime.broadcastOperators('refresh', { reason:'order.created', orderId:id });
  const message = dispatchMode==='exchange' ? 'Zlecenie dodane do giełdy.' : (offered ? 'Zlecenie utworzone i wysłane do kierowcy.' : 'Zlecenie utworzone.');
  ok(res, message, { orderId:id, offered, dispatchMode });
}));

app.post('/api/v1/dispatch/orders/:id/assign', ...dispatchGuard, asyncRoute(async (req, res) => {
  const driverId = String(req.body?.driverId || '').trim();
  if (!driverId) throw statusError(400, 'Wybierz kierowcę.');
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order || ['completed','cancelled'].includes(order.status)) throw statusError(409, 'Tego zlecenia nie można przypisać.');
    const driver = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [driverId])).rows[0];
    if (!driver || !driver.enabled || !driver.on_shift) throw statusError(409, 'Kierowca nie jest dostępny na zmianie.');
    const busy = await client.query("SELECT id FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') AND id<>$2 LIMIT 1", [driverId, order.id]);
    if (busy.rows[0]) throw statusError(409, 'Kierowca ma już aktywne zlecenie.');
    if (order.offered_driver_id && String(order.offered_driver_id) !== driverId) await restoreDriver(client, order.offered_driver_id);
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),updated_at=now() WHERE id=$1", [order.id, driverId]);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',updated_at=now() WHERE id=$1", [driverId, order.id]);
    await audit(client, req.userId, 'dispatch.order.assign', 'order', order.id, { driverId });
  });
  ok(res, 'Zlecenie przypisane.');
}));

app.post('/api/v1/dispatch/orders/:id/cancel', ...dispatchGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const order = (await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!order) throw statusError(404, 'Nie znaleziono zlecenia.');
    if (order.status === 'completed') throw statusError(409, 'Zakończonego kursu nie można anulować.');
    if (order.offered_driver_id) await restoreDriver(client, order.offered_driver_id);
    if (order.assigned_driver_id) {
      const q = (await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1 LIMIT 1', [order.assigned_driver_id])).rows[0];
      await client.query("UPDATE drivers SET active_order_id=NULL,status=CASE WHEN on_shift THEN $2 ELSE 'offline' END,updated_at=now() WHERE id=$1", [order.assigned_driver_id, q ? 'in_queue' : 'available']);
    }
    await client.query("UPDATE orders SET status='cancelled',offered_driver_id=NULL,offer_expires_at=NULL,cancelled_reason=$2,updated_at=now() WHERE id=$1", [order.id,String(req.body?.reason || '')]);
    await audit(client, req.userId, 'dispatch.order.cancel', 'order', order.id, { reason:String(req.body?.reason || '') });
  });
  ok(res, 'Zlecenie anulowane.');
}));

app.post('/api/v1/dispatch/messages', ...dispatchGuard, asyncRoute(async (req, res) => {
  const title = String(req.body?.title || '').trim();
  const body = String(req.body?.body || '').trim();
  const type = ['info','warning','urgent','system','question'].includes(String(req.body?.type || 'info')) ? String(req.body?.type || 'info') : 'info';
  if (!body) throw statusError(400, 'Wpisz treść wiadomości.');
  const targetType = ['all','driver','region'].includes(String(req.body?.targetType || 'all')) ? String(req.body?.targetType || 'all') : 'all';
  const targetId = String(req.body?.targetId || '').trim();
  await tx(async client => {
    const result = await client.query('INSERT INTO messages(type,title,body,requires_ack,active,created_by,target_type,target_id,voice_read) VALUES($1,$2,$3,$4,true,$5,$6,$7,$8) RETURNING id', [type,title,body,!!req.body?.requiresAck,req.userId,targetType,targetId,req.body?.voiceRead !== false]);
    await audit(client, req.userId, 'dispatch.message.create', 'message', String(result.rows[0].id), { type,title,targetType,targetId });
  });
  realtime.broadcastDrivers('refresh',{reason:'message'}); realtime.broadcastOperators('refresh',{reason:'message'});
  ok(res, 'Wiadomość wysłana.');
}));


// RT3000-core: giełda, nakazy, SOS, priorytety, komunikacja celowana.
app.post('/api/v1/driver/exchange/:id/claim', ...driverGuard, asyncRoute(async (req,res) => {
  await tx(async client => {
    const d=(await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE',[req.driverId])).rows[0];
    if(!d?.on_shift) throw statusError(409,'Najpierw rozpocznij zmianę.');
    const busy=await client.query("SELECT 1 FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') LIMIT 1",[req.driverId]);
    if(busy.rows[0]) throw statusError(409,'Masz już aktywne zlecenie.');
    const order=(await client.query("SELECT * FROM orders WHERE id=$1 AND status='exchange' AND dispatch_mode='exchange' FOR UPDATE",[req.params.id])).rows[0];
    if(!order) throw statusError(409,'Zlecenie nie jest już dostępne na giełdzie.');
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1',[req.driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,accepted_at=now(),updated_at=now() WHERE id=$1",[order.id,req.driverId]);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',updated_at=now() WHERE id=$1",[req.driverId,order.id]);
    await audit(client,req.userId,'exchange.claim','order',order.id,{driverId:req.driverId});
  });
  realtime.broadcastOperators('refresh',{reason:'exchange.claimed',orderId:req.params.id});
  realtime.broadcastDrivers('refresh',{reason:'exchange.claimed',orderId:req.params.id});
  ok(res,'Zlecenie pobrane z giełdy.');
}));

app.post('/api/v1/driver/sos', ...driverGuard, asyncRoute(async (req,res) => {
  let alertId='';
  await tx(async client => {
    const d=(await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE',[req.driverId])).rows[0];
    const existing=(await client.query("SELECT id FROM safety_alerts WHERE driver_id=$1 AND status IN ('active','acknowledged') ORDER BY created_at DESC LIMIT 1",[req.driverId])).rows[0];
    if(existing){alertId=String(existing.id);return;}
    const r=await client.query("INSERT INTO safety_alerts(driver_id,alert_type,status,note,lat,lng) VALUES($1,'sos','active',$2,$3,$4) RETURNING id",[req.driverId,String(req.body?.note||''),d.last_lat,d.last_lng]);
    alertId=String(r.rows[0].id);
    await client.query("UPDATE drivers SET status='emergency',updated_at=now() WHERE id=$1",[req.driverId]);
    await audit(client,req.userId,'driver.sos','safety_alert',alertId,{driverId:req.driverId});
  });
  realtime.broadcastOperators('sos',{alertId,driverId:req.driverId});
  realtime.broadcastDrivers('refresh',{reason:'sos'});
  ok(res,'ALARM SOS wysłany do centrali.',{alertId});
}));

app.post('/api/v1/driver/sos/cancel', ...driverGuard, asyncRoute(async (req,res) => {
  await tx(async client => {
    await client.query("UPDATE safety_alerts SET status='closed',closed_at=now(),closed_by=$2 WHERE driver_id=$1 AND status IN ('active','acknowledged')",[req.driverId,req.userId]);
    const q=(await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1 LIMIT 1',[req.driverId])).rows[0];
    const active=(await client.query("SELECT 1 FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') LIMIT 1",[req.driverId])).rows[0];
    if(!active) await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN $2 ELSE 'offline' END,updated_at=now() WHERE id=$1",[req.driverId,q?'in_queue':'available']);
    await audit(client,req.userId,'driver.sos.cancel','driver',req.driverId);
  });
  realtime.broadcastOperators('refresh',{reason:'sos.cancel'}); realtime.broadcastDrivers('refresh',{reason:'sos.cancel'});
  ok(res,'Alarm SOS odwołany.');
}));

app.post('/api/v1/driver/messages/:id/ack', ...driverGuard, asyncRoute(async (req,res) => {
  await pool.query('INSERT INTO message_ack(message_id,user_id) VALUES($1,$2) ON CONFLICT(message_id,user_id) DO UPDATE SET acknowledged_at=now()',[Number(req.params.id),req.userId]);
  realtime.broadcastOperators('refresh',{reason:'message.ack',messageId:req.params.id});
  ok(res,'Potwierdzono komunikat.');
}));


app.post('/api/v1/driver/messages/:id/answer', ...driverGuard, asyncRoute(async (req,res) => {
  const messageId=Number(req.params.id);
  const answer=String(req.body?.answer||'').toLowerCase();
  if(!['yes','no'].includes(answer)) throw statusError(400,'Odpowiedź musi być TAK lub NIE.');
  const message=(await pool.query("SELECT * FROM messages WHERE id=$1 AND active=true",[messageId])).rows[0];
  if(!message) throw statusError(404,'Pytanie nie jest już aktywne.');
  if(message.type!=='question') throw statusError(409,'Ta wiadomość nie jest pytaniem TAK/NIE.');
  await tx(async client => {
    await client.query('INSERT INTO message_response(message_id,user_id,answer) VALUES($1,$2,$3) ON CONFLICT(message_id,user_id) DO UPDATE SET answer=EXCLUDED.answer,responded_at=now()',[messageId,req.userId,answer]);
    await client.query('INSERT INTO message_ack(message_id,user_id) VALUES($1,$2) ON CONFLICT(message_id,user_id) DO UPDATE SET acknowledged_at=now()',[messageId,req.userId]);
    await audit(client,req.userId,'driver.message.answer','message',String(messageId),{answer});
  });
  realtime.broadcastOperators('refresh',{reason:'message.answer',messageId:String(messageId)});
  realtime.broadcastDrivers('refresh',{reason:'message.answer',messageId:String(messageId)});
  ok(res,answer==='yes'?'Odpowiedź: TAK':'Odpowiedź: NIE');
}));

app.post('/api/v1/dispatch/orders/:id/force', ...dispatchGuard, asyncRoute(async (req,res) => {
  const driverId=String(req.body?.driverId||'').trim();
  if(!driverId) throw statusError(400,'Wybierz kierowcę.');
  await tx(async client => {
    const order=(await client.query('SELECT * FROM orders WHERE id=$1 FOR UPDATE',[req.params.id])).rows[0];
    if(!order||['completed','cancelled'].includes(order.status)) throw statusError(409,'Tego zlecenia nie można wysłać z nakazu.');
    const driver=(await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE',[driverId])).rows[0];
    if(!driver||!driver.enabled||!driver.on_shift) throw statusError(409,'Kierowca nie jest na zmianie.');
    const busy=await client.query("SELECT id FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') AND id<>$2 LIMIT 1",[driverId,order.id]);
    if(busy.rows[0]) throw statusError(409,'Kierowca ma aktywne zlecenie.');
    if(order.offered_driver_id&&String(order.offered_driver_id)!==driverId) await restoreDriver(client,order.offered_driver_id);
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1',[driverId]);
    await client.query("UPDATE orders SET status='accepted',dispatch_mode='mandatory',forced=true,assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),updated_at=now() WHERE id=$1",[order.id,driverId]);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',updated_at=now() WHERE id=$1",[driverId,order.id]);
    await audit(client,req.userId,'dispatch.order.force','order',order.id,{driverId});
  });
  realtime.broadcastOperators('refresh',{reason:'order.forced',orderId:req.params.id}); realtime.broadcastDrivers('refresh',{reason:'order.forced',orderId:req.params.id});
  ok(res,'Zlecenie wysłane z nakazu.');
}));

app.post('/api/v1/dispatch/drivers/:id/priority', ...dispatchGuard, asyncRoute(async (req,res) => {
  const score=Math.max(-100,Math.min(100,Number(req.body?.priority||0)));
  await tx(async client => {
    const d=(await client.query('SELECT id FROM drivers WHERE id=$1 FOR UPDATE',[req.params.id])).rows[0];
    if(!d) throw statusError(404,'Nie znaleziono kierowcy.');
    await client.query('UPDATE drivers SET priority_points=$2,updated_at=now() WHERE id=$1',[req.params.id,score]);
    await client.query('UPDATE queue_entries SET priority_score=$2 WHERE driver_id=$1',[req.params.id,score]);
    await audit(client,req.userId,'dispatch.driver.priority','driver',req.params.id,{score});
  });
  realtime.broadcastOperators('refresh',{reason:'driver.priority'}); realtime.broadcastDrivers('refresh',{reason:'driver.priority'});
  ok(res,`Priorytet kierowcy: ${score}.`);
}));

app.post('/api/v1/dispatch/alerts/:id/ack', ...dispatchGuard, asyncRoute(async (req,res) => {
  await tx(async client => {
    const a=(await client.query("UPDATE safety_alerts SET status='acknowledged',acknowledged_at=now(),acknowledged_by=$2 WHERE id=$1 AND status='active' RETURNING driver_id",[Number(req.params.id),req.userId])).rows[0];
    if(!a) throw statusError(409,'Alarm został już obsłużony.');
    await audit(client,req.userId,'dispatch.sos.ack','safety_alert',req.params.id);
  });
  realtime.broadcastOperators('refresh',{reason:'sos.ack'}); realtime.broadcastDrivers('refresh',{reason:'sos.ack'});
  ok(res,'Alarm SOS potwierdzony.');
}));

app.post('/api/v1/dispatch/alerts/:id/close', ...dispatchGuard, asyncRoute(async (req,res) => {
  await tx(async client => {
    const a=(await client.query("UPDATE safety_alerts SET status='closed',closed_at=now(),closed_by=$2 WHERE id=$1 AND status IN ('active','acknowledged') RETURNING driver_id",[Number(req.params.id),req.userId])).rows[0];
    if(!a) throw statusError(409,'Alarm jest już zamknięty.');
    const q=(await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1 LIMIT 1',[a.driver_id])).rows[0];
    const active=(await client.query("SELECT 1 FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') LIMIT 1",[a.driver_id])).rows[0];
    if(!active) await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN $2 ELSE 'offline' END,updated_at=now() WHERE id=$1",[a.driver_id,q?'in_queue':'available']);
    await audit(client,req.userId,'dispatch.sos.close','safety_alert',req.params.id);
  });
  realtime.broadcastOperators('refresh',{reason:'sos.close'}); realtime.broadcastDrivers('refresh',{reason:'sos.close'});
  ok(res,'Alarm SOS zamknięty.');
}));

// ---------------- ADMIN ----------------
const adminGuard = [requireAuth, requireRole('admin'), adminLimiter];

app.get('/api/v1/admin/snapshot', ...adminGuard, asyncRoute(async (_req, res) => {
  res.json(await getOperatorSnapshot(pool, true));
}));

app.post('/api/v1/admin/users', ...adminGuard, asyncRoute(async (req, res) => {
  const email = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  const displayName = String(req.body?.name || email).trim();
  const roles = normalizeRoles(req.body?.roles);
  if (!email || !email.includes('@')) throw statusError(400, 'Podaj poprawny e-mail.');
  if (password.length < 8) throw statusError(400, 'Hasło musi mieć co najmniej 8 znaków.');
  if (roles.length === 0) throw statusError(400, 'Wybierz co najmniej jedną rolę.');
  const id = randomUUID();
  const hash = await bcrypt.hash(password, 12);
  await tx(async client => {
    const exists = (await client.query('SELECT 1 FROM users WHERE lower(email)=lower($1)', [email])).rows[0];
    if (exists) throw statusError(409, 'Konto z tym e-mailem już istnieje.');
    await client.query('INSERT INTO users(id,email,password_hash,display_name,roles,enabled) VALUES($1,$2,$3,$4,$5,true)', [id,email,hash,displayName,roles]);
    if (roles.includes('driver')) {
      const taxiId = String(req.body?.taxiId || '').trim().toUpperCase();
      const number = Number(req.body?.number || 0);
      if (!taxiId || !Number.isInteger(number) || number <= 0) throw statusError(400, 'Dla kierowcy podaj taxiId i numer taxi.');
      const driverId = randomUUID();
      await client.query(`
        INSERT INTO drivers(id,user_id,taxi_id,number,name,email,password_hash,enabled,on_shift,status,manual_tariff_allowed,current_tariff_id)
        VALUES($1,$2,$3,$4,$5,$6,$7,true,false,'offline',true,(SELECT id FROM tariffs WHERE active=true ORDER BY sort_order,id LIMIT 1))
      `, [driverId,id,taxiId,number,displayName,email,hash]);
    }
    await audit(client, req.userId, 'admin.user.create', 'user', id, { email,roles });
  });
  ok(res, 'Konto utworzone.', { userId:id });
}));

app.post('/api/v1/admin/users/:id/roles', ...adminGuard, asyncRoute(async (req, res) => {
  const roles = normalizeRoles(req.body?.roles);
  if (roles.length === 0) throw statusError(400, 'Konto musi mieć co najmniej jedną rolę.');
  await tx(async client => {
    const user = (await client.query('SELECT * FROM users WHERE id=$1 FOR UPDATE', [req.params.id])).rows[0];
    if (!user) throw statusError(404, 'Nie znaleziono konta.');
    await client.query('UPDATE users SET roles=$2,updated_at=now() WHERE id=$1', [user.id, roles]);
    if (!roles.includes('driver')) {
      await client.query('DELETE FROM queue_entries WHERE driver_id IN (SELECT id FROM drivers WHERE user_id=$1)', [user.id]);
      await client.query("UPDATE drivers SET enabled=false,on_shift=false,status='offline',online=false,active_order_id=NULL,updated_at=now() WHERE user_id=$1", [user.id]);
    } else {
      await client.query('UPDATE drivers SET enabled=true,updated_at=now() WHERE user_id=$1', [user.id]);
    }
    await audit(client, req.userId, 'admin.user.roles', 'user', user.id, { roles });
  });
  ok(res, 'Role zaktualizowane. Zaloguj konto ponownie, aby odświeżyć uprawnienia.');
}));

app.post('/api/v1/admin/users/:id/password', ...adminGuard, asyncRoute(async (req, res) => {
  const password = String(req.body?.password || '');
  if (password.length < 8) throw statusError(400, 'Hasło musi mieć co najmniej 8 znaków.');
  const hash = await bcrypt.hash(password, 12);
  await tx(async client => {
    const user = (await client.query('SELECT id FROM users WHERE id=$1', [req.params.id])).rows[0];
    if (!user) throw statusError(404, 'Nie znaleziono konta.');
    await client.query('UPDATE users SET password_hash=$2,updated_at=now() WHERE id=$1', [user.id,hash]);
    await client.query('UPDATE drivers SET password_hash=$2,updated_at=now() WHERE user_id=$1', [user.id,hash]);
    await audit(client, req.userId, 'admin.user.password', 'user', user.id);
  });
  ok(res, 'Hasło zmienione.');
}));

app.post('/api/v1/admin/users/:id/enabled', ...adminGuard, asyncRoute(async (req, res) => {
  const enabled = !!req.body?.enabled;
  if (String(req.params.id) === String(req.userId) && !enabled) throw statusError(409, 'Nie możesz zablokować własnego konta.');
  await tx(async client => {
    await client.query('UPDATE users SET enabled=$2,updated_at=now() WHERE id=$1', [req.params.id,enabled]);
    if (!enabled) {
      await client.query('DELETE FROM queue_entries WHERE driver_id IN (SELECT id FROM drivers WHERE user_id=$1)', [req.params.id]);
      await client.query("UPDATE drivers SET enabled=false,on_shift=false,status='offline',online=false,active_order_id=NULL,updated_at=now() WHERE user_id=$1", [req.params.id]);
    } else {
      await client.query('UPDATE drivers SET enabled=true,updated_at=now() WHERE user_id=$1', [req.params.id]);
    }
    await audit(client, req.userId, 'admin.user.enabled', 'user', req.params.id, { enabled });
  });
  ok(res, enabled ? 'Konto odblokowane.' : 'Konto zablokowane.');
}));

app.post('/api/v1/admin/tariffs/:id', ...adminGuard, asyncRoute(async (req, res) => {
  const id = cleanCode(req.params.id, 'Taryfa');
  const name = String(req.body?.name || id).trim();
  const shortName = String(req.body?.shortName || id).trim();
  await tx(async client => {
    await client.query(`
      INSERT INTO tariffs(id,name,short_name,active,start_fee,price_per_km,waiting_price_per_hour,minimum_fare,sort_order)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)
      ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,short_name=EXCLUDED.short_name,active=EXCLUDED.active,
        start_fee=EXCLUDED.start_fee,price_per_km=EXCLUDED.price_per_km,waiting_price_per_hour=EXCLUDED.waiting_price_per_hour,
        minimum_fare=EXCLUDED.minimum_fare,sort_order=EXCLUDED.sort_order
    `, [id,name,shortName,req.body?.active !== false,finite(req.body?.startFee),finite(req.body?.pricePerKm),finite(req.body?.waitingPricePerHour),finite(req.body?.minimumFare),Number(req.body?.sortOrder || 0)]);
    await audit(client, req.userId, 'admin.tariff.save', 'tariff', id);
  });
  ok(res, `Taryfa ${id} zapisana.`);
}));

app.post('/api/v1/admin/regions/:id', ...adminGuard, asyncRoute(async (req, res) => {
  const id = cleanCode(req.params.id, 'Region');
  const polygon = Array.isArray(req.body?.polygon) ? req.body.polygon : [];
  await tx(async client => {
    await client.query(`
      INSERT INTO regions(id,name,short_name,active,queue_enabled,priority,polygon)
      VALUES($1,$2,$3,$4,$5,$6,$7::jsonb)
      ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,short_name=EXCLUDED.short_name,active=EXCLUDED.active,
        queue_enabled=EXCLUDED.queue_enabled,priority=EXCLUDED.priority,polygon=EXCLUDED.polygon
    `, [id,String(req.body?.name || id),String(req.body?.shortName || id),req.body?.active !== false,req.body?.queueEnabled !== false,Number(req.body?.priority || 0),JSON.stringify(polygon)]);
    await audit(client, req.userId, 'admin.region.save', 'region', id);
  });
  ok(res, `Region ${id} zapisany.`);
}));

app.post('/api/v1/admin/fare-zones/:id', ...adminGuard, asyncRoute(async (req, res) => {
  const id = cleanCode(req.params.id, 'Strefa');
  const polygon = Array.isArray(req.body?.polygon) ? req.body.polygon : [];
  await tx(async client => {
    await client.query(`
      INSERT INTO fare_zones(id,name,active,multiplier,default_tariff_id,priority,polygon)
      VALUES($1,$2,$3,$4,$5,$6,$7::jsonb)
      ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,active=EXCLUDED.active,multiplier=EXCLUDED.multiplier,
        default_tariff_id=EXCLUDED.default_tariff_id,priority=EXCLUDED.priority,polygon=EXCLUDED.polygon
    `, [id,String(req.body?.name || id),req.body?.active !== false,Number(req.body?.multiplier || 1),nullable(req.body?.defaultTariffId),Number(req.body?.priority || 0),JSON.stringify(polygon)]);
    await audit(client, req.userId, 'admin.fare_zone.save', 'fare_zone', id);
  });
  ok(res, `Strefa ${id} zapisana.`);
}));

app.use((error, _req, res, _next) => {
  const code = Number(error.statusCode || 500);
  if (code >= 500) console.error(error);
  res.status(code).json({ message: error.message || 'Błąd serwera.' });
});

function statusError(statusCode, message) { return Object.assign(new Error(message), { statusCode }); }
function finite(value) { const n = Number(value); return Number.isFinite(n) ? n : 0; }
function nullable(value) { const text = String(value == null ? '' : value).trim(); return text ? text : null; }
function cleanCode(value, label) {
  const out = String(value || '').trim().toUpperCase();
  if (!/^[A-Z0-9_-]{1,24}$/.test(out)) throw statusError(400, `${label}: nieprawidłowe ID.`);
  return out;
}
async function audit(client, userId, action, entityType, entityId, details = {}) {
  await client.query('INSERT INTO audit_log(user_id,action,entity_type,entity_id,details) VALUES($1,$2,$3,$4,$5::jsonb)', [userId,action,entityType || '',String(entityId || ''),JSON.stringify(details || {})]);
}
async function restoreDriver(client, driverId) {
  const q = (await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1 LIMIT 1', [driverId])).rows[0];
  await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN $2 ELSE 'offline' END,updated_at=now() WHERE id=$1", [driverId,q ? 'in_queue' : 'available']);
}

const port = Math.max(1, Number(process.env.PORT || 8081));
const host = process.env.HOST || '127.0.0.1';
const server = app.listen(port, host, () => console.log(`[WolfTaxi] API 0.5.0 RT3000-core działa na http://${host}:${port}`));
realtime.attachRealtime(server);

const timer = setInterval(async () => {
  try { await expireOffers(); await releaseScheduledOrders(); realtime.broadcastDrivers('refresh',{reason:'tick'}); realtime.broadcastOperators('refresh',{reason:'tick'}); } catch (error) { console.error('[WolfTaxi] dispatch timer:', error); }
}, 3000);
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
