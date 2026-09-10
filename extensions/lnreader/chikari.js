/*
 * Chikari LNReader plugin compatibility override for Miyorare.
 * Based on the public Chikari plugin from LNReader/lnreader-plugins v1.0.1.
 *
 * Miyorare-specific change: chapter metadata is paged through parsePage() so opening
 * Details does not wait for every chapter of very long novels to download first.
 *
 * MIT License
 * Copyright (c) 2021 Rajarshee Chatterjee
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var fetchApi = require('@libs/fetch').fetchApi;
var FilterTypes = require('@libs/filterInputs').FilterTypes;
var defaultCover = require('@libs/defaultCover').defaultCover;
var NovelStatus = require('@libs/novelStatus').NovelStatus;

var CHAPTER_PAGE_SIZE = 500;

function encode(value) {
  return encodeURIComponent(String(value));
}

function chapterFromApi(novelPath, chapter) {
  var number = chapter.number;
  return {
    name: chapter.title || ('Chapter ' + number),
    path: 'api/novels/' + encode(novelPath) + '/chapters/' + encode(number) + '/read',
    releaseTime: chapter.created_at || '',
    chapterNumber: typeof number === 'number' ? number : Number(number) || undefined,
  };
}

function statusFromApi(status) {
  switch (status) {
    case 'releasing': return NovelStatus.Ongoing;
    case 'completed': return NovelStatus.Completed;
    case 'hiatus': return NovelStatus.OnHiatus;
    case 'cancelled': return NovelStatus.Cancelled;
    default: return NovelStatus.Unknown;
  }
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

async function getJson(url) {
  var response = await fetchApi(url);
  if (!response || !response.ok) {
    throw new Error('Chikari request failed' + (response ? ': HTTP ' + response.status : ''));
  }
  return response.json();
}

var Chikari = {
  id: 'chikari',
  name: 'Chikari',
  icon: 'src/en/chikari/icon.png',
  site: 'https://chikari.moe',
  lang: 'English',
  // Compatibility revision so existing upstream 1.0.1 installs receive this optimization once.
  // Upstream 1.0.2+ still compares newer and automatically takes precedence.
  version: '1.0.1.1',
  imageRequestInit: undefined,

  filters: {
    sort: {
      type: FilterTypes.Picker,
      label: 'Sort By',
      value: 'popular',
      options: [
        { label: 'Popular', value: 'popular' },
        { label: 'Trending', value: 'trending' },
        { label: 'Top Rated', value: 'top_rated' },
        { label: 'Recently Updated', value: 'updated' },
        { label: 'Recently Added', value: 'added' },
        { label: 'Most Bookmarked', value: 'most_bookmarked' },
      ],
    },
    genres: {
      type: FilterTypes.ExcludableCheckboxGroup,
      label: 'Genres',
      value: { include: [], exclude: [] },
      options: [
        { label: 'Action', value: 'action' },
        { label: 'Adventure', value: 'adventure' },
        { label: 'Comedy', value: 'comedy' },
        { label: 'Drama', value: 'drama' },
        { label: 'Ecchi', value: 'ecchi' },
        { label: 'Fantasy', value: 'fantasy' },
        { label: 'Gender Bender', value: 'gender_bender' },
        { label: 'Harem', value: 'harem' },
        { label: 'Historical', value: 'historical' },
        { label: 'Horror', value: 'horror' },
        { label: 'Josei', value: 'josei' },
        { label: 'Martial Arts', value: 'martial_arts' },
        { label: 'Mature', value: 'mature' },
        { label: 'Mecha', value: 'mecha' },
        { label: 'Mystery', value: 'mystery' },
        { label: 'Psychological', value: 'psychological' },
        { label: 'Romance', value: 'romance' },
        { label: 'School Life', value: 'school_life' },
        { label: 'Sci-Fi', value: 'sci-fi' },
        { label: 'Seinen', value: 'seinen' },
        { label: 'Shoujo', value: 'shoujo' },
        { label: 'Shoujo Ai', value: 'shoujo_ai' },
        { label: 'Shounen', value: 'shounen' },
        { label: 'Shounen Ai', value: 'shounen_ai' },
        { label: 'Slice of Life', value: 'slice_of_life' },
        { label: 'Sports', value: 'sports' },
        { label: 'Supernatural', value: 'supernatural' },
        { label: 'Tragedy', value: 'tragedy' },
        { label: 'Yaoi', value: 'yaoi' },
        { label: 'Yuri', value: 'yuri' },
      ],
    },
  },

  async popularNovels(pageNo, options) {
    var limit = 60;
    var offset = (Math.max(1, Number(pageNo) || 1) - 1) * limit;
    var filters = options && options.filters;
    var sort = options && options.showLatestNovels ? 'updated' : 'popular';
    var params = ['sort=' + encode(sort), 'limit=' + limit, 'offset=' + offset];

    if (!(options && options.showLatestNovels) && filters && filters.sort) {
      sort = typeof filters.sort === 'object' ? filters.sort.value : filters.sort;
      params[0] = 'sort=' + encode(sort || 'popular');
    }

    var genres = filters && filters.genres;
    var genreValues = genres && typeof genres === 'object' && 'value' in genres ? genres.value : genres;
    if (Array.isArray(genreValues)) {
      genreValues.forEach(function (genre) {
        if (String(genre).startsWith('-')) params.push('genre_exclude=' + encode(String(genre).slice(1)));
        else params.push('genre=' + encode(genre));
      });
    } else if (genreValues && typeof genreValues === 'object') {
      (genreValues.include || []).forEach(function (genre) { params.push('genre=' + encode(genre)); });
      (genreValues.exclude || []).forEach(function (genre) { params.push('genre_exclude=' + encode(genre)); });
    }

    var json = await getJson(this.site + '/api/novels?' + params.join('&'));
    return (json.items || []).map(function (item) {
      return { name: item.title, path: item.slug, cover: item.cover_url || defaultCover };
    });
  },

  async searchNovels(searchTerm, pageNo) {
    var limit = 36;
    var offset = (Math.max(1, Number(pageNo) || 1) - 1) * limit;
    var json = await getJson(
      this.site + '/api/novels?sort=popular&q=' + encode(searchTerm) + '&limit=' + limit + '&offset=' + offset,
    );
    return (json.items || []).map(function (item) {
      return { name: item.title, path: item.slug, cover: item.cover_url || defaultCover };
    });
  },

  async parseNovel(novelPath) {
    var safePath = encode(novelPath);
    var details = await getJson(this.site + '/api/novels/' + safePath);
    var firstPage = await getJson(
      this.site + '/api/novels/' + safePath + '/chapters?order=asc&limit=' + CHAPTER_PAGE_SIZE + '&offset=0',
    );
    var chapters = (firstPage.items || []).map(function (chapter) {
      return chapterFromApi(novelPath, chapter);
    });
    var total = Number(firstPage.total);
    if (!Number.isFinite(total) || total < chapters.length) {
      total = Number(details.chapter_count);
    }
    if (!Number.isFinite(total) || total < chapters.length) total = chapters.length;

    return {
      path: novelPath,
      name: details.title || 'Untitled',
      cover: details.cover_url || defaultCover,
      author: details.authors && details.authors.length
        ? details.authors.map(function (author) { return author.name; }).join(', ')
        : 'Unknown',
      summary: details.description || '',
      genres: details.genres && details.genres.length
        ? details.genres.map(function (genre) { return genre.name; }).join(', ')
        : '',
      status: statusFromApi(details.status),
      chapters: chapters,
      totalPages: Math.max(1, Math.ceil(total / CHAPTER_PAGE_SIZE)),
    };
  },

  async parsePage(novelPath, page) {
    var pageNo = Math.max(1, Number(page) || 1);
    var offset = (pageNo - 1) * CHAPTER_PAGE_SIZE;
    var safePath = encode(novelPath);
    var json = await getJson(
      this.site + '/api/novels/' + safePath + '/chapters?order=asc&limit=' + CHAPTER_PAGE_SIZE + '&offset=' + offset,
    );
    return {
      chapters: (json.items || []).map(function (chapter) {
        return chapterFromApi(novelPath, chapter);
      }),
    };
  },

  async parseChapter(chapterPath) {
    var json = await getJson(this.site + '/' + String(chapterPath).replace(/^\/+/, ''));
    if (json.locked && !json.body) {
      return '<p>' + escapeHtml(json.lock_reason || 'This chapter is locked.') + '</p>';
    }
    return String(json.body || '')
      .split('\n')
      .map(function (paragraph) { return paragraph.trim(); })
      .filter(function (paragraph) { return paragraph.length > 0; })
      .map(function (paragraph) { return '<p>' + escapeHtml(paragraph) + '</p>'; })
      .join('');
  },

  resolveUrl(path, isNovel) {
    if (isNovel) return this.site + '/novels/' + path;
    return this.site + '/' + String(path).replace(/^\/+/, '');
  },
};

exports.default = Chikari;
