const jwt = require('jsonwebtoken');
const { pool } = require('./db');

function normalizeRoles(value) {
  if (!Array.isArray(value)) return [];
  const allowed = new Set(['driver', 'dispatcher', 'admin']);
  return [...new Set(value.map(v => String(v || '').trim().toLowerCase()).filter(v => allowed.has(v)))];
}

function signUser(user) {
  const roles = normalizeRoles(user.roles);
  return jwt.sign(
    { sub: user.id, roles, name: user.display_name || '' },
    process.env.JWT_SECRET,
    { expiresIn: process.env.JWT_EXPIRES_IN || '7d' }
  );
}

function requireAuth(req, res, next) {
  const raw = req.header('authorization') || '';
  const token = raw.startsWith('Bearer ') ? raw.slice(7) : '';
  if (!token) return res.status(401).json({ message: 'Brak sesji.' });
  try {
    const payload = jwt.verify(token, process.env.JWT_SECRET);
    let roles = normalizeRoles(payload?.roles);
    // Zgodność z tokenami WolfTaxi 0.3, które miały pojedyncze pole role='driver'.
    if (roles.length === 0 && payload?.role === 'driver') roles = ['driver'];
    if (!payload?.sub || roles.length === 0) throw new Error('invalid session');
    req.userId = String(payload.sub);
    req.roles = roles;
    req.auth = payload;
    next();
  } catch (_) {
    return res.status(401).json({ message: 'Sesja wygasła. Zaloguj się ponownie.' });
  }
}

function requireRole(...allowedRoles) {
  const allowed = new Set(allowedRoles);
  return (req, res, next) => {
    if (!Array.isArray(req.roles) || !req.roles.some(role => allowed.has(role))) {
      return res.status(403).json({ message: 'Brak uprawnień do tej części systemu.' });
    }
    next();
  };
}

async function requireDriver(req, res, next) {
  if (!Array.isArray(req.roles) || !req.roles.includes('driver')) {
    return res.status(403).json({ message: 'Konto nie ma roli kierowcy.' });
  }
  try {
    const result = await pool.query('SELECT id,enabled FROM drivers WHERE user_id=$1 LIMIT 1', [req.userId]);
    const driver = result.rows[0];
    if (!driver) return res.status(403).json({ message: 'Brak profilu kierowcy dla tego konta.' });
    if (!driver.enabled) return res.status(403).json({ message: 'Profil kierowcy jest zablokowany.' });
    req.driverId = String(driver.id);
    next();
  } catch (error) {
    next(error);
  }
}

module.exports = { signUser, requireAuth, requireRole, requireDriver, normalizeRoles };
