require('dotenv').config();
const { pool } = require('../src/db');

(async () => {
  try {
    await pool.query(`
      INSERT INTO tariffs(id,name,short_name,active,start_fee,price_per_km,waiting_price_per_hour,minimum_fare,sort_order)
      VALUES
        ('T1','Taryfa 1','T1',true,8.00,4.00,60.00,15.00,1),
        ('T2','Taryfa 2','T2',true,8.00,6.00,60.00,15.00,2)
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,short_name=excluded.short_name,active=excluded.active,start_fee=excluded.start_fee,
        price_per_km=excluded.price_per_km,waiting_price_per_hour=excluded.waiting_price_per_hour,minimum_fare=excluded.minimum_fare,sort_order=excluded.sort_order;

      INSERT INTO regions(id,name,short_name,active,queue_enabled,priority,polygon)
      VALUES('R1','Region 1','R1',true,true,1,'[]'::jsonb)
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,short_name=excluded.short_name,active=excluded.active,queue_enabled=excluded.queue_enabled,priority=excluded.priority;

      INSERT INTO fare_zones(id,name,active,multiplier,default_tariff_id,priority,polygon)
      VALUES('S1','Strefa 1',true,1.0,'T1',1,'[]'::jsonb)
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,active=excluded.active,multiplier=excluded.multiplier,default_tariff_id=excluded.default_tariff_id,priority=excluded.priority;
    `);
    const count = await pool.query('SELECT count(*)::int AS count FROM messages');
    if ((count.rows[0]?.count || 0) === 0) {
      await pool.query("INSERT INTO messages(type,title,body) VALUES('info','System','WolfTaxi działa na własnym backendzie Oracle.')");
    }
    console.log('[WolfTaxi] Seed: T1, T2, R1, S1 gotowe.');
  } finally { await pool.end(); }
})().catch(error => { console.error(error); process.exit(1); });
