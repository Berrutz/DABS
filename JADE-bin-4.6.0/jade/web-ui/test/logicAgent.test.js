const test = require('node:test');
const assert = require('node:assert');
const net = require('net');
const { app, resultServer } = require('../server');

test('resultServer aggiorna latestAnswer quando riceve dati', async () => {
  await new Promise(resolve => resultServer.listen(0, resolve));
  const rPort = resultServer.address().port;

  const client = net.createConnection({ port: rPort }, () => {
    client.write('risposta\n');
    client.end();
  });
  client.on('error', () => {});

  await new Promise(r => setTimeout(r, 50));

  const server = app.listen(0);
  const port = server.address().port;
  const res = await fetch(`http://localhost:${port}/get-query-result`);
  const body = await res.json();
  assert.strictEqual(body.answer, 'risposta');

  server.close();
  resultServer.close();
});

test('resultServer gestisce errori senza crash', () => {
  assert.doesNotThrow(() => resultServer.emit('error', new Error('boom')));
});
