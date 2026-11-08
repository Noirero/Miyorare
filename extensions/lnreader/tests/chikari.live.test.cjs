'use strict';
const assert = require('node:assert/strict');

// External contract probe, not a mock. Never prints copyrighted chapter text.
const base = 'https://chikari.moe';
async function request(path, required = true) {
  const response = await fetch(base + path, {
    headers: { Accept: 'application/json', 'User-Agent': 'Miyorare-Chikari-Compatibility-Probe/1.0' },
    signal: AbortSignal.timeout(12000),
  });
  const text = await response.text();
  console.log(path.split('?')[0], 'HTTP', response.status, 'bytes', Buffer.byteLength(text));
  if (!response.ok) {
    if (!required) return null; // legacy listing is diagnostic only
    throw new Error(path.split('?')[0] + ' returned HTTP ' + response.status);
  }
  let json;
  try { json = JSON.parse(text); }
  catch { throw new Error(path.split('?')[0] + ' returned non-JSON payload'); }
  return json;
}

(async () => {
  const series = await request('/api/series?type=novel&sort=popular&limit=2&offset=0');
  assert(Array.isArray(series.items), '1.0.2 catalog API requires items array');
  assert(series.items.length > 0, 'novel list unexpectedly empty');
  assert(series.items.every(item => item.slug && item.title), 'novel catalog entry missing slug/title');

  const legacy = await request('/api/novels?sort=popular&limit=2&offset=0', false);
  console.log('legacy /api/novels catalog:', legacy ? 'JSON received' : 'unavailable');

  const slug = encodeURIComponent(series.items[0].slug);
  const details = await request('/api/novels/' + slug);
  assert(typeof details.title === 'string' && details.title.length > 0, 'novel detail missing title');

  const chapters = await request('/api/novels/' + slug + '/chapters?order=asc&limit=2&offset=0');
  assert(Array.isArray(chapters.items), 'chapter list must provide items array');
  assert(chapters.items.length > 0, 'selected public novel has no chapters');
  const first = chapters.items[0];
  const token = first.number === null || first.number === undefined ? 'oneshot' : String(first.number);
  const read = await request('/api/novels/' + slug + '/chapters/' + encodeURIComponent(token) + '/read');
  assert(read && (typeof read.body === 'string' || read.locked === true), 'chapter read body/lock contract missing');
  console.log('LIVE API CONTRACT PASS: series, detail, chapter pagination, chapter read');
})().catch(error => {
  console.error('LIVE API CONTRACT BLOCKED:', error.message);
  process.exitCode = 1;
});
