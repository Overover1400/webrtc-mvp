const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const rooms = new Map(); // room -> Set<ws>

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('signaling ok\n');
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
  ws.room = null;
  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch { return; }

    if (msg.type === 'join') {
      const room = String(msg.room || '');
      if (!room) return;
      ws.room = room;
      if (!rooms.has(room)) rooms.set(room, new Set());
      const peers = rooms.get(room);
      peers.add(ws);
      ws.send(JSON.stringify({ type: 'joined', room, peers: peers.size }));
      for (const p of peers) {
        if (p !== ws && p.readyState === 1) {
          p.send(JSON.stringify({ type: 'peer-joined', peers: peers.size }));
        }
      }
      return;
    }

    if (ws.room && rooms.has(ws.room)) {
      for (const p of rooms.get(ws.room)) {
        if (p !== ws && p.readyState === 1) p.send(raw.toString());
      }
    }
  });

  ws.on('close', () => {
    if (ws.room && rooms.has(ws.room)) {
      const peers = rooms.get(ws.room);
      peers.delete(ws);
      if (peers.size === 0) rooms.delete(ws.room);
      else for (const p of peers) if (p.readyState === 1) p.send(JSON.stringify({ type: 'peer-left' }));
    }
  });
});

server.listen(PORT, () => console.log(`signaling on :${PORT}`));
