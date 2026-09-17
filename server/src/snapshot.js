function ms(value) { return value ? new Date(value).getTime() : 0; }
function text(value) { return value == null ? '' : String(value); }

function mapDriver(row) {
  return {
    id: text(row.id), number: row.number || 0, name: text(row.name), vehicleId: text(row.vehicle_id),
    enabled: !!row.enabled, onShift: !!row.on_shift, manualTariffAllowed: !!row.manual_tariff_allowed,
    status: text(row.status || 'offline'), currentRegionId: text(row.current_region_id),
    currentTariffId: text(row.current_tariff_id), currentFareZoneId: text(row.current_fare_zone_id),
    activeOrderId: text(row.active_order_id), priorityPoints: Number(row.priority_points || 0),
    blockedReason: text(row.blocked_reason), ttsEnabled: row.tts_enabled !== false,
    exchangeEnabled: row.exchange_enabled !== false
  };
}

function mapOrder(row) {
  if (!row) return null;
  return {
    id: text(row.id), pickupAddress: text(row.pickup_address), destinationAddress: text(row.destination_address),
    pickupRegionId: text(row.pickup_region_id), pickupFareZoneId: text(row.pickup_fare_zone_id),
    destinationFareZoneId: text(row.destination_fare_zone_id), tariffId: text(row.tariff_id),
    assignedDriverId: text(row.assigned_driver_id), offeredDriverId: text(row.offered_driver_id),
    passengerName: text(row.passenger_name), passengerPhone: text(row.passenger_phone), notes: text(row.notes),
    passengerCount: row.passenger_count || 1, cardRequired: !!row.card_required,
    estimatedPrice: Number(row.estimated_price || 0), finalPrice: Number(row.final_price || 0),
    createdAt: ms(row.created_at), offerExpiresAt: ms(row.offer_expires_at), status: text(row.status),
    paymentMethod: text(row.payment_method || 'cash'), dispatchMode: text(row.dispatch_mode || 'queue'),
    forced: !!row.forced, source: text(row.source || 'dispatch'), scheduledFor: ms(row.scheduled_for),
    luggage: !!row.luggage, pet: !!row.pet, englishRequired: !!row.english_required,
    mineWarning: !!row.mine_warning, requirements: row.requirements || {}, cancelledReason: text(row.cancelled_reason)
  };
}

