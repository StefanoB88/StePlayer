import { test } from 'node:test';
import assert from 'node:assert/strict';
import { claimLists, parseAddress, scanForPhones, subnetOf, toInputs, type LinkHttp } from '../src/core/pairing/phoneLink.ts';

function fakeNetwork(phoneHost: string, code: string): LinkHttp & { calls: number } {
  const net = {
    calls: 0,
    async request(method: 'GET' | 'POST', url: string, body: string | null) {
      net.calls++;
      const u = new URL(url);
      if (u.hostname !== phoneHost || u.port !== '47620') return { status: 0, text: '' };
      if (method === 'GET' && u.pathname === '/steplayer/v1/hello') return { status: 200, text: '{"app":"StePlayer","v":1,"device":"Telefono di prova"}' };
      if (method === 'POST' && u.pathname === '/steplayer/v1/claim') {
        const sent = JSON.parse(body || '{}').code;
        if (sent !== code) return { status: 403, text: '{"error":"Codice errato"}' };
        return { status: 200, text: JSON.stringify({ lists: [
          { kind: 'xtream', name: 'Casa', serverUrl: 'http://server.esempio:8080', username: 'demo', password: 'segreta' },
          { kind: 'm3u', name: 'Seconda', listUrl: 'https://server.esempio/lista.m3u', epgUrl: 'https://server.esempio/guida.xml' },
          { kind: 'm3u', name: 'Rotta' },
        ] }) };
      }
      return { status: 404, text: '' };
    },
  };
  return net;
}

test('subnet and address parsing accept only private IPv4', () => {
  assert.equal(subnetOf('192.168.50.144'), '192.168.50');
  assert.equal(subnetOf('10.1.2.3'), '10.1.2');
  assert.equal(subnetOf('172.20.0.9'), '172.20.0');
  assert.equal(subnetOf('8.8.8.8'), null);
  assert.equal(subnetOf('server.esempio'), null);
  assert.deepEqual(parseAddress('192.168.1.23:47621'), { host: '192.168.1.23', port: 47621 });
  assert.deepEqual(parseAddress(' 192.168.1.23 '), { host: '192.168.1.23', port: 47620 });
  assert.equal(parseAddress('8.8.8.8:47620'), null);
});

test('scan finds the phone and claims the lists with the code', async () => {
  const net = fakeNetwork('192.168.50.77', '4821');
  const found: string[] = [];
  const phones = await scanForPhones(net, '192.168.50', { prefer: ['192.168.50.77'], concurrency: 16, onFound: (p) => found.push(p.device) });
  assert.equal(phones.length, 1);
  assert.equal(phones[0].host, '192.168.50.77');
  assert.deepEqual(found, ['Telefono di prova']);
  assert.ok(net.calls >= 254, 'the whole /24 is scanned');

  assert.deepEqual(await claimLists(net, phones[0], '1111'), { ok: false, reason: 'wrong-code' });
  const ok = await claimLists(net, phones[0], '4821');
  assert.equal(ok.ok, true);
  if (ok.ok) {
    assert.equal(ok.lists.length, 2, 'incomplete entries are dropped');
    assert.deepEqual(ok.lists[0], { kind: 'xtream', name: 'Casa', serverUrl: 'http://server.esempio:8080', username: 'demo', password: 'segreta', userAgent: null });
    assert.equal(ok.lists[1].kind, 'm3u');
  }
});

test('scan stops when cancelled', async () => {
  const net = fakeNetwork('192.168.50.250', '1234');
  const phones = await scanForPhones(net, '192.168.50', { concurrency: 1, isCancelled: () => net.calls >= 20 });
  assert.ok(net.calls < 254);
  assert.equal(phones.length, 0);
});

test('toInputs ignores unknown kinds', () => {
  assert.deepEqual(toInputs([{ kind: 'other', name: 'x' }]), []);
});
