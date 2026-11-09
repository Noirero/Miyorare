'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

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

  const search = await request('/api/series?type=novel&sort=popular&q=shadow&limit=10&offset=0');
  assert(Array.isArray(search.items), 'search must return an items array');
  assert(search.items.some(item => String(item.slug).toLowerCase().includes('shadow')),
    'known public novel should be searchable');

  const legacy = await request('/api/novels?sort=popular&limit=2&offset=0', false);
  console.log('legacy /api/novels catalog:', legacy ? 'JSON received' : 'unavailable');

  const slug = encodeURIComponent(series.items[0].slug);
  const details = await request('/api/novels/' + slug);
  assert(typeof details.title === 'string' && details.title.length > 0, 'novel detail missing title');

  const chapters = await request('/api/novels/' + slug + '/chapters?order=asc&limit=2&offset=0');
  assert(Array.isArray(chapters.items), 'chapter list must provide items array');
  assert(chapters.items.length > 0, 'selected public novel has no chapters');
  const secondBatch = await request('/api/novels/' + slug + '/chapters?order=asc&limit=2&offset=2');
  assert(Array.isArray(secondBatch.items), 'offset chapter pagination must return items array');
  if (Number(chapters.total) > 2) {
    assert(secondBatch.items.length > 0, 'nonempty second chapter batch expected');
  }
  const first = chapters.items[0];
  const token = first.number === null || first.number === undefined ? 'oneshot' : String(first.number);
  const read = await request('/api/novels/' + slug + '/chapters/' + encodeURIComponent(token) + '/read');
  assert(read && (typeof read.body === 'string' || read.locked === true), 'chapter read body/lock contract missing');
  // Execute the *actual bundled LNReader adapter* against those same live endpoints.
  // This is still a JS/network contract test; it is not Android/EPUB evidence.
  const exports = {};
  const fetchApi = (url) => fetch(url, {
    headers: { Accept: 'application/json', 'User-Agent': 'Miyorare-Chikari-Compatibility-Probe/1.0' },
    signal: AbortSignal.timeout(12000),
  });
  const libraries = {
    '@libs/fetch': { fetchApi },
    '@libs/filterInputs': { FilterTypes: { Picker: 1, ExcludableCheckboxGroup: 2 } },
    '@libs/defaultCover': { defaultCover: 'fallback' },
    '@libs/novelStatus': { NovelStatus: { Ongoing: 1, Completed: 2, OnHiatus: 3, Cancelled: 4, Unknown: 0 } },
  };
  const pluginCode = fs.readFileSync(path.resolve(__dirname, '..', 'chikari.js'), 'utf8');
  vm.runInNewContext(pluginCode, {
    exports, require: (name) => {
      assert(Object.hasOwn(libraries, name), 'unexpected JS host dependency');
      return libraries[name];
    }, console, URL,
  });
  const chikari = exports.default;
  assert.equal(chikari.id, 'chikari');
  assert.equal(chikari.version, '1.0.2.1');
  const listings = await chikari.popularNovels(1, {});
  assert(listings.length > 0 && listings.every(item => item.path && item.name));
  const searchResults = await chikari.searchNovels('shadow', 1);
  assert(searchResults.some(item => String(item.path).includes('shadow')), 'parser search must find Shadow Slave');
  const book = await chikari.parseNovel('shadow-slave');
  assert.equal(book.path, 'shadow-slave');
  assert(book.chapters.length > 0 && book.chapters.length <= 200, 'parser must return a bounded first batch');
  assert(book.totalPages >= 1 && book.totalPages < 10000, 'parser must report finite pagination');
  assert(book.chapters[0].path.startsWith('api/novels/shadow-slave/chapters/'), 'chapter identity/path continuity');
  if (book.totalPages > 1) {
    const nextPage = await chikari.parsePage('shadow-slave', 2);
    assert(nextPage.chapters.length > 0 && nextPage.chapters.length <= 200, 'incremental page must be bounded');
  }
  const html = await chikari.parseChapter(book.chapters[0].path);
  assert(typeof html === 'string' && html.includes('<p>'), 'parser chapter HTML contract');
  console.log('LIVE JS PARSER PASS: listing, search, details, bounded parsePage, chapter HTML');
  console.log('LIVE API CONTRACT PASS: series, search, detail, chapter pagination, chapter read');
})().catch(error => {
  console.error('LIVE API CONTRACT BLOCKED:', error.message);
  process.exitCode = 1;
});
