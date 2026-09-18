require('dotenv').config();

const express = require('express');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const bcrypt = require('bcryptjs');
const path = require('path');
const { randomUUID, randomBytes } = require('crypto');
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
const trackingLimiter = rateLimit({ windowMs: 60 * 1000, limit: 120, standardHeaders: true, legacyHeaders: false });
const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const ok = (res, message, extra = {}) => res.json({ ok: true, message, ...extra });

app.get('/health', asyncRoute(async (_req, res) => {
  await pool.query('SELECT 1');
  res.json({ ok: true, service: 'WolfTaxi Oracle API', version: '0.8.3', time: new Date().toISOString() });
}));

// Panel WWW. Publiczny Apache może mapować /dispatch/ bezpośrednio tutaj.
const dispatchPublic = path.join(__dirname, '..', 'public', 'dispatch');
const trackingPublic = path.join(__dirname, '..', 'public', 'track');
app.use('/dispatch', express.static(dispatchPublic, { index: 'index.html', maxAge: 0 }));
app.use('/track-static', express.static(trackingPublic, { maxAge: '1d' }));
app.get('/track/:token', (_req, res) => {
  res.set('Cache-Control','no-store');
  res.sendFile(path.join(trackingPublic, 'index.html'));
});

app.get('/api/v1/public/track/:token', trackingLimiter, asyncRoute(async (req, res) => {
  const token = String(req.params.token || '').trim();
  if (!/^[A-Za-z0-9_-]{24,128}$/.test(token)) throw statusError(404, 'Link śledzenia jest nieprawidłowy.');
  const row = (await pool.query(`
    SELECT o.id,o.status,o.pickup_address,o.destination_address,o.tracking_enabled,o.tracking_expires_at,
           o.meter_amount,o.meter_distance_m,o.meter_waiting_seconds,o.meter_last_lat,o.meter_last_lng,o.meter_updated_at,
           d.taxi_id,d.number,d.last_lat,d.last_lng,d.last_heading,d.last_speed,d.last_location_at
    FROM orders o LEFT JOIN drivers d ON d.id=o.assigned_driver_id
    WHERE o.tracking_token=$1 LIMIT 1
  `,[token])).rows[0];
  if (!row || !row.tracking_enabled || (row.tracking_expires_at && new Date(row.tracking_expires_at) <= new Date())) throw statusError(404, 'Link śledzenia wygasł lub jest niedostępny.');
  const finished = ['completed','cancelled'].includes(String(row.status));
  res.set('Cache-Control','no-store');
  res.json({
    orderId:String(row.id), status:String(row.status), pickupAddress:String(row.pickup_address||''), destinationAddress:String(row.destination_address||''),
    taxiId:String(row.taxi_id||''), taxiNumber:Number(row.number||0),
    lat:finished?(row.meter_last_lat==null?null:Number(row.meter_last_lat)):(row.last_lat==null?null:Number(row.last_lat)),
    lng:finished?(row.meter_last_lng==null?null:Number(row.meter_last_lng)):(row.last_lng==null?null:Number(row.last_lng)),
    heading:Number(row.last_heading||0), speed:Number(row.last_speed||0),
    lastLocationAt: finished ? (row.meter_updated_at?new Date(row.meter_updated_at).getTime():0) : (row.last_location_at?new Date(row.last_location_at).getTime():0),
    meterAmount:Number(row.meter_amount||0), meterDistanceM:Number(row.meter_distance_m||0), meterWaitingSeconds:Number(row.meter_waiting_seconds||0)
  });
}));

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


// ---------------- SMS GATEWAY (private APK role) ----------------
const smsGatewayGuard = [requireAuth, requireRole('sms_gateway')];

app.get('/api/v1/sms-gateway/status', ...smsGatewayGuard, asyncRoute(async (req,res) => {
  const counts=(await pool.query(`SELECT
    count(*) FILTER (WHERE status='queued')::int queued,
    count(*) FILTER (WHERE status='sending')::int sending,
    count(*) FILTER (WHERE status='sent' AND sent_at>now()-interval '24 hours')::int sent24h,
    count(*) FILTER (WHERE status='failed' AND updated_at>now()-interval '24 hours')::int failed24h
    FROM sms_outbox`)).rows[0] || {};
  const recent=(await pool.query(`SELECT id,order_id,kind,recipient,status,attempts,last_error,created_at,sent_at
    FROM sms_outbox ORDER BY created_at DESC LIMIT 20`)).rows;
  res.json({
    ok:true,
    queued:Number(counts.queued||0), sending:Number(counts.sending||0), sent24h:Number(counts.sent24h||0), failed24h:Number(counts.failed24h||0),
    recent:recent.map(x=>({id:String(x.id),orderId:String(x.order_id||''),kind:String(x.kind||''),recipient:String(x.recipient||''),status:String(x.status||''),attempts:Number(x.attempts||0),lastError:String(x.last_error||''),createdAt:x.created_at?new Date(x.created_at).getTime():0,sentAt:x.sent_at?new Date(x.sent_at).getTime():0}))
  });
}));

app.post('/api/v1/sms-gateway/next', ...smsGatewayGuard, asyncRoute(async (req,res) => {
  let item=null;
  await tx(async client => {
    const row=(await client.query(`
      SELECT * FROM sms_outbox
      WHERE (
        status='queued' OR
        (status='sending' AND (lease_until IS NULL OR lease_until<now())) OR
        (status='failed' AND attempts<3 AND updated_at<now()-interval '30 seconds')
      )
      ORDER BY created_at ASC
      FOR UPDATE SKIP LOCKED
      LIMIT 1
    `)).rows[0];
    if(!row) return;
    const claimed=(await client.query(`UPDATE sms_outbox
      SET status='sending',attempts=attempts+1,gateway_user_id=$2,lease_until=now()+interval '90 seconds',updated_at=now()
      WHERE id=$1 RETURNING *`,[row.id,req.userId])).rows[0];
    item={id:String(claimed.id),orderId:String(claimed.order_id||''),kind:String(claimed.kind||''),recipient:String(claimed.recipient||''),body:String(claimed.body||''),attempts:Number(claimed.attempts||0)};
  });
  res.json({ok:true,item});
}));

