// web-ui/server-main.js
const express = require('express');
const path = require('path');
const http = require('http');
const net = require('net');
const os = require('os');

const app = express();
const PORT = process.env.MONITOR_PORT || 4100;

// WebSocket
const { WebSocketServer } = require('ws');
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// in-memory state
let platform = {
  hostname: os.hostname(),
  mainHost: process.env.PUBLIC_HOST || 'jade-main',
  port: parseInt(process.env.PORT || '1099', 10),
  httpMTP: parseInt(process.env.HTTP_PORT || '7778', 10),
  up: false,
  agents: [],        
  lastUpdate: null,
};

// log helper
const log = (...a) => console.log('[monitor]', ...a);

// ----------------------------------------------------
// HTTP routes


app.get('/monitor/monitor.css', (req, res) => {
  res.type('text/css'); 
  res.sendFile(path.join(__dirname, 'public', 'monitor.css'));
});

app.get('/monitor/monitor.js', (req, res) => {
  res.type('text/javascript');
  res.sendFile(path.join(__dirname, 'public', 'monitor.js'));
});

app.get('/monitor', (req, res) => {
  res.sendFile(path.join(__dirname, 'public/monitor.html'));
});

// Main heartbeat API (used by the client)
app.get('/api/status', (req, res) => {
  res.json(platform);
});

// receive DF events (from a small Java agent)
app.use(express.json());
app.post('/df-event', (req, res) => {
  const { type, name, clazz, when } = req.body || {};
  if (!type || !name) return res.status(400).json({ ok: false, error: 'bad payload' });

  platform.lastUpdate = Date.now();

  let eventType = type;
  if (type === 'REGISTER') {
    // evita duplicati
    const idx = platform.agents.findIndex(a => a.name === name);
    const now = Date.now();
    if (idx === -1) {
      platform.agents.push({ name, class: clazz || '?', since: when || now, lastSeen: now });
    } else {
      platform.agents[idx] = { ...platform.agents[idx], class: clazz || platform.agents[idx].class, lastSeen: now };
      eventType = 'ALIVE';
    }
  } else if (type === 'DEREGISTER' || type === 'DE-REGISTER') {
    platform.agents = platform.agents.filter(a => a.name !== name);
  }

  // broadcast via WS
  broadcast({ kind: 'df', payload: { type: eventType, name, clazz, when } });
  res.json({ ok: true });
});

// Garbage-collect stale agents (e.g., container kill - CTRL+C)
const STALE_TTL_MS = 45_000; // > ping period (10s) and DF rescan (10s)
setInterval(() => {
  const now = Date.now();
  const before = platform.agents.length;
  const staleNames = platform.agents
    .filter(a => a.lastSeen && (now - a.lastSeen > STALE_TTL_MS))
    .map(a => a.name);
  if (staleNames.length) {
    platform.agents = platform.agents.filter(a => !staleNames.includes(a.name));
    staleNames.forEach(name => broadcast({ kind: 'df', payload: { type: 'DEREGISTER', name, when: now } }));
    platform.lastUpdate = now;
  }
}, 5000);

// simple TCP check on the HTTP-MTP socket without sending HTTP
// avoids "Malformed POST" warnings in JADE HTTP-MTP
function checkMainAlive() {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    const timeoutMs = 1200;

    const onDone = (ok) => {
      try { socket.destroy(); } catch (_) {}
      resolve(ok);
    };

    socket.setTimeout(timeoutMs);
    socket.once('connect', () => onDone(true));
    socket.once('timeout', () => onDone(false));
    socket.once('error', () => onDone(false));

    socket.connect({ host: platform.mainHost, port: platform.httpMTP });
  });
}

// periodic poll (heartbeat only until the DF agent is conected)
setInterval(async () => {
  const alive = await checkMainAlive();
  if (alive !== platform.up) {
    platform.up = alive;
    platform.lastUpdate = Date.now();
    broadcast({ kind: 'heartbeat', payload: { up: alive, ts: platform.lastUpdate } });
    log(`main ${alive ? 'UP' : 'DOWN'} @ ${platform.mainHost}:${platform.port}`);
  }
}, 1500);

// websocket broadcast
function broadcast(msg) {
  const data = JSON.stringify(msg);
  wss.clients.forEach(c => { if (c.readyState === 1) c.send(data); });
}

// startup
server.listen(PORT, () => {
  log(`🌐 Monitor UI on http://localhost:${PORT}/monitor`);
  log(`🔭 Tracking main ${platform.mainHost}:${platform.port} (HTTP-MTP ${platform.httpMTP})`);
});
