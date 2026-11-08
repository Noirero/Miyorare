'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const code = fs.readFileSync(path.join(__dirname, '..', 'chikari.js'), 'utf8');

function fixture(routes) {
  const urls = [];
  const exports = {};
  const fetchApi = async (url) => {
    urls.push(url);
    const found = routes.find(([match]) => match(url));
    if (!found) throw new Error('Unexpected URL: ' + url);
    const result = found[1](url);
    return { ok: true, status: 200, json: async () => result };
  };
  const imports = {
    '@libs/fetch': { fetchApi },
    '@libs/filterInputs': { FilterTypes: { Picker: 1, ExcludableCheckboxGroup: 2 } },
    '@libs/defaultCover': { defaultCover: 'fallback' },
    '@libs/novelStatus': { NovelStatus: { Ongoing: 1, Completed: 2, OnHiatus: 3, Cancelled: 4, Unknown: 0 } },
  };
  vm.runInNewContext(code, { exports, require: name => imports[name], URL, console });
  return { source: exports.default, urls };
}

test('novel listing uses LNReader 1.0.2 series endpoint, not manga or legacy novel list', async () => {
  const { source, urls } = fixture([
    [u => u.includes('/api/series?type=novel&'), () => ({ items: [{ title: 'Pilot', slug: 'pilot', cover_url: '' }] })],
  ]);
  assert.equal(source.id, 'chikari');
  assert.equal(source.version, '1.0.2.1');
  assert.equal((await source.popularNovels(1, {}))[0].path, 'pilot');
  assert.equal((await source.searchNovels('test novel', 2))[0].path, 'pilot');
  assert(urls.every(url => url.includes('type=novel')));
  assert(urls.some(url => url.includes('offset=36')));
});

test('incremental chapter batches preserve legacy chapter URLs and can fetch long novels', async () => {
  const full = Array.from({ length: 450 }, (_, i) => ({ number: i + 1, title: 'Chapter ' + (i+1) }));
  const { source, urls } = fixture([
    [u => u.endsWith('/api/novels/pilot'), () => ({
      title: 'Pilot', status: 'releasing', chapter_count: 450, authors: [], genres: []
    })],
    [u => u.includes('/api/novels/pilot/chapters?'), u => {
      const offset = Number(new URL(u).searchParams.get('offset'));
      const limit = Number(new URL(u).searchParams.get('limit'));
      assert.equal(limit, 200);
      return { total: full.length, items: full.slice(offset, offset + limit) };
    }],
  ]);
  const initial = await source.parseNovel('pilot');
  assert.equal(initial.chapters.length, 200);
  assert.equal(initial.totalPages, 3);
  assert.equal(initial.chapters[0].path, 'api/novels/pilot/chapters/1/read');
  assert.equal(urls.length, 2, 'Details must not eagerly download every chapter');
  const page2 = await source.parsePage('pilot', 2);
  const page3 = await source.parsePage('pilot', 3);
  assert.equal(page2.chapters.length, 200);
  assert.equal(page3.chapters.length, 50);
  assert.equal(page3.chapters[49].path, 'api/novels/pilot/chapters/450/read');
});

test('chapter HTML escapes untrusted text; legacy text path and locked chapter remain readable', async () => {
  const { source } = fixture([
    [u => u.endsWith('/api/novels/pilot/chapters/1/read'), () => ({ body: '<script>x</script>\nParagraph & text' })],
    [u => u.endsWith('/api/novels/pilot/chapters/2/read'), () => ({ locked: true, lock_reason: 'Restricted' })],
  ]);
  assert.equal(await source.parseChapter('api/novels/pilot/chapters/1/read'),
    '<p>&lt;script&gt;x&lt;/script&gt;</p><p>Paragraph &amp; text</p>');
  assert.equal(await source.parseChapter('api/novels/pilot/chapters/2/read'), '<p>Restricted</p>');
  assert.equal(source.resolveUrl('pilot', true), 'https://chikari.moe/novels/pilot');
});