app.post('/api/v1/sms-gateway/:id/report', ...smsGatewayGuard, asyncRoute(async (req,res) => {
  const success=!!req.body?.success;
  const error=String(req.body?.error||'').slice(0,500);
  await tx(async client => {
    const row=(await client.query('SELECT * FROM sms_outbox WHERE id=$1 FOR UPDATE',[req.params.id])).rows[0];
    if(!row) throw statusError(404,'Nie znaleziono wiadomości SMS.');
    if(success){
      await client.query(`UPDATE sms_outbox SET status='sent',sent_at=COALESCE(sent_at,now()),lease_until=NULL,last_error='',updated_at=now() WHERE id=$1`,[row.id]);
      if(row.kind==='tracking' && row.order_id) await client.query(`UPDATE orders SET tracking_sms_status='sent',tracking_sms_sent_at=COALESCE(tracking_sms_sent_at,now()),tracking_sms_last_error='',updated_at=now() WHERE id=$1`,[row.order_id]);
    } else {
      const permanent=Number(row.attempts||0)>=3;
      await client.query(`UPDATE sms_outbox SET status=$2,lease_until=NULL,last_error=$3,updated_at=now() WHERE id=$1`,[row.id,permanent?'failed':'queued',error||'Błąd wysyłki SMS']);
      if(row.kind==='tracking' && row.order_id) await client.query(`UPDATE orders SET tracking_sms_status=$2,tracking_sms_last_error=$3,updated_at=now() WHERE id=$1`,[row.order_id,permanent?'failed':'queued',error||'Błąd wysyłki SMS']);
    }
    await audit(client,req.userId,success?'sms.sent':'sms.failed','sms_outbox',String(row.id),{orderId:row.order_id,kind:row.kind,error});
  });
  realtime.broadcastOperators('refresh',{reason:'sms.report'});
  ok(res,success?'SMS oznaczony jako wysłany.':'Błąd SMS zapisany.');
}));

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
    await client.query("UPDATE drivers SET on_shift=true, status='available', target_region_id=NULL, online=true, updated_at=now() WHERE id=$1", [req.driverId]);
    await client.query(`INSERT INTO shift_sessions(driver_id,started_at) SELECT $1,now() WHERE NOT EXISTS (SELECT 1 FROM shift_sessions WHERE driver_id=$1 AND ended_at IS NULL)`, [req.driverId]);
    await driverEvent(client, req.driverId, 'shift.start', {});
    await audit(client, req.userId, 'driver.shift.start', 'driver', req.driverId);
  });
  realtime.broadcastOperators('refresh',{reason:'shift.start',driverId:req.driverId}); realtime.broadcastDrivers('refresh',{reason:'shift.start'});
  ok(res, 'Zmiana rozpoczęta');
}));

app.post('/api/v1/driver/shift/end', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    const active = await client.query("SELECT 1 FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') LIMIT 1", [req.driverId]);
    if (active.rows[0]) throw statusError(409, 'Najpierw zakończ lub odrzuć zlecenie.');
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET on_shift=false, status='offline', current_region_id=NULL, target_region_id=NULL, active_order_id=NULL, online=false, updated_at=now() WHERE id=$1", [req.driverId]);
    await client.query(`UPDATE shift_sessions SET ended_at=now() WHERE id=(SELECT id FROM shift_sessions WHERE driver_id=$1 AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1)`, [req.driverId]);
    await driverEvent(client, req.driverId, 'shift.end', {});
    await audit(client, req.userId, 'driver.shift.end', 'driver', req.driverId);
  });
  realtime.broadcastOperators('refresh',{reason:'shift.end',driverId:req.driverId}); realtime.broadcastDrivers('refresh',{reason:'shift.end'});
  ok(res, 'Zmiana zakończona');
}));

app.post('/api/v1/driver/status', ...driverGuard, asyncRoute(async (req, res) => {
  const status = String(req.body?.status || '');
  const requestedRegionId = String(req.body?.regionId || '').trim();
  const allowed = new Set(['available', 'break', 'out_of_service', 'busy', 'course', 'driving_to_pickup']);
  if (!allowed.has(status)) throw statusError(400, 'Niedozwolony status.');
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const busy = await client.query("SELECT id,status FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') ORDER BY updated_at DESC LIMIT 1", [req.driverId]);

    // Aktywne zlecenie nadal steruje statusem kierowcy, ale nie blokuje już
    // wskazania regionu docelowego z terminala. To pozwala używać kodów
    // rejonów w trakcie kursu/dojazdu bez ingerowania w etap zlecenia.
    if (busy.rows[0]) {
      if (!requestedRegionId || !(status === 'course' || status === 'driving_to_pickup')) {
        throw statusError(409, 'Status jest sterowany przez aktywne zlecenie.');
      }
      const region = (await client.query('SELECT id FROM regions WHERE id=$1 AND active=true', [requestedRegionId])).rows[0];
      if (!region) throw statusError(400, 'Nieznany lub nieaktywny rejon.');
      await client.query('UPDATE drivers SET target_region_id=$2, updated_at=now() WHERE id=$1', [req.driverId, requestedRegionId]);
      await driverEvent(client, req.driverId, 'target.region', { targetRegionId: requestedRegionId, activeOrderId: busy.rows[0].id, requestedStatus: status });
      await audit(client, req.userId, 'driver.target.region', 'driver', req.driverId, { targetRegionId: requestedRegionId, activeOrderId: busy.rows[0].id, requestedStatus: status });
      return;
    }

    let targetRegionId = null;
    if (requestedRegionId) {
      const region = (await client.query('SELECT id FROM regions WHERE id=$1 AND active=true', [requestedRegionId])).rows[0];
      if (!region) throw statusError(400, 'Nieznany lub nieaktywny rejon.');
      if (status === 'course' || status === 'driving_to_pickup') targetRegionId = requestedRegionId;
    }

    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query('UPDATE drivers SET status=$2, target_region_id=$3, updated_at=now() WHERE id=$1', [req.driverId, status, targetRegionId]);
    await driverEvent(client, req.driverId, 'status', { status, targetRegionId });
    await audit(client, req.userId, 'driver.status', 'driver', req.driverId, { status, targetRegionId });
  });
  realtime.broadcastOperators('refresh',{reason:'driver.status',driverId:req.driverId}); realtime.broadcastDrivers('refresh',{reason:'driver.status'});
  ok(res, requestedRegionId && (status === 'course' || status === 'driving_to_pickup') ? `Cel rejonu: ${requestedRegionId}` : `Status: ${status}`);
}));

