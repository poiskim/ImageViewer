/*
 * 현재 열린 페이지에서 이미지 주소를 모아 앱(ImageViewerBridge)으로 보냅니다.
 *
 * 찾는 곳
 *  - <img> : srcset 중 가장 큰 것, 지연 로딩 속성(data-src 등), 원본 링크(<a href="....jpg">)
 *  - <picture><source srcset>
 *  - CSS 배경 이미지(background-image)
 *  - 이미지 파일로 바로 연결된 링크
 *  - 같은 도메인의 iframe 안쪽
 *
 * opts.deep      : true 면 페이지를 끝까지 스크롤하며 지연 로딩 이미지를 깨운 뒤 수집
 * opts.fromStart : true 면 첫 이미지부터, 아니면 지금 화면에 보이는 이미지부터 시작
 * opts.small     : true 면 작은 이미지(아이콘 등)도 포함
 * opts.startUrl  : 길게 누른 이미지 주소 — 그 이미지부터 시작
 */
(function () {
  'use strict';

  var LAZY_ATTRS = ['data-full-src', 'data-full', 'data-orig-file', 'data-large-file', 'data-zoom-image',
    'data-hi-res-src', 'data-original', 'data-src', 'data-lazy-src', 'data-lazy', 'data-url', 'data-echo',
    'data-original-src', 'data-img', 'data-image'];
  var LAZY_SRCSET = ['data-srcset', 'data-lazy-srcset'];
  var IMG_EXT = /\.(jpe?g|png|gif|webp|avif|bmp|jfif|heic)(\?|#|$)/i;
  var JUNK = /(spacer|blank|pixel|1x1|transparent|loading|loader|placeholder|lazyload)\.(gif|png|svg|jpe?g|webp)(\?|#|$)/i;
  var SKIP_BG = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, NOSCRIPT: 1, BR: 1, SVG: 1, PATH: 1, OPTION: 1, SOURCE: 1, TEMPLATE: 1 };

  function abs(u, base) {
    if (!u) return null;
    u = String(u).trim();
    if (!u || /^javascript:/i.test(u)) return null;
    try { return new URL(u, base || document.baseURI).href; } catch (e) { return null; }
  }

  function usable(u) {
    if (!u) return false;
    if (u.indexOf('data:') === 0) return u.length > 3000 && u.indexOf('data:image/svg') !== 0;
    if (!/^https?:/i.test(u)) return false;
    return !JUNK.test(u);
  }

  function bestSrcset(ss, base) {
    if (!ss) return null;
    var best = null, bestV = -1;
    String(ss).split(/,\s+/).forEach(function (part) {
      var p = part.trim().split(/\s+/);
      if (!p[0]) return;
      var url = p[0].replace(/,$/, '');
      var v = 1;
      var m = p[1] && /^([\d.]+)[wxh]$/i.exec(p[1]);
      if (m) v = parseFloat(m[1]);
      if (v > bestV) { bestV = v; best = url; }
    });
    return abs(best, base);
  }

  function linkedImage(el, base) {
    var a = el.closest ? el.closest('a[href]') : null;
    if (!a) return null;
    var h = abs(a.getAttribute('href'), base);
    return h && IMG_EXT.test(h) && usable(h) ? h : null;
  }

  // blob: 주소는 다른 화면에서 열 수 없어서, 이미 그려진 이미지를 data: 로 바꿔 넘깁니다.
  function blobToData(img) {
    try {
      if (!img.complete || !img.naturalWidth) return null;
      var c = document.createElement('canvas');
      c.width = img.naturalWidth; c.height = img.naturalHeight;
      c.getContext('2d').drawImage(img, 0, 0);
      return c.toDataURL('image/jpeg', 0.92);
    } catch (e) { return null; }
  }

  function tooSmall(img, chosen, min) {
    var loaded = img.currentSrc || img.src;
    if (chosen === loaded && img.complete && img.naturalWidth && img.naturalHeight) {
      return img.naturalWidth < min || img.naturalHeight < min;
    }
    var aw = parseInt(img.getAttribute('width'), 10), ah = parseInt(img.getAttribute('height'), 10);
    if (aw && ah) return aw < min || ah < min;
    var r = img.getBoundingClientRect();
    if (r.width && r.height) return r.width < Math.min(min, 48) || r.height < Math.min(min, 48);
    return false;
  }

  function fromImg(img, base, min) {
    var link = linkedImage(img, base);
    if (link) return link; // 썸네일이 원본 이미지로 링크돼 있으면 원본 사용

    var c = [];
    var pic = img.parentElement && img.parentElement.tagName === 'PICTURE' ? img.parentElement : null;
    if (pic) {
      var sources = pic.querySelectorAll('source');
      for (var s = 0; s < sources.length; s++) {
        c.push(bestSrcset(sources[s].getAttribute('srcset') || sources[s].getAttribute('data-srcset'), base));
      }
    }
    LAZY_SRCSET.forEach(function (a) { c.push(bestSrcset(img.getAttribute(a), base)); });
    c.push(bestSrcset(img.getAttribute('srcset'), base));
    LAZY_ATTRS.forEach(function (a) { c.push(abs(img.getAttribute(a), base)); });
    c.push(img.currentSrc || abs(img.getAttribute('src'), base));

    for (var i = 0; i < c.length; i++) {
      var u = c[i];
      if (!u) continue;
      if (u.indexOf('blob:') === 0) {
        if (img.naturalWidth >= min && img.naturalHeight >= min) {
          var d = blobToData(img);
          if (d) return d;
        }
        continue;
      }
      if (usable(u)) return tooSmall(img, u, min) ? null : u;
    }
    return null;
  }

  function fromBg(el, base, min) {
    var r = el.getBoundingClientRect();
    if (r.width < min || r.height < min) return null;
    var bg = getComputedStyle(el).backgroundImage;
    if (!bg || bg === 'none' || bg.indexOf('url(') < 0) return null;
    var m = /url\((['"]?)(.*?)\1\)/.exec(bg);
    var u = m ? abs(m[2], base) : null;
    if (!usable(u)) return null;
    return linkedImage(el, base) || u;
  }

  function collect(doc, out, opts, depth) {
    if (!doc || !doc.body) return;
    var base = doc.baseURI, min = opts.small ? 24 : 120;
    var all = doc.body.getElementsByTagName('*');
    var bgBudget = 6000;
    for (var i = 0; i < all.length; i++) {
      var el = all[i], tag = el.tagName.toUpperCase(), url = null;
      if (tag === 'IMG') {
        url = fromImg(el, base, min);
      } else if (tag === 'A') {
        if (!el.querySelector('img')) {
          var h = abs(el.getAttribute('href'), base);
          if (h && IMG_EXT.test(h) && usable(h)) url = h;
        }
      } else if (tag === 'IFRAME' || tag === 'FRAME') {
        try { if (depth < 3) collect(el.contentDocument, out, opts, depth + 1); } catch (e) { /* 다른 도메인 */ }
        continue;
      } else if (!SKIP_BG[tag] && bgBudget-- > 0) {
        url = fromBg(el, base, min);
      }
      if (url) out.push({ el: el, url: url });
    }
  }

  function snapshot(opts) {
    var out = [];
    collect(document, out, opts, 0);
    return out;
  }

  // 순서를 유지하면서 새로 발견된 이미지를 끼워 넣기 (스크롤 중 사라지는 목록 대비)
  function mergeInto(merged, snap) {
    var pos = {};
    for (var k = 0; k < merged.length; k++) pos[merged[k].url] = k;
    var last = -1;
    for (var i = 0; i < snap.length; i++) {
      var it = snap[i];
      if (pos.hasOwnProperty(it.url)) {
        last = pos[it.url];
        merged[last].el = it.el;
        continue;
      }
      merged.splice(last + 1, 0, it);
      last++;
      for (k = last; k < merged.length; k++) pos[merged[k].url] = k;
    }
  }

  function dedupe(list) {
    var seen = {}, out = [];
    list.forEach(function (it) { if (!seen[it.url]) { seen[it.url] = 1; out.push(it); } });
    return out;
  }

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function findScroller() {
    var se = document.scrollingElement || document.documentElement;
    if (se.scrollHeight > innerHeight + 50) return se;
    var best = null, bestH = 0, els = document.body.getElementsByTagName('*');
    for (var i = 0; i < els.length && i < 20000; i++) {
      var e = els[i];
      if (e.scrollHeight > e.clientHeight + 50 && e.clientHeight > innerHeight * 0.5) {
        var oy = getComputedStyle(e).overflowY;
        if ((oy === 'auto' || oy === 'scroll') && e.scrollHeight > bestH) { best = e; bestH = e.scrollHeight; }
      }
    }
    return best || se;
  }

  async function deepCollect(opts) {
    var merged = dedupe(snapshot(opts));
    var sc = findScroller(), start = sc.scrollTop, stuck = 0;
    for (var i = 0; i < 160; i++) {
      var before = sc.scrollTop;
      sc.scrollTop = before + Math.max(200, sc.clientHeight * 0.8);
      await sleep(220);
      if (i % 4 === 3) mergeInto(merged, dedupe(snapshot(opts)));
      if (sc.scrollTop <= before + 1) {
        if (++stuck >= 4) break;
        await sleep(500); // 무한 스크롤이 다음 내용을 불러올 시간
      } else {
        stuck = 0;
      }
    }
    await sleep(300);
    mergeInto(merged, dedupe(snapshot(opts)));
    sc.scrollTop = start;
    return merged;
  }

  function startIndex(items, opts) {
    if (opts.startUrl) {
      for (var i = 0; i < items.length; i++) {
        var el = items[i].el;
        if (items[i].url === opts.startUrl) return i;
        if (el.tagName === 'IMG' && (el.currentSrc === opts.startUrl || el.src === opts.startUrl)) return i;
      }
    }
    if (opts.fromStart) return 0;
    var line = innerHeight * 0.2;
    for (var j = 0; j < items.length; j++) {
      var e = items[j].el;
      if (e.ownerDocument !== document || !e.isConnected) continue;
      var r = e.getBoundingClientRect();
      if (r.height > 0 && r.bottom > line) return j;
    }
    return 0;
  }

  function send(obj) {
    var json = JSON.stringify(obj);
    if (window.ImageViewerBridge) window.ImageViewerBridge.onImages(json);
    else console.log('[ImageViewer]', json);
  }

  window.__ivScrollTo = function (i) {
    var el = window.__ivEls && window.__ivEls[i];
    if (el && el.isConnected) el.scrollIntoView({ block: 'center' });
  };

  window.__ivExtract = async function (opts) {
    opts = opts || {};
    try {
      var items = opts.deep ? await deepCollect(opts) : dedupe(snapshot(opts));
      window.__ivEls = items.map(function (it) { return it.el; });
      send({
        images: items.map(function (it) { return it.url; }),
        start: startIndex(items, opts),
        title: document.title || location.hostname,
        page: location.href
      });
    } catch (e) {
      send({ error: String(e && e.message || e) });
    }
  };
})();
