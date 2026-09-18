const { WebSocketServer, WebSocket } = require('ws');
const jwt = require('jsonwebtoken');
const { normalizeRoles } = require('./auth');

let wss = null;
const clients = new Set();

function attachRealtime(server) {
  wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (request, socket, head) => {
    try {
      const url = new URL(request.url, 'http://localhost');
      if (url.pathname !== '/ws') return socket.destroy();
      const token = url.searchParams.get('token') || '';
      const payload = jwt.verify(token, process.env.JWT_SECRET);
      let roles = normalizeRoles(payload?.roles);
      if (roles.length === 0 && payload?.role === 'driver') roles = ['driver'];
      if (!payload?.sub || roles.length === 0) throw new Error('invalid session');
      request.wolftaxi = { userId: String(payload.sub), roles };
      wss.handleUpgrade(request, socket, head, ws => wss.emit('connection', ws, request));
    } catch (_) {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
    }
  });

  wss.on('connection', (ws, request) => {
    ws.wolftaxi = request.wolftaxi;
    ws.isAlive = true;
    clients.add(ws);
    ws.on('pong', () => { ws.isAlive = true; });
    ws.on('close', () => clients.delete(ws));
    send(ws, 'hello', { roles: ws.wolftaxi.roles, userId: ws.wolftaxi.userId, version: '0.7.0' });
  });

  const heartbeat = setInterval(() => {
    for (const ws of clients) {
      if (!ws.isAlive) { ws.terminate(); clients.delete(ws); continue; }
      ws.isAlive = false;
      try { ws.ping(); } catch (_) {}
    }
  }, 25000);
  heartbeat.unref?.();
}

function send(ws, type, data = {}) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  try { ws.send(JSON.stringify({ type, data, at: Date.now() })); } catch (_) {}
}

function broadcast(type, data = {}, predicate = null) {
  for (const ws of clients) {
    if (predicate && !predicate(ws.wolftaxi || {})) continue;
    send(ws, type, data);
  }
}

function broadcastOperators(type, data = {}) {
  broadcast(type, data, auth => auth.roles?.some(r => r === 'dispatcher' || r === 'admin'));
}

function broadcastDrivers(type, data = {}) {
  broadcast(type, data, auth => auth.roles?.includes('driver'));
}

function broadcastUser(userId, type, data = {}) {
  broadcast(type, data, auth => String(auth.userId) === String(userId));
}

function stats() {
  let driver = 0, operator = 0;
  for (const ws of clients) {
    const roles = ws.wolftaxi?.roles || [];
    if (roles.includes('driver')) driver++;
    if (roles.includes('dispatcher') || roles.includes('admin')) operator++;
  }
  return { total: clients.size, driver, operator };
}

module.exports = { attachRealtime, broadcast, broadcastOperators, broadcastDrivers, broadcastUser, stats };