app.post('/api/v1/driver/region/current', ...driverGuard, asyncRoute(async (req, res) => {
  const regionId = String(req.body?.regionId || '').trim();
  await tx(async client => {
    const d = (await client.query('SELECT * FROM drivers WHERE id=$1 FOR UPDATE', [req.driverId])).rows[0];
    if (!d?.on_shift) throw statusError(409, 'Najpierw rozpocznij zmianę.');
    const region = (await client.query('SELECT * FROM regions WHERE id=$1 AND active=true', [regionId])).rows[0];
    if (!region) throw statusError(400, 'Nieznany lub nieaktywny rejon.');

    const busy = (await client.query("SELECT id,status FROM orders WHERE (assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress')) OR (offered_driver_id=$1 AND status='offered') ORDER BY updated_at DESC LIMIT 1", [req.driverId])).rows[0];
    const reachedTarget = !!d.target_region_id && d.target_region_id === regionId;

    // OK zawsze oznacza bieżący rejon. Aktywny kurs nie blokuje tej operacji.
    // Nie zmieniamy etapu zlecenia ani statusu kierowcy podczas kursu.
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);

    let joinedQueue = false;
    if (!busy && (d.status === 'available' || d.status === 'in_queue') && region.queue_enabled) {
      let locationOk = true;
      if (Array.isArray(region.polygon) && region.polygon.length >= 3) {
        locationOk = !!d.last_lat && !!d.last_lng && !!d.last_location_at &&
          (Date.now() - new Date(d.last_location_at).getTime()) <= 60000 &&
          pointInPolygon(Number(d.last_lat), Number(d.last_lng), region.polygon);
      }
      if (locationOk) {
        await client.query('INSERT INTO queue_entries(region_id,driver_id,joined_at,priority_score) VALUES($1,$2,now(),0)', [regionId, req.driverId]);
        joinedQueue = true;
      }
    }

    const nextStatus = joinedQueue ? 'in_queue' : d.status;
    const nextTarget = reachedTarget ? null : d.target_region_id;
    await client.query('UPDATE drivers SET current_region_id=$2, target_region_id=$3, status=$4, updated_at=now() WHERE id=$1', [req.driverId, regionId, nextTarget, nextStatus]);

    await driverEvent(client, req.driverId, 'region.current', {
      regionId,
      joinedQueue,
      activeOrderId: busy?.id || null,
      previousRegionId: d.current_region_id || null,
      reachedTarget
    });
    if (reachedTarget) await driverEvent(client, req.driverId, 'target.reached', { regionId, activeOrderId: busy?.id || null });
    await audit(client, req.userId, 'driver.region.current', 'region', regionId, {
      driverId:req.driverId,
      joinedQueue,
      activeOrderId: busy?.id || null,
      previousRegionId: d.current_region_id || null,
      reachedTarget
    });
  });
  realtime.broadcastOperators('refresh',{reason:'driver.region.current',driverId:req.driverId,regionId});
  realtime.broadcastDrivers('refresh',{reason:'driver.region.current'});
  ok(res, `Bieżący rejon: ${regionId}`);
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
    await client.query("UPDATE drivers SET current_region_id=$2, target_region_id=NULL, status='in_queue', updated_at=now() WHERE id=$1", [req.driverId, regionId]);
    await driverEvent(client, req.driverId, 'queue.join', { regionId });
    await audit(client, req.userId, 'driver.queue.join', 'region', regionId, { driverId:req.driverId });
  });
  realtime.broadcastOperators('refresh',{reason:'queue.join',driverId:req.driverId,regionId}); realtime.broadcastDrivers('refresh',{reason:'queue.join'});
  ok(res, `Dołączono do kolejki ${regionId}`);
}));

app.post('/api/v1/driver/queue/leave', ...driverGuard, asyncRoute(async (req, res) => {
  await tx(async client => {
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE drivers SET status=CASE WHEN on_shift THEN 'available' ELSE 'offline' END, current_region_id=NULL, target_region_id=NULL, updated_at=now() WHERE id=$1", [req.driverId]);
    await driverEvent(client, req.driverId, 'queue.leave', {});
    await audit(client, req.userId, 'driver.queue.leave', 'driver', req.driverId);
  });
  realtime.broadcastOperators('refresh',{reason:'queue.leave',driverId:req.driverId}); realtime.broadcastDrivers('refresh',{reason:'queue.leave'});
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
  realtime.broadcastOperators('refresh',{reason:'driver.tariff',driverId:req.driverId}); realtime.broadcastDrivers('refresh',{reason:'driver.tariff'});
  ok(res, `Ustawiono ${tariffId}`);
}));

