require('dotenv').config();
const fs = require('fs');
const path = require('path');
const { pool } = require('../src/db');

(async () => {
  try {
    const sql = fs.readFileSync(path.join(__dirname, '..', 'sql', 'schema.sql'), 'utf8');
    await pool.query(sql);
    console.log('[WolfTaxi] Schemat PostgreSQL gotowy.');
  } finally {
    await pool.end();
  }
})().catch(error => { console.error(error); process.exit(1); });
