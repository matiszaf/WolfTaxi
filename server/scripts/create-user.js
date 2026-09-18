require('dotenv').config();
const bcrypt = require('bcryptjs');
const { randomUUID } = require('crypto');
const { pool, tx } = require('../src/db');
const { normalizeRoles } = require('../src/auth');

const email = String(process.env.USER_EMAIL || '').trim().toLowerCase();
const password = String(process.env.USER_PASSWORD || '');
const name = String(process.env.USER_NAME || email).trim();
const roles = normalizeRoles(String(process.env.USER_ROLES || 'dispatcher').split(',').map(v => v.trim()));
const taxiId = String(process.env.USER_TAXI_ID || '').trim().toUpperCase();
const number = Number(process.env.USER_TAXI_NUMBER || 0);

if (!email || !password || roles.length === 0) {
  console.error('Ustaw USER_EMAIL, USER_PASSWORD, USER_NAME oraz USER_ROLES=driver,dispatcher,admin.');
  process.exit(2);
}
if (password.length < 8) { console.error('Hasło musi mieć co najmniej 8 znaków.'); process.exit(2); }
if (roles.includes('driver') && (!taxiId || !Number.isInteger(number) || number <= 0)) {
  console.error('Dla roli driver ustaw USER_TAXI_ID i USER_TAXI_NUMBER.'); process.exit(2);
}

(async () => {
  try {
    const hash = await bcrypt.hash(password, 12);
    let userId;
    await tx(async client => {
      const existing = (await client.query('SELECT * FROM users WHERE lower(email)=lower($1) LIMIT 1', [email])).rows[0];
      userId = existing?.id || randomUUID();
      await client.query(`
        INSERT INTO users(id,email,password_hash,display_name,roles,enabled)
        VALUES($1,$2,$3,$4,$5,true)
        ON CONFLICT(id) DO UPDATE SET email=EXCLUDED.email,password_hash=EXCLUDED.password_hash,display_name=EXCLUDED.display_name,
          roles=EXCLUDED.roles,enabled=true,updated_at=now()
      `, [userId,email,hash,name,roles]);
      if (roles.includes('driver')) {
        const existingDriver = (await client.query('SELECT * FROM drivers WHERE user_id=$1 OR taxi_id=$2 LIMIT 1', [userId,taxiId])).rows[0];
        const driverId = existingDriver?.id || randomUUID();
        await client.query(`
          INSERT INTO drivers(id,user_id,taxi_id,number,name,email,password_hash,enabled,on_shift,status,manual_tariff_allowed,current_tariff_id)
          VALUES($1,$2,$3,$4,$5,$6,$7,true,false,'offline',true,(SELECT id FROM tariffs WHERE active=true ORDER BY sort_order,id LIMIT 1))
          ON CONFLICT(id) DO UPDATE SET user_id=EXCLUDED.user_id,taxi_id=EXCLUDED.taxi_id,number=EXCLUDED.number,name=EXCLUDED.name,
            email=EXCLUDED.email,password_hash=EXCLUDED.password_hash,enabled=true,updated_at=now()
        `, [driverId,userId,taxiId,number,name,email,hash]);
      }
    });
    console.log(`[WolfTaxi] Konto ${email} gotowe. Role: ${roles.join(', ')}. User ID: ${userId}`);
  } finally { await pool.end(); }
})().catch(error => { console.error(error); process.exit(1); });