app.post('/api/v1/driver/location', ...driverGuard, asyncRoute(async (req, res) => {
  const lat = Number(req.body?.lat), lng = Number(req.body?.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) throw statusError(400, 'Nieprawidłowa lokalizacja.');
  const speed = finite(req.body?.speed), heading = finite(req.body?.heading), accuracy = finite(req.body?.accuracy);
  let offer = null, meter = null;
  await tx(async client => {
    const detectedRegion = await detectArea(client, 'regions', lat, lng);
    const fareZone = await detectArea(client, 'fare_zones', lat, lng);
    await client.query(`
      UPDATE drivers SET last_lat=$2,last_lng=$3,last_speed=$4,last_heading=$5,last_accuracy=$6,
        last_location_at=now(), online=true, detected_region_id=$7, current_fare_zone_id=$8, updated_at=now()
      WHERE id=$1
    `, [req.driverId, lat, lng, speed, heading, accuracy, detectedRegion, fareZone]);
    meter = await updateMeterForLocation(client, req.driverId, lat, lng, speed, accuracy);
    offer = (await client.query("SELECT id,pickup_address FROM orders WHERE offered_driver_id=$1 AND status='offered' AND offer_expires_at>now() ORDER BY offer_expires_at DESC LIMIT 1", [req.driverId])).rows[0] || null;
  });
  realtime.broadcastOperators('driver.location', { driverId:req.driverId, lat, lng, speed, heading, accuracy, at:Date.now(), ...(meter||{}) });
  if (meter) realtime.broadcastUser(req.userId, 'refresh', { reason:'meter.location' });
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
    await ensureTrackingToken(client, order.id);
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [req.driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),tracking_enabled=true,tracking_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id, req.driverId]);
    await queueTrackingSms(client, order.id);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',target_region_id=NULL,updated_at=now() WHERE id=$1", [req.driverId, order.id]);
    await driverEvent(client, req.driverId, 'order.accept', { orderId:order.id });
    await orderEvent(client, order.id, req.userId, req.driverId, 'accepted', {});
    await audit(client, req.userId, 'order.accept', 'order', order.id, { driverId:req.driverId });
  });
  realtime.broadcastOperators('refresh',{reason:'order.accept',orderId:req.params.id}); realtime.broadcastDrivers('refresh',{reason:'order.accept'});
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
    await driverEvent(client, req.driverId, 'order.reject', { orderId:order.id });
    await orderEvent(client, order.id, req.userId, req.driverId, 'rejected', {});
    await audit(client, req.userId, 'order.reject', 'order', order.id, { driverId:req.driverId });
  });
  realtime.broadcastOperators('refresh',{reason:'order.reject',orderId:req.params.id}); realtime.broadcastDrivers('refresh',{reason:'order.reject'});
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
      const requestedFinal = Number(req.body?.finalPrice);
      const meterPrice = Math.max(0, Number(order.meter_amount || 0));
      const finalPrice = Number.isFinite(requestedFinal) && requestedFinal > 0 ? requestedFinal : Math.max(meterPrice, Number(order.final_price || 0), Number(order.estimated_price || 0));
      const paymentMethod = String(req.body?.paymentMethod || order.payment_method || 'cash');
      await client.query("UPDATE orders SET status='completed',final_price=$2,payment_method=$3,completed_at=now(),meter_active=false,meter_updated_at=now(),tracking_expires_at=now()+interval '24 hours',updated_at=now() WHERE id=$1", [order.id, finalPrice, paymentMethod]);
      await client.query("UPDATE drivers SET active_order_id=NULL,status='available',target_region_id=NULL,updated_at=now() WHERE id=$1", [req.driverId]);
    } else {
      await client.query(`UPDATE orders SET status=$2, arrived_at=CASE WHEN $2='arrived' THEN now() ELSE arrived_at END, started_at=CASE WHEN $2='in_progress' THEN now() ELSE started_at END, updated_at=now() WHERE id=$1`, [order.id, next]);
      await client.query('UPDATE drivers SET status=$2,updated_at=now() WHERE id=$1', [req.driverId, driverStatus]);
      if (next === 'in_progress') await startMeter(client, order.id, req.driverId);
      if (next === 'arrived') await queueOrderSms(client, order.id, 'arrived');
    }
    if (next === 'completed') {
      const completed = (await client.query('SELECT * FROM orders WHERE id=$1', [order.id])).rows[0];
      await createSettlement(client, completed, req.driverId);
      await queueOrderSms(client, order.id, 'completed');
      await driverEvent(client, req.driverId, 'order.completed', { orderId:order.id, finalPrice:Number(completed.final_price||0), paymentMethod:completed.payment_method });
    } else {
      await driverEvent(client, req.driverId, 'order.'+next, { orderId:order.id });
    }
    await orderEvent(client, order.id, req.userId, req.driverId, next, {});
    await audit(client, req.userId, 'order.advance', 'order', order.id, { next });
  });
  realtime.broadcastOperators('refresh',{reason:'order.advance',orderId:req.params.id,next}); realtime.broadcastDrivers('refresh',{reason:'order.advance'});
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
    await ensureTrackingToken(client, id);
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
    const clientId = nullable(req.body?.clientId);
    const companyId = nullable(req.body?.companyId);
    const voucherCode = String(req.body?.voucherCode || '').trim().toUpperCase();
    if (clientId) {
      const c=(await client.query('SELECT id,blocked FROM clients WHERE id=$1',[clientId])).rows[0];
      if(!c) throw statusError(400,'Nieznany klient.');
      if(c.blocked) throw statusError(409,'Klient jest zablokowany.');
    }
    if (companyId) {
      const c=(await client.query('SELECT id,active,monthly_limit FROM companies WHERE id=$1',[companyId])).rows[0];
      if(!c||!c.active) throw statusError(400,'Nieznana lub nieaktywna firma.');
      const limit=Number(c.monthly_limit||0);
      if(limit>0){
        const used=Number((await client.query(`SELECT COALESCE(sum(gross_amount),0) AS used FROM settlements WHERE company_id=$1 AND created_at>=date_trunc('month',now())`,[companyId])).rows[0]?.used||0);
        const estimate=finite(req.body?.estimatedPrice);
        if(used+estimate>limit) throw statusError(409,`Limit firmy zostałby przekroczony (${used.toFixed(2)} / ${limit.toFixed(2)} zł).`);
      }
    }
    if (voucherCode) {
      const v=(await client.query(`SELECT * FROM vouchers WHERE code=$1 AND active=true AND remaining_amount>0 AND valid_from<=now() AND (valid_until IS NULL OR valid_until>=now())`,[voucherCode])).rows[0];
      if(!v) throw statusError(400,'Voucher jest nieważny lub wykorzystany.');
    }
    const cashless = !!req.body?.cashless || !!companyId || !!voucherCode;
    const derivedPayment = String(req.body?.paymentMethod || (companyId ? 'company' : (voucherCode ? 'other' : 'cash')));
    await client.query(`UPDATE orders SET client_id=$2,company_id=$3,voucher_code=$4,cost_center=$5,booking_ref=$6,cashless=$7,payment_method=$8 WHERE id=$1`,
      [id,clientId,companyId,voucherCode,String(req.body?.costCenter||''),String(req.body?.bookingRef||''),cashless,derivedPayment]);
    await ensureTrackingToken(client,id);
    await orderEvent(client,id,req.userId,null,'created',{dispatchMode,scheduledFor,clientId,companyId,voucherCode});
    if (initialStatus === 'searching_driver') offered = await offerOrder(client, id);
    await audit(client, req.userId, 'dispatch.order.create', 'order', id, { pickupRegionId, offered, dispatchMode, scheduledFor });
  });
  realtime.broadcastDrivers('refresh', { reason:'order.created', orderId:id });
  realtime.broadcastOperators('refresh', { reason:'order.created', orderId:id });
  const message = dispatchMode==='exchange' ? 'Zlecenie dodane do giełdy.' : (offered ? 'Zlecenie utworzone i wysłane do kierowcy.' : 'Zlecenie utworzone.');
  ok(res, message, { orderId:id, offered, dispatchMode });
}));

