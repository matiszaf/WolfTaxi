function pointInPolygon(lat, lng, polygon) {
  if (!Array.isArray(polygon) || polygon.length < 3) return false;
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i] || {};
    const b = polygon[j] || {};
    const ay = Number(a.lat), ax = Number(a.lng), by = Number(b.lat), bx = Number(b.lng);
    if (![ay, ax, by, bx].every(Number.isFinite)) continue;
    const intersects = ((ay > lat) !== (by > lat)) &&
      (lng < ((bx - ax) * (lat - ay)) / ((by - ay) || 1e-12) + ax);
    if (intersects) inside = !inside;
  }
  return inside;
}

async function detectArea(client, table, lat, lng) {
  const { rows } = await client.query(`SELECT id, polygon FROM ${table} WHERE active = true ORDER BY priority ASC, id ASC`);
  for (const row of rows) {
    if (pointInPolygon(lat, lng, row.polygon)) return row.id;
  }
  return null;
}

module.exports = { pointInPolygon, detectArea };
