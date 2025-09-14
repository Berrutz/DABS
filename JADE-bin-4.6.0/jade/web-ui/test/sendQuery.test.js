const test = require('node:test');
const assert = require('node:assert');
const net = require('net');
const { app, resultServer } = require('../server');

test('send-query', async (t) => {
  await t.test('invia le query e riceve le risposte', async () => {
    const data = [
      { q: 'Can John treat patients?', a: 'Yes, John can treat patients.' },
      { q: 'Can Anna treat patients?', a: 'No, Anna cannot treat patients.' },
      { q: 'What should be done with Marta?', a: 'Marta must be isolated.' },
      { q: 'Can Marco access the VPN?', a: 'No, Marco cannot access the VPN.' },
      { q: 'Who works at the clinic?', a: 'Anna and John work at the clinic.' },
      { q: 'Who is a doctor?', a: 'John and Luca are doctors.' }
    ];

    const sockets = [];
    class FakeSocket {
      constructor() { this.data = ''; sockets.push(this); }
      connect(port, host, cb) { this.port = port; if (cb) process.nextTick(cb); return this; }
      write(chunk) { this.data += chunk; }
      end() { if (this.onClose) this.onClose(); }
      on(event, handler) {
        if (event === 'close') this.onClose = handler;
        if (event === 'error') this.onError = handler;
      }
    }
    const original = net.Socket;
    net.Socket = FakeSocket;

    await new Promise(resolve => resultServer.listen(0, resolve));
    const rPort = resultServer.address().port;

    const server = app.listen(0);
    const port = server.address().port;

    for (const { q, a } of data) {
      const res = await fetch(`http://localhost:${port}/send-query`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: q })
      });
      assert.strictEqual(res.status, 200);

      await new Promise(resolve => {
        const client = new original();
        client.connect(rPort, '127.0.0.1', () => {
          client.write(a + '\n');
          client.end();
        });
        client.on('close', resolve);
        client.on('error', resolve);
      });

      const ansRes = await fetch(`http://localhost:${port}/get-query-result`);
      const body = await ansRes.json();
      assert.strictEqual(body.answer, a);
    }

    assert.deepStrictEqual(
      sockets.map(s => s.data),
      data.map(({ q }) => q + '\n')
    );

    server.close();
    resultServer.close();
    net.Socket = original;
  });

  await t.test('gestisce errori di connessione', async () => {
    class ErrorSocket {
      connect() {
        process.nextTick(() => { if (this.onError) this.onError(new Error('boom')); });
        return this;
      }
      on(event, handler) { if (event === 'error') this.onError = handler; }
    }
    const original = net.Socket;
    net.Socket = ErrorSocket;

    const server = app.listen(0);
    const port = server.address().port;

    const res = await fetch(`http://localhost:${port}/send-query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Can John treat patients?' })
    });

    assert.strictEqual(res.status, 500);
    const text = await res.text();
    assert.ok(text.includes('Errore nella comunicazione'));

    server.close();
    net.Socket = original;
  });
});