app.post('/api/v1/dispatch/orders/:id/tracking-link', ...dispatchGuard, asyncRoute(async (req,res) => {
  let token;
  await tx(async client => {
    const order=(await client.query('SELECT id,status,tracking_expires_at FROM orders WHERE id=$1 FOR UPDATE',[req.params.id])).rows[0];
    if(!order) throw statusError(404,'Nie znaleziono zlecenia.');
    const finished=['completed','cancelled'].includes(String(order.status));
    if(finished && order.tracking_expires_at && new Date(order.tracking_expires_at) <= new Date()) {
      throw statusError(409,'Link śledzenia tego zakończonego kursu już wygasł.');
    }
    token=await ensureTrackingToken(client, order.id);
    await client.query(`UPDATE orders SET tracking_enabled=true,
      tracking_expires_at=CASE WHEN status IN ('completed','cancelled') THEN COALESCE(tracking_expires_at,now()+interval '24 hours') ELSE NULL END,
      updated_at=now() WHERE id=$1`,[order.id]);
    await audit(client, req.userId, 'dispatch.tracking.link', 'order', order.id);
  });
  ok(res,'Link śledzenia gotowy.',{trackingUrl:trackingUrl(token)});
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
    await ensureTrackingToken(client, order.id);
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1', [driverId]);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),tracking_enabled=true,tracking_expires_at=NULL,updated_at=now() WHERE id=$1", [order.id, driverId]);
    await queueTrackingSms(client, order.id);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',target_region_id=NULL,updated_at=now() WHERE id=$1", [driverId, order.id]);
    await orderEvent(client,order.id,req.userId,driverId,'assigned',{forced:false});
    await audit(client, req.userId, 'dispatch.order.assign', 'order', order.id, { driverId });
  });
  realtime.broadcastOperators('refresh',{reason:'order.assign',orderId:req.params.id}); realtime.broadcastDrivers('refresh',{reason:'order.assign'});
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
      await client.query("UPDATE drivers SET active_order_id=NULL,status=CASE WHEN on_shift THEN $2 ELSE 'offline' END,target_region_id=NULL,updated_at=now() WHERE id=$1", [order.assigned_driver_id, q ? 'in_queue' : 'available']);
    }
    await client.query("UPDATE orders SET status='cancelled',offered_driver_id=NULL,offer_expires_at=NULL,cancelled_reason=$2,meter_active=false,tracking_expires_at=CASE WHEN tracking_token IS NULL THEN tracking_expires_at ELSE now()+interval '24 hours' END,updated_at=now() WHERE id=$1", [order.id,String(req.body?.reason || '')]);
    await audit(client, req.userId, 'dispatch.order.cancel', 'order', order.id, { reason:String(req.body?.reason || '') });
  });
  realtime.broadcastOperators('refresh',{reason:'order.cancel',orderId:req.params.id}); realtime.broadcastDrivers('refresh',{reason:'order.cancel'});
  ok(res, 'Zlecenie anulowane.');
}));

