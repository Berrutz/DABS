const test = require('node:test');
const assert = require('node:assert');
const net = require('net');
const { app } = require('../server');

test('send-fact', async (t) => {
  await t.test('invia tutti i fatti e risponde correttamente', async () => {
    const facts = [
      'John is a doctor.',
      'Luca is a doctor.',
      'Anna is a nurse.',
      'Marta is a patient.',
      'The clinic is in Rome.',
      'Anna works at the clinic.',
      'John works at the clinic.',
      'Marta has a fever.',
      'Marta has a cough.',
      'Luca has completed the security training.',
      'Marco has an active contract.',
      'Marco has not completed the security training.'
    ];

    const sockets = [];
    class FakeSocket {
      constructor() {
        this.data = '';
        sockets.push(this);
      }
      connect(port, host, cb) {
        this.port = port;
        if (cb) process.nextTick(cb);
        return this;
      }
      write(chunk) {
        this.data += chunk;
      }
      end() {
        if (this.onClose) this.onClose();
      }
      on(event, handler) {
        if (event === 'close') this.onClose = handler;
        if (event === 'error') this.onError = handler;
      }
    }
    const original = net.Socket;
    net.Socket = FakeSocket;

    const server = app.listen(0);
    const port = server.address().port;

    for (const fact of facts) {
      const res = await fetch(`http://localhost:${port}/send-fact`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fact })
      });

      assert.strictEqual(res.status, 200);
      assert.strictEqual(await res.text(), 'Fatto');
    }

    assert.deepStrictEqual(
      sockets.map(s => s.data),
      facts.map(f => f + '\n')
    );

    server.close();
    net.Socket = original;
  });

  await t.test('gestisce errori di connessione', async () => {
    class ErrorSocket {
      connect() {
        process.nextTick(() => {
          if (this.onError) this.onError(new Error('boom'));
        });
        return this;
      }
      on(event, handler) {
        if (event === 'error') this.onError = handler;
      }
    }
    const original = net.Socket;
    net.Socket = ErrorSocket;

    const server = app.listen(0);
    const port = server.address().port;

    const res = await fetch(`http://localhost:${port}/send-fact`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fact: 'John is a doctor.' })
    });

    assert.strictEqual(res.status, 500);
    const text = await res.text();
    assert.ok(text.includes('Errore nella comunicazione'));

    server.close();
    net.Socket = original;
  });
});