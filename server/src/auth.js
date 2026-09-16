const jwt = require('jsonwebtoken');

function signDriver(driver) {
  return jwt.sign(
    { sub: driver.id, taxiId: driver.taxi_id, role: 'driver' },
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
    if (!payload || payload.role !== 'driver') throw new Error('invalid role');
    req.driverId = payload.sub;
    req.auth = payload;
    next();
  } catch (_) {
    return res.status(401).json({ message: 'Sesja wygasła. Zaloguj się ponownie.' });
  }
}

module.exports = { signDriver, requireAuth };