async function getSnapshot(client, driverId) {
  const driverResult = await client.query('SELECT * FROM drivers WHERE id=$1', [driverId]);
  const driver = driverResult.rows[0];
  if (!driver) throw Object.assign(new Error('Brak profilu kierowcy.'), { statusCode: 404 });

  const [regions, tariffs, zones, active, offer, exchange, history, messages, queue, alerts, regionStats] = await Promise.all([
    client.query("SELECT id,name,short_name,active,queue_enabled,priority,polygon FROM regions WHERE active=true ORDER BY priority,id"),
    client.query("SELECT id,name,short_name,active,start_fee,price_per_km,waiting_price_per_hour,minimum_fare,sort_order FROM tariffs WHERE active=true ORDER BY sort_order,id"),
    client.query("SELECT id,name,active,multiplier,default_tariff_id,priority,polygon FROM fare_zones WHERE active=true ORDER BY priority,id"),
    client.query("SELECT * FROM orders WHERE assigned_driver_id=$1 AND status IN ('accepted','en_route','arrived','in_progress') ORDER BY updated_at DESC LIMIT 1", [driverId]),
    client.query("SELECT * FROM orders WHERE offered_driver_id=$1 AND status='offered' AND offer_expires_at > now() ORDER BY offer_expires_at DESC LIMIT 1", [driverId]),
    client.query(`
      SELECT * FROM orders
      WHERE status='exchange' AND dispatch_mode='exchange'
        AND (scheduled_for IS NULL OR scheduled_for <= now() + interval '24 hours')
        AND (pickup_region_id IS NULL OR pickup_region_id='' OR pickup_region_id=$2 OR $2 IS NULL)
      ORDER BY mine_warning DESC, scheduled_for NULLS FIRST, created_at ASC
      LIMIT 40
    `, [driverId, driver.current_region_id]),
    client.query("SELECT * FROM orders WHERE assigned_driver_id=$1 AND status='completed' ORDER BY completed_at DESC NULLS LAST, updated_at DESC LIMIT 20", [driverId]),
    client.query(`
      SELECT m.id,m.type,m.title,m.body,m.created_at,m.requires_ack,m.voice_read,m.target_type,m.target_id,
             (a.user_id IS NOT NULL) AS acknowledged
      FROM messages m
      LEFT JOIN message_ack a ON a.message_id=m.id AND a.user_id=$2
      WHERE m.active=true AND (
        m.target_type='all' OR
        (m.target_type='driver' AND m.target_id=$1::text) OR
        (m.target_type='region' AND m.target_id=COALESCE($3,''))
      )
      ORDER BY m.created_at DESC LIMIT 30
    `, [driverId, driver.user_id, driver.current_region_id]),
    client.query(`
      SELECT x.position, x.total, x.priority_score
      FROM (
        SELECT driver_id, priority_score,
               row_number() OVER (ORDER BY priority_score DESC, joined_at ASC)::int AS position,
               count(*) OVER ()::int AS total
        FROM queue_entries
        WHERE region_id = (SELECT region_id FROM queue_entries WHERE driver_id=$1 LIMIT 1)
      ) x WHERE x.driver_id=$1
    `, [driverId]),
    client.query("SELECT id,alert_type,status,note,lat,lng,created_at FROM safety_alerts WHERE driver_id=$1 AND status IN ('active','acknowledged') ORDER BY created_at DESC LIMIT 1", [driverId]),
    client.query(`
      SELECT r.id,r.short_name,r.name,
             count(q.driver_id)::int AS queued,
             count(*) FILTER (WHERE d.status='available')::int AS available,
             count(*) FILTER (WHERE d.status IN ('driving_to_pickup','at_pickup','in_ride'))::int AS busy
      FROM regions r
      LEFT JOIN queue_entries q ON q.region_id=r.id
      LEFT JOIN drivers d ON d.id=q.driver_id
      WHERE r.active=true
      GROUP BY r.id,r.short_name,r.name,r.priority
      ORDER BY r.priority,r.id
    `)
  ]);

  return {
    driver: mapDriver(driver),
    regions: regions.rows.map(r => ({ id:text(r.id), name:text(r.name), shortName:text(r.short_name), active:!!r.active, queueEnabled:!!r.queue_enabled, priority:r.priority || 0, polygon:r.polygon || [] })),
    tariffs: tariffs.rows.map(t => ({ id:text(t.id), name:text(t.name), shortName:text(t.short_name), active:!!t.active, startFee:Number(t.start_fee||0), pricePerKm:Number(t.price_per_km||0), waitingPricePerHour:Number(t.waiting_price_per_hour||0), minimumFare:Number(t.minimum_fare||0) })),
    fareZones: zones.rows.map(z => ({ id:text(z.id), name:text(z.name), active:!!z.active, multiplier:Number(z.multiplier||1), defaultTariffId:text(z.default_tariff_id), polygon:z.polygon || [] })),
    activeOrder: mapOrder(active.rows[0]),
    offer: mapOrder(offer.rows[0]),
    exchange: exchange.rows.map(mapOrder),
    history: history.rows.map(mapOrder),
    messages: messages.rows.map(m => ({ id:text(m.id), type:text(m.type), title:text(m.title), body:text(m.body), createdAt:ms(m.created_at), requiresAck:!!m.requires_ack, voiceRead:m.voice_read!==false, targetType:text(m.target_type), targetId:text(m.target_id), acknowledged:!!m.acknowledged })),
    queuePosition: queue.rows[0]?.position || 0,
    queueSize: queue.rows[0]?.total || 0,
    queuePriority: queue.rows[0]?.priority_score || 0,
    safetyAlert: alerts.rows[0] ? { id:text(alerts.rows[0].id), type:text(alerts.rows[0].alert_type), status:text(alerts.rows[0].status), note:text(alerts.rows[0].note), lat:alerts.rows[0].lat==null?null:Number(alerts.rows[0].lat), lng:alerts.rows[0].lng==null?null:Number(alerts.rows[0].lng), createdAt:ms(alerts.rows[0].created_at) } : null,
    regionStats: regionStats.rows.map(r => ({ id:text(r.id), shortName:text(r.short_name), name:text(r.name), queued:r.queued||0, available:r.available||0, busy:r.busy||0 }))
  };
}

module.exports = { getSnapshot, mapOrder };
