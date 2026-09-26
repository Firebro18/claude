// Wird in die geladene tutti.ch- bzw. Facebook-Seite (Worker-WebView) injiziert.
// Ruft am Ende WorkerBridge.result(token, json) auf. Die Platzhalter unten ersetzt die App vor dem Einfügen.
(function () {
  'use strict';
  var TOKEN = '__TOKEN__';
  var MODE = '__MODE__'; // tutti-search | tutti-detail | fb-search | fb-detail | page
  var SCROLLS = __SCROLLS__;

  function send(obj) {
    obj.url = location.href;
    obj.pageTitle = document.title;
    try { WorkerBridge.result(TOKEN, JSON.stringify(obj)); } catch (e) { /* Bridge fehlt */ }
  }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
  function txt(el) { return el ? (el.innerText || el.textContent || '').trim() : ''; }
  function lines(el) { return txt(el).split('\n').map(function (s) { return s.trim(); }).filter(Boolean); }
  function meta(name) {
    var m = document.querySelector('meta[property="' + name + '"],meta[name="' + name + '"]');
    return m ? m.getAttribute('content') || '' : '';
  }
  var PRICE_RE = /(chf|fr\.)\s*[\d'’.,]+|[\d'’]+(\.[-–—]|\.\d\d)\s*$|^[\d'’.,]+\s*(chf|fr\.?)$|^gratis$/i;

  function blocked() {
    var body = (document.body ? document.body.innerText : '').slice(0, 4000);
    if (document.querySelector('iframe[src*="captcha"], iframe[src*="datadome"], #cf-challenge-running, .g-recaptcha, iframe[src*="hcaptcha"], iframe[src*="challenges.cloudflare"]')) return 'captcha';
    if (/captcha|verify you are (a )?human|bist du ein mensch|sind sie ein mensch|zugriff verweigert|access denied|unusual traffic|ungewöhnliche aktivität/i.test(body) && body.length < 3000) return 'captcha';
    return null;
  }

  function jsonLd() {
    var out = [];
    document.querySelectorAll('script[type="application/ld+json"]').forEach(function (s) {
      try {
        var j = JSON.parse(s.textContent);
        (Array.isArray(j) ? j : (j['@graph'] || [j])).forEach(function (x) { out.push(x); });
      } catch (e) { }
    });
    return out;
  }

  // Kleinster Vorfahre, der genau einen Inserat-Link enthält = "Karte".
  function cardOf(a, linkSel) {
    var el = a, best = a;
    for (var i = 0; i < 8 && el.parentElement; i++) {
      var p = el.parentElement;
      var ids = {};
      p.querySelectorAll(linkSel).forEach(function (x) { ids[x.href.split('?')[0]] = 1; });
      if (Object.keys(ids).length > 1) break;
      best = p; el = p;
    }
    return best;
  }

  function tuttiSearch() {
    var items = [], seen = {};
    document.querySelectorAll('a[href*="/vi/"]').forEach(function (a) {
      var href = a.href.split('?')[0].split('#')[0];
      if (!/\/vi\/.+\/\d+$/.test(href) || seen[href]) return;
      seen[href] = 1;
      var card = cardOf(a, 'a[href*="/vi/"]');
      var ls = lines(card);
      var price = '', loc = '', title = '';
      ls.forEach(function (l) {
        if (!price && PRICE_RE.test(l)) price = l;
        else if (!loc && /\b[1-9]\d{3}\b/.test(l) && /[a-zà-ÿ]{3}/i.test(l) && l.length < 60 && !/\bg\b|gramm|silber|gold/i.test(l)) loc = l;
      });
      var h = card.querySelector('h2,h3,h4,[class*="title" i]');
      title = txt(h) || txt(a) || ls.slice().sort(function (x, y) { return y.length - x.length; })[0] || '';
      if (title.length > 200) title = title.slice(0, 200);
      items.push({ url: href, title: title, priceText: price, location: loc, snippet: ls.join(' | ').slice(0, 600) });
    });
    return items;
  }

  function tuttiDetail() {
    var ld = jsonLd().filter(function (x) { return /product|offer|thing/i.test(String(x['@type'] || '')); })[0] || {};
    var offers = ld.offers ? (Array.isArray(ld.offers) ? ld.offers[0] : ld.offers) : {};
    var title = ld.name || txt(document.querySelector('h1')) || meta('og:title');
    var main = document.querySelector('main') || document.body;
    var body = txt(main);
    var desc = ld.description || '';
    if (!desc) {
      // grösster Textblock der Hauptseite als Beschreibung
      var bestLen = 0;
      main.querySelectorAll('div,p,section').forEach(function (d) {
        var t = txt(d);
        if (t.length > bestLen && t.length < 6000 && d.querySelectorAll('a').length < 5) { bestLen = t.length; desc = t; }
      });
    }
    var lower = body.toLowerCase();
    var gone = /nicht mehr verfügbar|nicht mehr aktiv|inserat wurde (entfernt|gelöscht|deaktiviert)|inserat ist abgelaufen|dieses inserat existiert nicht|seite (wurde )?nicht gefunden|page not found|n'est plus disponible|annonce (a été )?supprimée/.test(lower);
    var avail = String(offers.availability || '');
    if (/outofstock|soldout|discontinued/i.test(avail)) gone = true;
    var idMatch = location.href.match(/\/(\d{6,})(?:[/?#]|$)/);
    var priceText = offers.price != null ? ('CHF ' + offers.price) : '';
    if (!priceText) {
      var pl = lines(main).filter(function (l) { return PRICE_RE.test(l); })[0];
      priceText = pl || '';
    }
    var locLine = lines(main).filter(function (l) { return /\b[1-9]\d{3}\b\s+[A-Za-zÀ-ÿ]/.test(l) && l.length < 60; })[0] || '';
    return {
      title: title, description: String(desc).slice(0, 5000), priceText: priceText, location: locLine,
      active: !gone && !!title && !!idMatch, reserved: /reserviert/.test(lower.slice(0, 3000)),
      availability: avail, datePosted: ld.datePosted || ''
    };
  }

  function fbSearch() {
    var items = [], seen = {};
    document.querySelectorAll('a[href*="/marketplace/item/"]').forEach(function (a) {
      var m = a.href.match(/\/marketplace\/item\/(\d+)/);
      if (!m || seen[m[1]]) return;
      seen[m[1]] = 1;
      var ls = lines(a);
      var price = ls.filter(function (l) { return PRICE_RE.test(l) || /^(chf|fr\.?)\s*\d|^\d[\d'’.,]*\s*(chf|fr)/i.test(l); })[0] || '';
      var rest = ls.filter(function (l) { return l !== price && !/^(chf|fr\.?)\s*\d/i.test(l); });
      items.push({
        url: 'https://www.facebook.com/marketplace/item/' + m[1] + '/',
        title: rest[0] || '', location: rest[1] || '', priceText: price, snippet: ls.join(' | ').slice(0, 400)
      });
    });
    return items;
  }

  function fbDetail() {
    var main = document.querySelector('[role="main"]') || document.body;
    var all = txt(main);
    // Nur den Teil bis zu Verkäuferinfo/ähnlichen Angeboten nehmen, damit fremde Inserate den Score nicht verfälschen.
    var cut = all.search(/verkäufer(innen)?informationen|seller information|heutige angebote|today's picks|ähnliche artikel|related listings|mehr von diesem verkäufer/i);
    var body = cut > 0 ? all.slice(0, cut) : all.slice(0, 5000);
    var title = txt(main.querySelector('h1')) || meta('og:title') || document.title.replace(/\s*\|\s*facebook.*$/i, '');
    var lower = all.toLowerCase();
    var gone = /dieses angebot ist nicht mehr verfügbar|nicht mehr verfügbar|this listing is no longer available|listing (is )?unavailable|inhalt ist derzeit nicht verfügbar|this content isn't available/.test(lower);
    var sold = /\bverkauft\b|\bsold\b/.test(body.slice(0, 400).toLowerCase());
    var pending = /\bausstehend\b|\bpending\b|\breserviert\b/.test(body.slice(0, 400).toLowerCase());
    var price = lines(main).filter(function (l) { return /^(chf|fr\.?)\s*\d|^\d[\d'’.,]*\s*(chf|fr\.?)$/i.test(l) || /^gratis$|^kostenlos$|^free$/i.test(l); })[0] || '';
    var needsLogin = !!document.querySelector('form[action*="login"], input[name="email"][type="text"], input[name="pass"]') && !main.querySelector('h1');
    return { title: title, description: body.slice(0, 5000), priceText: price, active: !gone && !sold && !!title && !needsLogin, sold: sold, reserved: pending, login: needsLogin };
  }

  async function run() {
    try {
      await sleep(300);
      var b = blocked();
      if (b) return send({ blocked: b });
      if (MODE === 'tutti-search' || MODE === 'fb-search' || MODE === 'page') {
        var isFb = /facebook\.com/.test(location.host);
        if (isFb && document.querySelector('input[name="pass"]') && !document.querySelector('a[href*="/marketplace/item/"]')) return send({ login: true });
        var collected = {}, order = [];
        for (var i = 0; i <= SCROLLS; i++) {
          var found = isFb ? fbSearch() : tuttiSearch();
          found.forEach(function (it) { if (!collected[it.url]) { collected[it.url] = it; order.push(it.url); } });
          if (i < SCROLLS) { window.scrollTo(0, document.body.scrollHeight); await sleep(isFb ? 1800 : 900); }
        }
        return send({ items: order.map(function (u) { return collected[u]; }) });
      }
      if (MODE === 'tutti-detail') return send({ detail: tuttiDetail() });
      if (MODE === 'fb-detail') return send({ detail: fbDetail() });
      send({ error: 'unknown mode' });
    } catch (e) {
      send({ error: String(e && e.message || e) });
    }
  }
  run();
})();
