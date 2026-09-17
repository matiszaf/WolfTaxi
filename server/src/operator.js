const { mapOrder } = require('./snapshot');

function text(value) { return value == null ? '' : String(value); }
function ms(value) { return value ? new Date(value).getTime() : 0; }

async function getOperatorSnapshot(client, includeUsers = false) {
  const [drivers, orders, regions, tariffs, zones, messages, users, alerts] = await Promise.all([
    client.query(`
      SELECT d.id,d.user_id,d.taxi_id,d.number,d.name,d.vehicle_id,d.enabled,d.on_shift,d.status,d.online,
             d.current_region_id,d.target_region_id,d.detected_region_id,d.current_tariff_id,d.current_fare_zone_id,d.active_order_id,
             d.last_lat,d.last_lng,d.last_speed,d.last_heading,d.last_accuracy,d.last_location_at,d.priority_points,d.blocked_reason,
             q.region_id AS queue_region_id,q.priority_score,
             CASE WHEN q.driver_id IS NULL THEN 0 ELSE
               (SELECT count(*)::int FROM queue_entries q2 WHERE q2.region_id=q.region_id AND (q2.priority_score>q.priority_score OR (q2.priority_score=q.priority_score AND q2.joined_at<=q.joined_at)))
             END AS queue_position,
             CASE WHEN q.driver_id IS NULL THEN 0 ELSE
               (SELECT count(*)::int FROM queue_entries q3 WHERE q3.region_id=q.region_id)
             END AS queue_size
      FROM drivers d
      LEFT JOIN queue_entries q ON q.driver_id=d.id
      ORDER BY d.number,d.taxi_id
    `),
    client.query(`SELECT * FROM orders WHERE status <> 'completed' OR completed_at > now() - interval '12 hours' ORDER BY created_at DESC LIMIT 150`),
    client.query(`SELECT id,name,short_name,active,queue_enabled,priority,polygon FROM regions ORDER BY priority,id`),
    client.query(`SELECT id,name,short_name,active,start_fee,price_per_km,waiting_price_per_hour,minimum_fare,sort_order FROM tariffs ORDER BY sort_order,id`),
    client.query(`SELECT id,name,active,multiplier,default_tariff_id,priority,polygon FROM fare_zones ORDER BY priority,id`),
    client.query(`SELECT m.id,m.type,m.title,m.body,m.requires_ack,m.voice_read,m.target_type,m.target_id,m.active,m.created_at,
      count(r.user_id) FILTER (WHERE r.answer='yes')::int AS yes_count,
      count(r.user_id) FILTER (WHERE r.answer='no')::int AS no_count
      FROM messages m LEFT JOIN message_response r ON r.message_id=m.id
      WHERE m.active=true GROUP BY m.id ORDER BY m.created_at DESC LIMIT 50`),
    includeUsers ? client.query(`SELECT id,email,display_name,roles,enabled,created_at,updated_at FROM users ORDER BY display_name,email`) : Promise.resolve({ rows: [] }),
    client.query(`
      SELECT a.id,a.alert_type,a.status,a.note,a.lat,a.lng,a.created_at,a.acknowledged_at,
             d.id AS driver_id,d.taxi_id,d.number,d.name
      FROM safety_alerts a JOIN drivers d ON d.id=a.driver_id
      WHERE a.status IN ('active','acknowledged') ORDER BY a.created_at DESC
    `)
  ]);

  return {
    drivers: drivers.rows.map(d => ({
      id:text(d.id), userId:text(d.user_id), taxiId:text(d.taxi_id), number:d.number || 0, name:text(d.name), vehicleId:text(d.vehicle_id),
      enabled:!!d.enabled, onShift:!!d.on_shift, status:text(d.status), online:!!d.online,
      currentRegionId:text(d.current_region_id), targetRegionId:text(d.target_region_id), detectedRegionId:text(d.detected_region_id),
      currentTariffId:text(d.current_tariff_id), currentFareZoneId:text(d.current_fare_zone_id), activeOrderId:text(d.active_order_id),
      queueRegionId:text(d.queue_region_id), queuePosition:d.queue_position || 0, queueSize:d.queue_size || 0,
      queuePriority:Number(d.priority_score || 0), priorityPoints:Number(d.priority_points || 0), blockedReason:text(d.blocked_reason),
      lat:d.last_lat == null ? null : Number(d.last_lat), lng:d.last_lng == null ? null : Number(d.last_lng),
      speed:Number(d.last_speed || 0), heading:Number(d.last_heading || 0), accuracy:Number(d.last_accuracy || 0),
      lastLocationAt:ms(d.last_location_at)
    })),
    orders: orders.rows.map(mapOrder),
    regions: regions.rows.map(r => ({ id:text(r.id), name:text(r.name), shortName:text(r.short_name), active:!!r.active, queueEnabled:!!r.queue_enabled, priority:r.priority || 0, polygon:r.polygon || [] })),
    tariffs: tariffs.rows.map(t => ({ id:text(t.id), name:text(t.name), shortName:text(t.short_name), active:!!t.active, startFee:Number(t.start_fee||0), pricePerKm:Number(t.price_per_km||0), waitingPricePerHour:Number(t.waiting_price_per_hour||0), minimumFare:Number(t.minimum_fare||0), sortOrder:t.sort_order || 0 })),
    fareZones: zones.rows.map(z => ({ id:text(z.id), name:text(z.name), active:!!z.active, multiplier:Number(z.multiplier||1), defaultTariffId:text(z.default_tariff_id), priority:z.priority || 0, polygon:z.polygon || [] })),
    messages: messages.rows.map(m => ({ id:text(m.id), type:text(m.type), title:text(m.title), body:text(m.body), requiresAck:!!m.requires_ack, voiceRead:m.voice_read!==false, targetType:text(m.target_type), targetId:text(m.target_id), active:!!m.active, createdAt:ms(m.created_at), yesCount:m.yes_count||0, noCount:m.no_count||0 })),
    users: users.rows.map(u => ({ id:text(u.id), email:text(u.email), name:text(u.display_name), roles:Array.isArray(u.roles)?u.roles:[], enabled:!!u.enabled, createdAt:ms(u.created_at), updatedAt:ms(u.updated_at) })),
    alerts: alerts.rows.map(a => ({ id:text(a.id), type:text(a.alert_type), status:text(a.status), note:text(a.note), lat:a.lat==null?null:Number(a.lat), lng:a.lng==null?null:Number(a.lng), createdAt:ms(a.created_at), acknowledgedAt:ms(a.acknowledged_at), driverId:text(a.driver_id), taxiId:text(a.taxi_id), number:a.number||0, driverName:text(a.name) })),
    generatedAt: Date.now()
  };
}

module.exports = { getOperatorSnapshot };
