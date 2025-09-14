const test = require('node:test');
const assert = require('node:assert');
const { app, setLatestAnswer } = require('../server');

test('GET /get-query-result returns the latest answer', async () => {
  const server = app.listen(0);
  const port = server.address().port;

  setLatestAnswer('prova');

  const res = await fetch(`http://localhost:${port}/get-query-result`);
  const data = await res.json();
  assert.strictEqual(data.answer, 'prova');

  server.close();
});

