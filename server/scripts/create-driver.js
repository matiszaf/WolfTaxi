require('dotenv').config();
const bcrypt = require('bcryptjs');
const { randomUUID } = require('crypto');
const { pool, tx } = require('../src/db');

const email = String(process.env.DRIVER_EMAIL || '').trim().toLowerCase();
const password = String(process.env.DRIVER_PASSWORD || '');
const taxiId = String(process.env.DRIVER_TAXI_ID || 'TX1').trim().toUpperCase();
const number = Number(process.env.DRIVER_NUMBER || 1);
const name = String(process.env.DRIVER_NAME || `Taxi ${number}`).trim();

if (!email || !password) {
  console.error('Ustaw DRIVER_EMAIL i DRIVER_PASSWORD (oraz opcjonalnie DRIVER_TAXI_ID, DRIVER_NUMBER, DRIVER_NAME).');
  process.exit(2);
}
if (password.length < 8) { console.error('Hasło musi mieć co najmniej 8 znaków.'); process.exit(2); }
if (!Number.isInteger(number) || number <= 0) { console.error('Numer taxi musi być dodatnią liczbą całkowitą.'); process.exit(2); }

(async () => {
  try {
    const hash = await bcrypt.hash(password, 12);
    let result;
    await tx(async client => {
      const existingUser = (await client.query('SELECT * FROM users WHERE lower(email)=lower($1) LIMIT 1', [email])).rows[0];
      const existingDriver = (await client.query('SELECT * FROM drivers WHERE lower(email)=lower($1) OR taxi_id=$2 LIMIT 1', [email,taxiId])).rows[0];
      const userId = existingUser?.id || existingDriver?.user_id || existingDriver?.id || randomUUID();
      const roles = Array.isArray(existingUser?.roles) ? existingUser.roles : [];
      if (!roles.includes('driver')) roles.push('driver');
      await client.query(`
        INSERT INTO users(id,email,password_hash,display_name,roles,enabled)
        VALUES($1,$2,$3,$4,$5,true)
        ON CONFLICT(id) DO UPDATE SET email=EXCLUDED.email,password_hash=EXCLUDED.password_hash,display_name=EXCLUDED.display_name,
          roles=EXCLUDED.roles,enabled=true,updated_at=now()
      `, [userId,email,hash,name,roles]);

      const driverId = existingDriver?.id || randomUUID();
      await client.query(`
        INSERT INTO drivers(id,user_id,taxi_id,number,name,email,password_hash,enabled,on_shift,status,manual_tariff_allowed,current_tariff_id)
        VALUES($1,$2,$3,$4,$5,$6,$7,true,false,'offline',true,(SELECT id FROM tariffs WHERE active=true ORDER BY sort_order,id LIMIT 1))
        ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,taxi_id=EXCLUDED.taxi_id,number=EXCLUDED.number,name=EXCLUDED.name,
          email=EXCLUDED.email,password_hash=EXCLUDED.password_hash,enabled=true,updated_at=now()
      `, [driverId,userId,taxiId,number,name,email,hash]);
      result = { userId, driverId };
    });
    console.log(`[WolfTaxi] Kierowca ${taxiId} (${email}) gotowy. User ID: ${result.userId}, Driver ID: ${result.driverId}`);
  } finally { await pool.end(); }
})().catch(error => { console.error(error); process.exit(1); });