app.post('/api/v1/dispatch/messages', ...dispatchGuard, asyncRoute(async (req, res) => {
  let title = String(req.body?.title || '').trim();
  const body = String(req.body?.body || '').trim();
  const type = ['info','warning','urgent','system','question'].includes(String(req.body?.type || 'info')) ? String(req.body?.type || 'info') : 'info';
  if (!body) throw statusError(400, 'Wpisz treść wiadomości.');
  const targetType = ['all','driver','region'].includes(String(req.body?.targetType || 'all')) ? String(req.body?.targetType || 'all') : 'all';
  let targetId = String(req.body?.targetId || '').trim();
  if (type === 'question' && !title) title = 'PYTANIE CENTRALI';
  if (targetType === 'all') targetId = '';
  await tx(async client => {
    if (targetType === 'driver') {
      if (!targetId) throw statusError(400, 'Wybierz kierowcę.');
      const target = (await client.query('SELECT id FROM drivers WHERE id=$1 AND enabled=true', [targetId])).rows[0];
      if (!target) throw statusError(400, 'Nieznany lub nieaktywny kierowca.');
    }
    if (targetType === 'region') {
      if (!targetId) throw statusError(400, 'Wybierz region.');
      const target = (await client.query('SELECT id FROM regions WHERE id=$1 AND active=true', [targetId])).rows[0];
      if (!target) throw statusError(400, 'Nieznany lub nieaktywny region.');
    }
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
    await ensureTrackingToken(client, order.id);
    await client.query("UPDATE orders SET status='accepted',assigned_driver_id=$2,accepted_at=now(),tracking_enabled=true,tracking_expires_at=NULL,updated_at=now() WHERE id=$1",[order.id,req.driverId]);
    await queueTrackingSms(client, order.id);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',target_region_id=NULL,updated_at=now() WHERE id=$1",[req.driverId,order.id]);
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
    await ensureTrackingToken(client, order.id);
    await client.query('DELETE FROM queue_entries WHERE driver_id=$1',[driverId]);
    await client.query("UPDATE orders SET status='accepted',dispatch_mode='mandatory',forced=true,assigned_driver_id=$2,offered_driver_id=NULL,offer_expires_at=NULL,accepted_at=now(),tracking_enabled=true,tracking_expires_at=NULL,updated_at=now() WHERE id=$1",[order.id,driverId]);
    await queueTrackingSms(client, order.id);
    await client.query("UPDATE drivers SET active_order_id=$2,status='driving_to_pickup',target_region_id=NULL,updated_at=now() WHERE id=$1",[driverId,order.id]);
    await orderEvent(client,order.id,req.userId,driverId,'forced',{forced:true});
    await driverEvent(client,driverId,'order.forced',{orderId:order.id});
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


// ---------------- FULL RT3000: CRM / FIRMY / VOUCHERY / ROZLICZENIA / RAPORTY ----------------
app.post('/api/v1/dispatch/clients', ...dispatchGuard, asyncRoute(async (req,res) => {
  const name=String(req.body?.name||'').trim(); const phone=String(req.body?.phone||'').trim();
  if(!name && !phone) throw statusError(400,'Podaj nazwę lub telefon klienta.');
  const row=(await pool.query(`INSERT INTO clients(name,phone,email,notes,blocked) VALUES($1,$2,$3,$4,$5) RETURNING id`,
    [name,phone,String(req.body?.email||'').trim(),String(req.body?.notes||'').trim(),!!req.body?.blocked])).rows[0];
  realtime.broadcastOperators('refresh',{reason:'client.created'}); ok(res,'Klient zapisany.',{clientId:String(row.id)});
}));

app.post('/api/v1/dispatch/clients/:id', ...dispatchGuard, asyncRoute(async (req,res) => {
  const r=await pool.query(`UPDATE clients SET name=$2,phone=$3,email=$4,notes=$5,blocked=$6,updated_at=now() WHERE id=$1 RETURNING id`,
    [req.params.id,String(req.body?.name||''),String(req.body?.phone||''),String(req.body?.email||''),String(req.body?.notes||''),!!req.body?.blocked]);
  if(!r.rows[0]) throw statusError(404,'Nie znaleziono klienta.'); realtime.broadcastOperators('refresh',{reason:'client.updated'}); ok(res,'Klient zaktualizowany.');
}));

app.post('/api/v1/dispatch/companies', ...dispatchGuard, asyncRoute(async (req,res) => {
  const name=String(req.body?.name||'').trim(); if(!name) throw statusError(400,'Podaj nazwę firmy.');
  const row=(await pool.query(`INSERT INTO companies(name,nip,billing_email,phone,active,monthly_limit,notes) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id`,
    [name,String(req.body?.nip||'').trim(),String(req.body?.billingEmail||'').trim(),String(req.body?.phone||'').trim(),req.body?.active!==false,finite(req.body?.monthlyLimit),String(req.body?.notes||'')])).rows[0];
  realtime.broadcastOperators('refresh',{reason:'company.created'}); ok(res,'Firma zapisana.',{companyId:String(row.id)});
}));

app.post('/api/v1/dispatch/companies/:id', ...dispatchGuard, asyncRoute(async (req,res) => {
  const r=await pool.query(`UPDATE companies SET name=$2,nip=$3,billing_email=$4,phone=$5,active=$6,monthly_limit=$7,notes=$8,updated_at=now() WHERE id=$1 RETURNING id`,
    [req.params.id,String(req.body?.name||''),String(req.body?.nip||''),String(req.body?.billingEmail||''),String(req.body?.phone||''),req.body?.active!==false,finite(req.body?.monthlyLimit),String(req.body?.notes||'')]);
  if(!r.rows[0]) throw statusError(404,'Nie znaleziono firmy.'); realtime.broadcastOperators('refresh',{reason:'company.updated'}); ok(res,'Firma zaktualizowana.');
}));

app.post('/api/v1/dispatch/vouchers', ...dispatchGuard, asyncRoute(async (req,res) => {
  const code=String(req.body?.code||'').trim().toUpperCase(); const amount=finite(req.body?.amount);
  if(!/^[A-Z0-9_-]{3,32}$/.test(code)) throw statusError(400,'Kod vouchera: 3–32 znaki A-Z/0-9.');
  if(amount<=0) throw statusError(400,'Kwota vouchera musi być większa od zera.');
  const validUntil=req.body?.validUntil?new Date(req.body.validUntil):null; if(validUntil&&Number.isNaN(validUntil.getTime())) throw statusError(400,'Nieprawidłowy termin vouchera.');
  await pool.query(`INSERT INTO vouchers(code,company_id,client_id,amount,remaining_amount,active,valid_until,created_by) VALUES($1,$2,$3,$4,$4,true,$5,$6)
                    ON CONFLICT(code) DO UPDATE SET company_id=EXCLUDED.company_id,client_id=EXCLUDED.client_id,amount=EXCLUDED.amount,remaining_amount=EXCLUDED.remaining_amount,active=true,valid_until=EXCLUDED.valid_until`,
    [code,nullable(req.body?.companyId),nullable(req.body?.clientId),amount,validUntil,req.userId]);
  realtime.broadcastOperators('refresh',{reason:'voucher.saved'}); ok(res,'Voucher zapisany.',{code});
}));

app.post('/api/v1/dispatch/vouchers/:code/enabled', ...dispatchGuard, asyncRoute(async (req,res) => {
  const r=await pool.query('UPDATE vouchers SET active=$2 WHERE code=$1 RETURNING code',[String(req.params.code||'').toUpperCase(),!!req.body?.enabled]);
  if(!r.rows[0]) throw statusError(404,'Nie znaleziono vouchera.'); realtime.broadcastOperators('refresh',{reason:'voucher.enabled'}); ok(res,'Voucher zaktualizowany.');
}));

app.post('/api/v1/dispatch/drivers/:id/queue-priority', ...dispatchGuard, asyncRoute(async (req,res) => {
  const score=Math.max(-999,Math.min(999,Number(req.body?.priority||0)));
  const r=await pool.query('UPDATE queue_entries SET priority_score=$2 WHERE driver_id=$1 RETURNING region_id',[req.params.id,score]);
  if(!r.rows[0]) throw statusError(409,'Kierowca nie jest obecnie w kolejce.');
  await pool.query('UPDATE drivers SET priority_points=$2,updated_at=now() WHERE id=$1',[req.params.id,score]);
  realtime.broadcastOperators('refresh',{reason:'queue.priority'}); realtime.broadcastDrivers('refresh',{reason:'queue.priority'}); ok(res,`Priorytet kolejki: ${score}.`);
}));

app.post('/api/v1/dispatch/settlements/:id/close', ...dispatchGuard, asyncRoute(async (req,res) => {
  const r=await pool.query("UPDATE settlements SET status='settled',settled_at=now() WHERE id=$1 AND status<>'settled' RETURNING id",[req.params.id]);
  if(!r.rows[0]) throw statusError(409,'Rozliczenie jest już zamknięte lub nie istnieje.');
  realtime.broadcastOperators('refresh',{reason:'settlement.closed'}); ok(res,'Rozliczenie zamknięte.');
}));

app.get('/api/v1/dispatch/reports/daily', ...dispatchGuard, asyncRoute(async (req,res) => {
  const day=String(req.query?.day||'').trim();
  const date=day?new Date(day+'T00:00:00Z'):new Date(); if(Number.isNaN(date.getTime())) throw statusError(400,'Nieprawidłowa data.');
  const start=new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth(),date.getUTCDate())); const end=new Date(start.getTime()+86400000);
  const [totals,drivers,payments]=await Promise.all([
    pool.query(`SELECT count(*)::int rides,COALESCE(sum(gross_amount),0) gross FROM settlements WHERE created_at>=$1 AND created_at<$2`,[start,end]),
    pool.query(`SELECT d.taxi_id,d.number,d.name,count(s.id)::int rides,COALESCE(sum(s.gross_amount),0) gross FROM drivers d LEFT JOIN settlements s ON s.driver_id=d.id AND s.created_at>=$1 AND s.created_at<$2 GROUP BY d.id ORDER BY d.number`,[start,end]),
    pool.query(`SELECT payment_method,count(*)::int rides,COALESCE(sum(gross_amount),0) gross FROM settlements WHERE created_at>=$1 AND created_at<$2 GROUP BY payment_method ORDER BY payment_method`,[start,end])
  ]);
  res.json({day:start.toISOString().slice(0,10),totals:{rides:totals.rows[0]?.rides||0,gross:Number(totals.rows[0]?.gross||0)},drivers:drivers.rows.map(x=>({...x,gross:Number(x.gross||0)})),payments:payments.rows.map(x=>({...x,gross:Number(x.gross||0)}))});
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
    const numericCode=String(req.body?.numericCode || id.replace(/\D/g,'')).trim();
    await client.query(`
      INSERT INTO regions(id,numeric_code,name,short_name,active,queue_enabled,priority,polygon)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8::jsonb)
      ON CONFLICT(id) DO UPDATE SET numeric_code=EXCLUDED.numeric_code,name=EXCLUDED.name,short_name=EXCLUDED.short_name,active=EXCLUDED.active,
        queue_enabled=EXCLUDED.queue_enabled,priority=EXCLUDED.priority,polygon=EXCLUDED.polygon
    `, [id,numericCode,String(req.body?.name || id),String(req.body?.shortName || id),req.body?.active !== false,req.body?.queueEnabled !== false,Number(req.body?.priority || 0),JSON.stringify(polygon)]);
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


async function driverEvent(client, driverId, eventType, payload = {}) {
  await client.query('INSERT INTO driver_events(driver_id,event_type,payload) VALUES($1,$2,$3::jsonb)', [driverId,eventType,JSON.stringify(payload||{})]);
}
async function orderEvent(client, orderId, userId, driverId, eventType, payload = {}) {
  await client.query('INSERT INTO order_events(order_id,actor_user_id,actor_driver_id,event_type,payload) VALUES($1,$2,$3,$4,$5::jsonb)', [orderId,userId||null,driverId||null,eventType,JSON.stringify(payload||{})]);
}
async function createSettlement(client, order, driverId) {
  if(!order) return;
  const gross=Math.max(0,Number(order.final_price||order.estimated_price||0));
  let voucherAmount=0;
  if(order.voucher_code){
    const v=(await client.query(`SELECT * FROM vouchers WHERE code=$1 AND active=true FOR UPDATE`,[order.voucher_code])).rows[0];
    if(v){
      voucherAmount=Math.min(gross,Math.max(0,Number(v.remaining_amount||0)));
      const left=Math.max(0,Number(v.remaining_amount||0)-voucherAmount);
      await client.query(`UPDATE vouchers SET remaining_amount=$2,active=($2>0),used_at=CASE WHEN $2<=0 THEN now() ELSE used_at END WHERE code=$1`,[v.code,left]);
    }
  }
  const companyAmount=order.company_id?Math.max(0,gross-voucherAmount):0;
  const driverAmount=gross;
  await client.query(`INSERT INTO settlements(order_id,driver_id,company_id,client_id,payment_method,gross_amount,driver_amount,company_amount,voucher_amount,status)
    VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,'open') ON CONFLICT(order_id) DO UPDATE SET payment_method=EXCLUDED.payment_method,gross_amount=EXCLUDED.gross_amount,driver_amount=EXCLUDED.driver_amount,company_amount=EXCLUDED.company_amount,voucher_amount=EXCLUDED.voucher_amount`,
    [order.id,driverId,order.company_id,order.client_id,order.payment_method,gross,driverAmount,companyAmount,voucherAmount]);
  if(order.client_id) await client.query('UPDATE clients SET rides_count=rides_count+1,total_spend=total_spend+$2,updated_at=now() WHERE id=$1',[order.client_id,gross]);
  const shiftCompanyTotal=(order.company_id || !['cash','card'].includes(String(order.payment_method||''))) ? gross : 0;
  await client.query(`UPDATE shift_sessions SET rides_count=rides_count+1,
    cash_total=cash_total+CASE WHEN $2='cash' THEN $3 ELSE 0 END,
    card_total=card_total+CASE WHEN $2='card' THEN $3 ELSE 0 END,
    company_total=company_total+$4
    WHERE id=(SELECT id FROM shift_sessions WHERE driver_id=$1 AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1)`,[driverId,order.payment_method,gross,shiftCompanyTotal]);
  await client.query("UPDATE orders SET settlement_status='open' WHERE id=$1",[order.id]);
}



function normalizeSmsRecipient(value) {
  let raw=String(value||'').trim().replace(/[\s().-]/g,'');
  if(raw.startsWith('00')) raw='+'+raw.slice(2);
  if(/^\d{9}$/.test(raw)) raw='+48'+raw;
  if(!/^\+\d{8,15}$/.test(raw)) return '';
  return raw;
}

async function queueOrderSms(client, orderId, kind) {
  const supported=new Set(['tracking','arrived','completed']);
  if(!supported.has(kind)) return {queued:false,reason:'unsupported_kind'};
  const row=(await client.query(`SELECT o.id,o.passenger_name,o.passenger_phone,o.tracking_token,o.tracking_sms_status,o.status,o.pickup_address,o.destination_address,o.final_price,
      d.number AS taxi_number,d.taxi_id
    FROM orders o LEFT JOIN drivers d ON d.id=o.assigned_driver_id WHERE o.id=$1 FOR UPDATE OF o`,[orderId])).rows[0];
  if(!row) return {queued:false,reason:'missing_order'};
  const recipient=normalizeSmsRecipient(row.passenger_phone);
  if(!recipient){
    if(kind==='tracking') await client.query(`UPDATE orders SET tracking_sms_status='skipped',tracking_sms_last_error='Brak poprawnego numeru telefonu klienta',updated_at=now() WHERE id=$1`,[orderId]);
    return {queued:false,reason:'invalid_phone'};
  }
  const first=String(row.passenger_name||'').trim().split(/\s+/)[0];
  const hello=first?`${first}, `:'';
  let body='';
  if(kind==='tracking'){
    const token=row.tracking_token || await ensureTrackingToken(client,orderId);
    const link=trackingUrl(token);
    body=`WolfTaxi: ${hello}Twoja taksówka jest w drodze. Śledź kurs na żywo: ${link}`;
  } else if(kind==='arrived'){
    const taxi=Number(row.taxi_number||0)>0?`Taxi ${Number(row.taxi_number)}`:(String(row.taxi_id||'').trim()||'Kierowca');
    body=`WolfTaxi: ${hello}${taxi} jest już na miejscu i czeka${row.pickup_address?` pod adresem ${String(row.pickup_address).trim()}`:''}.`;
  } else if(kind==='completed'){
    const amount=Number(row.final_price||0);
    const amountText=amount>0?` Kwota kursu: ${amount.toFixed(2).replace('.',',')} zł.`:'';
    body=`WolfTaxi: ${hello}dziękujemy za przejazd.${amountText}`;
  }
  const inserted=await client.query(`INSERT INTO sms_outbox(order_id,kind,recipient,body,status)
    VALUES($1,$2,$3,$4,'queued')
    ON CONFLICT(order_id,kind) DO NOTHING RETURNING id`,[orderId,kind,recipient,body]);
  if(kind==='tracking') await client.query(`UPDATE orders SET tracking_sms_status=CASE WHEN tracking_sms_status='sent' THEN 'sent' ELSE 'queued' END,tracking_sms_last_error='',updated_at=now() WHERE id=$1`,[orderId]);
  return {queued:inserted.rowCount>0,recipient,kind};
}

async function queueTrackingSms(client, orderId) {
  return queueOrderSms(client,orderId,'tracking');
}

function trackingUrl(token) {
  return `${String(process.env.PUBLIC_BASE_URL || 'https://wolftaxi.starcore.pl').replace(/\/$/,'')}/track/${encodeURIComponent(String(token||''))}`;
}
async function ensureTrackingToken(client, orderId) {
  const current=(await client.query('SELECT tracking_token FROM orders WHERE id=$1 FOR UPDATE',[orderId])).rows[0];
  if(!current) throw statusError(404,'Nie znaleziono zlecenia.');
  if(current.tracking_token) return String(current.tracking_token);
  for(let i=0;i<5;i++){
    const token=randomBytes(24).toString('base64url');
    try {
      await client.query('UPDATE orders SET tracking_token=$2,tracking_enabled=true WHERE id=$1',[orderId,token]);
      return token;
    } catch(error) { if(error?.code!=='23505') throw error; }
  }
  throw new Error('Nie udało się utworzyć bezpiecznego linku śledzenia.');
}
function haversineMeters(lat1,lng1,lat2,lng2){
  const R=6371000,toRad=v=>v*Math.PI/180;
  const p1=toRad(lat1),p2=toRad(lat2),dp=toRad(lat2-lat1),dl=toRad(lng2-lng1);
  const a=Math.sin(dp/2)**2+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
  return 2*R*Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
}
async function startMeter(client, orderId, driverId){
  const x=(await client.query(`SELECT o.id,o.tariff_id,d.last_lat,d.last_lng,t.start_fee,t.minimum_fare,
    COALESCE(z.multiplier,1) multiplier FROM orders o JOIN drivers d ON d.id=$2
    LEFT JOIN tariffs t ON t.id=COALESCE(o.tariff_id,d.current_tariff_id) LEFT JOIN fare_zones z ON z.id=d.current_fare_zone_id WHERE o.id=$1 FOR UPDATE OF o`,[orderId,driverId])).rows[0];
  if(!x) return;
  await ensureTrackingToken(client,orderId);
  const multiplier=Math.max(0.01,Number(x.multiplier||1));
  const initial=Math.max(Number(x.start_fee||0),Number(x.minimum_fare||0))*multiplier;
  await client.query(`UPDATE orders SET meter_active=true,meter_started_at=now(),meter_last_at=now(),meter_last_lat=$2,meter_last_lng=$3,
    meter_distance_m=0,meter_waiting_seconds=0,meter_amount=$4,meter_updated_at=now(),tracking_enabled=true,tracking_expires_at=NULL WHERE id=$1`,
    [orderId,x.last_lat,x.last_lng,initial]);
}
async function updateMeterForLocation(client,driverId,lat,lng,speed,accuracy){
  const x=(await client.query(`SELECT o.id,o.meter_last_at,o.meter_last_lat,o.meter_last_lng,o.meter_distance_m,o.meter_waiting_seconds,
      t.start_fee,t.price_per_km,t.waiting_price_per_hour,t.minimum_fare,COALESCE(z.multiplier,1) multiplier
    FROM orders o JOIN drivers d ON d.id=o.assigned_driver_id
    LEFT JOIN tariffs t ON t.id=COALESCE(o.tariff_id,d.current_tariff_id) LEFT JOIN fare_zones z ON z.id=d.current_fare_zone_id
    WHERE o.assigned_driver_id=$1 AND o.status='in_progress' AND o.meter_active=true ORDER BY o.updated_at DESC LIMIT 1 FOR UPDATE OF o`,[driverId])).rows[0];
  if(!x) return null;
  const now=Date.now(),prevAt=x.meter_last_at?new Date(x.meter_last_at).getTime():now;
  const dt=Math.max(0,Math.min(30,(now-prevAt)/1000));
  let distance=Math.max(0,Number(x.meter_distance_m||0)),waiting=Math.max(0,Number(x.meter_waiting_seconds||0));
  if(x.meter_last_lat!=null&&x.meter_last_lng!=null&&Number(accuracy||0)<=100&&dt>0){
    const step=haversineMeters(Number(x.meter_last_lat),Number(x.meter_last_lng),lat,lng);
    const maxStep=Math.max(200,dt*70);
    if(Number.isFinite(step)&&step<=maxStep){
      if(Number(speed||0)<2.2 && step<Math.max(15,dt*3)) waiting+=dt; else distance+=step;
    }
  }
  const multiplier=Math.max(0.01,Number(x.multiplier||1));
  const base=Number(x.start_fee||0)+(distance/1000)*Number(x.price_per_km||0)+(waiting/3600)*Number(x.waiting_price_per_hour||0);
  const amount=Math.max(Number(x.minimum_fare||0),base)*multiplier;
  await client.query(`UPDATE orders SET meter_last_at=now(),meter_last_lat=$2,meter_last_lng=$3,meter_distance_m=$4,
    meter_waiting_seconds=$5,meter_amount=$6,meter_updated_at=now() WHERE id=$1`,[x.id,lat,lng,distance,waiting,amount]);
  return { orderId:String(x.id), meterAmount:amount, meterDistanceM:distance, meterWaitingSeconds:waiting, meterUpdatedAt:Date.now() };
}

const port = Math.max(1, Number(process.env.PORT || 8081));
const host = process.env.HOST || '127.0.0.1';
const server = app.listen(port, host, () => console.log(`[WolfTaxi] API 0.8.3 REGION SEMANTICS działa na http://${host}:${port}`));
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
