require('dotenv').config();
const bcrypt = require('bcryptjs');
const { randomUUID } = require('crypto');
const { pool } = require('../src/db');

const email = String(process.env.DRIVER_EMAIL || '').trim().toLowerCase();
const password = String(process.env.DRIVER_PASSWORD || '');
const taxiId = String(process.env.DRIVER_TAXI_ID || 'TX1').trim();
const number = Number(process.env.DRIVER_NUMBER || 1);
const name = String(process.env.DRIVER_NAME || `Taxi ${number}`).trim();

if (!email || !password) {
  console.error('Ustaw DRIVER_EMAIL i DRIVER_PASSWORD (oraz opcjonalnie DRIVER_TAXI_ID, DRIVER_NUMBER, DRIVER_NAME).');
  process.exit(2);
}
if (password.length < 8) {
  console.error('Hasło musi mieć co najmniej 8 znaków.');
  process.exit(2);
}

(async () => {
  try {
    const hash = await bcrypt.hash(password, 12);
    const existing = await pool.query('SELECT id FROM drivers WHERE lower(email)=lower($1) OR taxi_id=$2 LIMIT 1', [email, taxiId]);
    const id = existing.rows[0]?.id || randomUUID();
    await pool.query(`
      INSERT INTO drivers(id,taxi_id,number,name,email,password_hash,enabled,on_shift,status,manual_tariff_allowed,current_tariff_id)
      VALUES($1,$2,$3,$4,$5,$6,true,false,'offline',true,'T1')
      ON CONFLICT(id) DO UPDATE SET taxi_id=excluded.taxi_id,number=excluded.number,name=excluded.name,email=excluded.email,password_hash=excluded.password_hash,enabled=true,updated_at=now()
    `, [id, taxiId, number, name, email, hash]);
    console.log(`[WolfTaxi] Kierowca ${taxiId} (${email}) gotowy. ID: ${id}`);
  } finally { await pool.end(); }
})().catch(error => { console.error(error); process.exit(1); });
