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

      INSERT INTO regions(id,numeric_code,name,short_name,active,queue_enabled,priority,polygon)
      VALUES
        ('R21','21','Witomino','R21',true,true,21,'[]'::jsonb),
        ('R23','23','Karwiny','R23',true,true,23,'[]'::jsonb),
        ('R24','24','WICZLINO','R24',true,true,24,'[]'::jsonb),
        ('R26','26','Dąbrowa','R26',true,true,26,'[]'::jsonb),
        ('R37','37','CENTRUM','R37',true,true,37,'[]'::jsonb),
        ('R39','39','CHYLONIA','R39',true,true,39,'[]'::jsonb),
        ('R87','87','TRÓJMIASTO','R87',true,true,87,'[]'::jsonb),
        ('R89','89','DOM','R89',true,true,89,'[]'::jsonb),
        ('R1','1','Region 1','R1',true,true,99,'[]'::jsonb)
      ON CONFLICT(id) DO UPDATE SET numeric_code=excluded.numeric_code,name=excluded.name,short_name=excluded.short_name,
        active=excluded.active,queue_enabled=excluded.queue_enabled,priority=excluded.priority;

      INSERT INTO fare_zones(id,name,active,multiplier,default_tariff_id,priority,polygon)
      VALUES('S1','Strefa 1',true,1.0,'T1',1,'[]'::jsonb)
      ON CONFLICT(id) DO UPDATE SET name=excluded.name,active=excluded.active,multiplier=excluded.multiplier,default_tariff_id=excluded.default_tariff_id,priority=excluded.priority;

      INSERT INTO system_settings(key,value) VALUES
        ('dispatch', '{"offerTimeoutSeconds":20,"scheduledReleaseMinutes":15,"queuePriorityEnabled":true}'::jsonb),
        ('terminal', '{"tts":true,"numericCodes":true,"sos":true}'::jsonb)
      ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=now();
    `);
    const count = await pool.query('SELECT count(*)::int AS count FROM messages');
    if ((count.rows[0]?.count || 0) === 0) {
      await pool.query("INSERT INTO messages(type,title,body) VALUES('info','System','WolfTaxi 0.6 FULL RT3000 działa na backendzie Oracle.')");
    }
    console.log('[WolfTaxi] Seed 0.6: taryfy, rejony RT3000, strefy i ustawienia gotowe.');
  } finally { await pool.end(); }
})().catch(error => { console.error(error); process.exit(1); });
