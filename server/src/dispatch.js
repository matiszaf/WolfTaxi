const { tx } = require('./db');

const offerSeconds = () => Math.max(5, Number(process.env.OFFER_TIMEOUT_SECONDS || 20));

async function offerOrder(client, orderId, excludeDriverId = null) {
  const orderResult = await client.query('SELECT * FROM orders WHERE id = $1 FOR UPDATE', [orderId]);
  const order = orderResult.rows[0];
  if (!order) return false;
  if (!['created', 'searching_driver', 'offered'].includes(order.status)) return false;
  if (!order.pickup_region_id) {
    await client.query("UPDATE orders SET status='no_driver', offered_driver_id=NULL, offer_expires_at=NULL, updated_at=now() WHERE id=$1", [orderId]);
    return false;
  }

  const params = [order.pickup_region_id];
  let exclude = '';
  if (excludeDriverId) {
    params.push(excludeDriverId);
    exclude = `AND d.id <> $${params.length}`;
  }
  const candidate = await client.query(`
    SELECT d.id
    FROM queue_entries q
    JOIN drivers d ON d.id = q.driver_id
    WHERE q.region_id = $1
      AND d.enabled = true
      AND d.on_shift = true
      AND d.status = 'in_queue'
      ${exclude}
    ORDER BY q.joined_at ASC
    LIMIT 1
  `, params);

  if (!candidate.rows[0]) {
    await client.query("UPDATE orders SET status='no_driver', offered_driver_id=NULL, offer_expires_at=NULL, updated_at=now() WHERE id=$1", [orderId]);
    return false;
  }

  const driverId = candidate.rows[0].id;
  await client.query(`
    UPDATE orders
    SET status='offered', offered_driver_id=$2,
        offer_expires_at=now() + ($3 || ' seconds')::interval,
        updated_at=now()
    WHERE id=$1
  `, [orderId, driverId, String(offerSeconds())]);
  await client.query("UPDATE drivers SET status='offer_received', updated_at=now() WHERE id=$1", [driverId]);
  return true;
}

async function expireOffers() {
  await tx(async client => {
    const expired = await client.query(`
      SELECT id, offered_driver_id
      FROM orders
      WHERE status='offered' AND offer_expires_at IS NOT NULL AND offer_expires_at <= now()
      FOR UPDATE SKIP LOCKED
    `);
    for (const order of expired.rows) {
      const driverId = order.offered_driver_id;
      if (driverId) {
        const q = await client.query('SELECT 1 FROM queue_entries WHERE driver_id=$1 LIMIT 1', [driverId]);
        await client.query('UPDATE drivers SET status=$2, updated_at=now() WHERE id=$1', [driverId, q.rows[0] ? 'in_queue' : 'available']);
      }
      await client.query("UPDATE orders SET status='searching_driver', offered_driver_id=NULL, offer_expires_at=NULL, updated_at=now() WHERE id=$1", [order.id]);
      await offerOrder(client, order.id, driverId);
    }
  });
}

module.exports = { offerOrder, expireOffers };
