/* global Android, Scoring */
// Ablauf: 1) Spotpreise  2) Suche tutti/Facebook  3) Gold-Agent (Score 0-10, nur 8-10)
//         4) Aktiv-Agent (öffnet jedes Inserat: läuft es noch?)  5) Liste nach Marge sortiert.
var App = (function () {
  'use strict';

  var DEFAULTS = {
    tuttiOn: true,
    fbOn: true,
    homePlz: 8200,
    radiusKm: 80,
    target: 100,
    maxDetail: 450,
    pages: 2,
    settleMs: 2500,
    fbScrolls: 4,
    apiKey: '',
    tuttiTemplate: 'https://www.tutti.ch/de/q/suche?sorting=newest&page={p}&query={q}',
    fbTemplate: 'https://www.facebook.com/marketplace/schaffhausen/search/?query={q}&radius=80&exact=false',
    tuttiQueries: [
      'silber 925', 'silber 800', '800er silber', 'sterling silber', 'silberbesteck 800', 'tafelsilber', 'jezler', 'silberschale',
      'silberbecher', 'silbermünzen', '5 franken silber', 'fünfliber', 'silberbarren', 'feinsilber', 'silbermedaille', 'schützentaler',
      'konvolut silber', 'silber punze', 'zigarettenetui silber', 'silberleuchter', 'silberschmuck 925 konvolut',
      'gold 750', 'gold 585', '18k gold', '14k gold', 'goldring 750', 'goldkette 750', 'altgold', 'bruchgold', 'zahngold',
      'goldvreneli', 'vreneli', 'goldmünze', 'goldbarren', 'krügerrand', 'taschenuhr gold 14k', 'goldmedaille', 'konvolut gold', 'nachlass schmuck gold'
    ].join('\n'),
    fbQueries: [
      'silber 925', 'silber 800', 'silberbesteck', 'tafelsilber', 'silbermünzen', 'fünfliber', 'silberbarren',
      'gold 750', 'gold 585', 'goldring', 'goldkette', 'altgold', 'vreneli', 'goldmünze', 'taschenuhr gold', 'konvolut schmuck'
    ].join('\n')
  };

  var S = {};           // Einstellungen
  var items = {};       // url -> Inserat
  var running = false, stopFlag = false;
  var spot = null;
  var pendingWorker = {}, pendingHttp = {}, seq = 0;
  var resumeResolver = null;

  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function fmt(n) { return n == null ? '–' : Math.round(n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, "'"); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function log(msg, cls) {
    var el = document.createElement('div');
    el.className = 'log-line ' + (cls || '');
    el.textContent = new Date().toTimeString().slice(0, 8) + '  ' + msg;
    var box = $('log');
    box.insertBefore(el, box.firstChild);
    while (box.childNodes.length > 400) box.removeChild(box.lastChild);
  }
  function status(msg) { $('status').textContent = msg; }

  // ---------- Persistenz ----------
  function loadSettings() {
    var raw = null;
    try { raw = Android.load('settings'); } catch (e) { }
    var saved = {};
    try { saved = raw ? JSON.parse(raw) : {}; } catch (e) { }
    S = Object.assign({}, DEFAULTS, saved);
    Object.keys(DEFAULTS).forEach(function (k) {
      var el = $('s_' + k);
      if (!el) return;
      if (el.type === 'checkbox') el.checked = !!S[k]; else el.value = S[k];
    });
  }
  function readSettings() {
    Object.keys(DEFAULTS).forEach(function (k) {
      var el = $('s_' + k);
      if (!el) return;
      if (el.type === 'checkbox') S[k] = el.checked;
      else if (typeof DEFAULTS[k] === 'number') S[k] = parseFloat(el.value) || DEFAULTS[k];
      else S[k] = el.value;
    });
    try { Android.save('settings', JSON.stringify(S)); } catch (e) { }
  }
  function saveItems() {
    var keep = {};
    Object.keys(items).forEach(function (u) { if (items[u].checked) keep[u] = items[u]; });
    try { Android.save('items', JSON.stringify(keep)); } catch (e) { }
  }
  function loadItems() {
    try { items = JSON.parse(Android.load('items') || '{}') || {}; } catch (e) { items = {}; }
  }

  // ---------- Brücken zur Android-Hülle ----------
  function worker(url, mode, scrolls) {
    return new Promise(function (resolve) {
      var token = 'w' + (++seq) + '_' + Math.random().toString(36).slice(2);
      pendingWorker[token] = resolve;
      Android.loadInWorker(token, url, mode, scrolls || 0, S.settleMs);
    });
  }
  function onWorker(token, json) {
    var cb = pendingWorker[token];
    if (!cb) return;
    delete pendingWorker[token];
    var r;
    try { r = JSON.parse(json); } catch (e) { r = { error: 'bad json' }; }
    cb(r);
  }
  function http(method, url, headers, body) {
    return new Promise(function (resolve) {
      var token = 'h' + (++seq);
      pendingHttp[token] = resolve;
      Android.http(token, method, url, JSON.stringify(headers || {}), body || '');
    });
  }
  function onHttp(token, code, body) {
    var cb = pendingHttp[token];
    if (!cb) return;
    delete pendingHttp[token];
    cb({ status: code, body: body });
  }

  // Captcha/Login: Browserfenster zeigen und warten, bis der Nutzer "Weiter" tippt.
  function waitForUser(msg) {
    status(msg);
    log(msg, 'warn');
    Android.showWorker(true);
    $('resumeBtn').style.display = 'inline-block';
    try { Android.notify('Edelmetall-Scanner', msg); } catch (e) { }
    return new Promise(function (r) { resumeResolver = r; });
  }
  function resume() {
    $('resumeBtn').style.display = 'none';
    Android.showWorker(false);
    if (resumeResolver) { var r = resumeResolver; resumeResolver = null; r(); }
  }

  async function workerWithRetry(url, mode, scrolls) {
    for (var attempt = 0; attempt < 3; attempt++) {
      if (stopFlag) return { error: 'stopped' };
      var r = await worker(url, mode, scrolls);
      if (r.blocked) { await waitForUser('Captcha/Sperre erkannt – bitte im Browserfenster lösen und dann „Weiter" tippen.'); continue; }
      return r;
    }
    return { error: 'blocked' };
  }

  // ---------- Spotpreise ----------
  async function fetchSpot() {
    var g = await http('GET', 'https://api.gold-api.com/price/XAU/CHF');
    var s = await http('GET', 'https://api.gold-api.com/price/XAG/CHF');
    try {
      var gp = JSON.parse(g.body).price, sp = JSON.parse(s.body).price;
      spot = { goldChfPerG: gp / 31.1035, silverChfPerG: sp / 31.1035, at: new Date().toISOString() };
    } catch (e) {
      spot = spot || { goldChfPerG: 114, silverChfPerG: 1.7, at: 'Fallback' };
      log('Spotpreis nicht abrufbar, verwende Fallback.', 'warn');
    }
    $('spot').textContent = 'Gold CHF ' + spot.goldChfPerG.toFixed(2) + '/g · Silber CHF ' + spot.silverChfPerG.toFixed(3) + '/g';
    try { Android.save('spot', JSON.stringify(spot)); } catch (e) { }
  }

  // ---------- Suche ----------
  function buildUrl(tpl, q, p) {
    return tpl.replace('{q}', encodeURIComponent(q)).replace('{p}', String(p));
  }

  function addFound(list, source, q) {
    var added = 0;
    list.forEach(function (it) {
      if (!it.url || items[it.url]) return;
      var price = Scoring.parsePrice(it.priceText);
      var plz = Scoring.plzFromText(it.location);
      var dist = plz ? Scoring.distanceKm(S.homePlz, plz) : null;
      var pre = Scoring.scoreItem({ title: it.title, text: it.snippet, price: price });
      items[it.url] = {
        url: it.url, source: source, query: q, title: it.title, priceText: it.priceText, price: price,
        location: it.location, plz: plz, dist: dist, snippet: it.snippet, pre: pre.score, preExcluded: pre.excluded,
        checked: false
      };
      added++;
    });
    return added;
  }

  async function searchPhase() {
    var jobs = [];
    var lines = function (s) { return String(s).split('\n').map(function (x) { return x.trim(); }).filter(Boolean); };
    if (S.tuttiOn) lines(S.tuttiQueries).forEach(function (q) { jobs.push({ src: 'tutti', q: q }); });
    if (S.fbOn) lines(S.fbQueries).forEach(function (q) { jobs.push({ src: 'facebook', q: q }); });
    var fbDisabled = false;
    for (var j = 0; j < jobs.length && !stopFlag; j++) {
      var job = jobs[j];
      if (job.src === 'facebook' && fbDisabled) continue;
      var pages = job.src === 'tutti' ? S.pages : 1;
      for (var p = 1; p <= pages && !stopFlag; p++) {
        var url = buildUrl(job.src === 'tutti' ? S.tuttiTemplate : S.fbTemplate, job.q, p);
        status('Suche ' + (j + 1) + '/' + jobs.length + ': ' + job.src + ' „' + job.q + '" Seite ' + p);
        var r = await workerWithRetry(url, job.src === 'tutti' ? 'tutti-search' : 'fb-search', job.src === 'tutti' ? 1 : S.fbScrolls);
        if (r.login) {
          await waitForUser('Facebook verlangt ein Login. Bitte im Browserfenster einloggen, dann „Weiter".');
          r = await workerWithRetry(url, 'fb-search', S.fbScrolls);
          if (r.login) { log('Facebook ohne Login – übersprungen.', 'warn'); fbDisabled = true; break; }
        }
        if (r.error) { log(job.src + ' „' + job.q + '": ' + r.error, 'warn'); break; }
        var n = addFound(r.items || [], job.src, job.q);
        log(job.src + ' „' + job.q + '" S.' + p + ': ' + (r.items || []).length + ' Treffer, ' + n + ' neu');
        if (n === 0) break;
      }
    }
  }

  // ---------- Gold-Agent mit Claude (optional) ----------
  var CLAUDE_SCHEMA = {
    type: 'object',
    properties: {
      score: { type: 'integer', description: '0-10: Wahrscheinlichkeit, dass der Artikel echtes, extrahierbares Gold/Silber enthält' },
      metal: { type: 'string', enum: ['gold', 'silver', 'none'] },
      fine_grams: { type: 'number', description: 'Geschätztes Feingewicht Edelmetall in Gramm (0 wenn unbekannt)' },
      reason: { type: 'string' }
    },
    required: ['score', 'metal', 'fine_grams', 'reason'],
    additionalProperties: false
  };

  async function claudeAgent(it, rule) {
    if (!S.apiKey) return null;
    var prompt = 'Inserat (' + it.source + '):\nTitel: ' + it.title + '\nPreis: ' + (it.priceText || 'unbekannt') +
      '\nBeschreibung: ' + String(it.description || it.snippet || '').slice(0, 3500) +
      '\n\nRegelbasierte Vorabschätzung: Score ' + rule.score + ', Metall ' + rule.metal + ', Feingewicht ' + (rule.fineGrams ? rule.fineGrams.toFixed(1) + ' g' : 'unbekannt') +
      '\nAktueller Spotpreis: Gold CHF ' + spot.goldChfPerG.toFixed(2) + '/g, Silber CHF ' + spot.silverChfPerG.toFixed(3) + '/g.';
    var body = {
      model: 'claude-opus-5',
      max_tokens: 2000,
      fallbacks: 'default',
      output_config: { effort: 'low', format: { type: 'json_schema', schema: CLAUDE_SCHEMA } },
      system: 'Du bist ein Edelmetall-Gutachter. Bewerte, ob ein Kleinanzeigen-Artikel mit hoher Wahrscheinlichkeit echtes Gold oder Silber enthält, das man einschmelzen/verkaufen kann. ' +
        'Hohe Scores (8-10) nur bei klaren Belegen: Feingehaltsangabe/Punze (999, 925, 900, 835, 800, 750, 585, 375), bekannte Anlagemünzen oder Barren, Schweizer Silbermünzen bis 1967, renommierte Silberschmiede. ' +
        'Niedrig bewerten: versilbert, vergoldet (ausser vergoldetes Silber), Doublé, Alpaka, Modeschmuck, "Silber"/"Gold" als Farbangabe, Ankaufs- oder Suchinserate. ' +
        'fine_grams = Bruttogewicht × Feingehalt, abzüglich Nicht-Metallteile (Uhrwerk, Messerklingen, Steine, Füllungen). Wenn kein Gewicht angegeben ist, vorsichtig schätzen oder 0.',
      messages: [{ role: 'user', content: prompt }]
    };
    var res = await http('POST', 'https://api.anthropic.com/v1/messages', {
      'x-api-key': S.apiKey, 'anthropic-version': '2023-06-01', 'anthropic-beta': 'server-side-fallback-2026-07-01', 'content-type': 'application/json'
    }, JSON.stringify(body));
    if (res.status !== 200) { log('Claude-Agent Fehler ' + res.status + ': ' + String(res.body).slice(0, 160), 'warn'); return null; }
    try {
      var msg = JSON.parse(res.body);
      if (msg.stop_reason === 'refusal') return null;
      var textBlock = (msg.content || []).filter(function (b) { return b.type === 'text'; }).pop();
      return textBlock ? JSON.parse(textBlock.text) : null;
    } catch (e) { return null; }
  }

  // ---------- Detailprüfung: Gold-Agent + Aktiv-Agent ----------
  async function checkItem(it) {
    var d = await workerWithRetry(it.url, it.source === 'tutti' ? 'tutti-detail' : 'fb-detail', 0);
    it.checked = true;
    it.checkedAt = new Date().toISOString();
    if (d.error || !d.detail) { it.active = false; it.activeNote = 'Seite nicht lesbar (' + (d.error || '?') + ')'; return; }
    var det = d.detail;
    if (det.login) { it.active = null; it.activeNote = 'Login nötig'; return; }
    it.title = det.title || it.title;
    it.description = det.description || '';
    if (det.priceText) { var p = Scoring.parsePrice(det.priceText); if (p != null) { it.price = p; it.priceText = det.priceText; } }
    if (det.location) { it.location = det.location; it.plz = Scoring.plzFromText(det.location) || it.plz; it.dist = it.plz ? Scoring.distanceKm(S.homePlz, it.plz) : it.dist; }
    // Agent 2: läuft das Inserat noch?
    it.active = !!det.active;
    it.activeNote = det.active ? (det.reserved ? 'aktiv, aber reserviert' : 'aktiv') : (det.sold ? 'verkauft' : 'abgelaufen/entfernt');
    it.reserved = !!det.reserved;

    // Agent 1: Gold/Silber-Wahrscheinlichkeit
    var rule = Scoring.scoreItem({ title: it.title, text: it.description, price: it.price });
    it.score = rule.score; it.metal = rule.metal; it.fineGrams = rule.fineGrams; it.reasons = rule.reasons; it.weightSource = rule.weightSource;
    it.agent = 'Regeln';
    if (it.active && rule.score >= 5 && !rule.excluded && S.apiKey) {
      var c = await claudeAgent(it, rule);
      if (c) {
        it.score = c.score; it.agent = 'Claude';
        if (c.metal !== 'none') it.metal = c.metal;
        if (c.fine_grams > 0) { it.fineGrams = c.fine_grams; it.weightSource = it.weightSource || 'Claude-Schätzung'; }
        it.reasons = [c.reason].concat(rule.reasons);
      }
    }
    it.value = Scoring.metalValue(it.fineGrams, it.metal, spot);
    it.margin = (it.value != null && it.price) ? (it.value - it.price) / it.price : null;
  }

  function passing() {
    return Object.keys(items).map(function (u) { return items[u]; }).filter(function (it) {
      return it.checked && it.active && it.score >= 8 && (it.dist == null || it.dist <= S.radiusKm);
    }).sort(function (a, b) {
      var am = a.margin == null ? -99 : a.margin, bm = b.margin == null ? -99 : b.margin;
      return bm - am || b.score - a.score;
    });
  }

  async function detailPhase() {
    var cands = Object.keys(items).map(function (u) { return items[u]; }).filter(function (it) {
      return !it.checked && !it.preExcluded && it.pre >= 3 && (it.dist == null || it.dist <= S.radiusKm);
    }).sort(function (a, b) { return b.pre - a.pre || (a.dist || 999) - (b.dist || 999); });
    log(cands.length + ' Kandidaten für die Detailprüfung.');
    var n = 0;
    for (var i = 0; i < cands.length && n < S.maxDetail && !stopFlag; i++) {
      if (passing().length >= S.target) { log('Ziel von ' + S.target + ' Links erreicht.', 'ok'); break; }
      var it = cands[i];
      n++;
      status('Prüfe ' + n + '/' + Math.min(cands.length, S.maxDetail) + ' · bestanden: ' + passing().length + ' · ' + it.title.slice(0, 40));
      await checkItem(it);
      log((it.active ? '✓' : '✗') + ' ' + (it.score != null ? it.score : '-') + '/10 ' + it.title.slice(0, 60) + ' – ' + it.activeNote, it.active && it.score >= 8 ? 'ok' : '');
      if (n % 5 === 0) { saveItems(); render(); }
    }
    saveItems();
  }

  // Bereits gefundene Links erneut prüfen (läuft die Anzeige noch?)
  async function recheck() {
    if (running) return;
    readSettings();
    running = true; stopFlag = false; setButtons();
    if (!spot) await fetchSpot();
    var list = passing();
    for (var i = 0; i < list.length && !stopFlag; i++) {
      status('Erneute Prüfung ' + (i + 1) + '/' + list.length);
      await checkItem(list[i]);
    }
    saveItems(); render();
    running = false; setButtons(); status('Erneute Prüfung fertig: ' + passing().length + ' aktiv mit Score ≥ 8.');
  }

  async function start() {
    if (running) return;
    readSettings();
    running = true; stopFlag = false; setButtons();
    try { Android.keepAwake(true); } catch (e) { }
    log('Start. Spotpreise werden geladen …');
    await fetchSpot();
    await searchPhase();
    await detailPhase();
    render();
    var n = passing().length;
    status((stopFlag ? 'Gestoppt. ' : 'Fertig. ') + n + ' aktive Inserate mit Score 8–10.');
    try { Android.notify('Edelmetall-Scanner', n + ' Treffer gefunden'); } catch (e) { }
    try { Android.keepAwake(false); } catch (e) { }
    running = false; setButtons();
    showTab('results');
  }

  function stop() { stopFlag = true; if (resumeResolver) resume(); status('Wird gestoppt …'); }

  function reset() {
    if (running) return;
    if (!confirm('Alle gespeicherten Treffer löschen?')) return;
    items = {}; saveItems(); render();
  }

  // Vom Nutzer im Browserfenster geöffnete Seite übernehmen (z. B. tutti mit eigenem Ortsfilter).
  async function takeCurrentPage() {
    if (running) return;
    readSettings();
    var r = await worker('', 'page', 3);
    if (r.error) { log('Seite übernehmen: ' + r.error, 'warn'); return; }
    var src = /facebook/.test(r.url || '') ? 'facebook' : 'tutti';
    var n = addFound(r.items || [], src, 'manuell');
    log('Seite übernommen: ' + (r.items || []).length + ' Treffer, ' + n + ' neu. Starte Prüfung …', 'ok');
    running = true; stopFlag = false; setButtons();
    if (!spot) await fetchSpot();
    await detailPhase();
    running = false; setButtons(); render();
  }

  // ---------- Darstellung ----------
  function render() {
    var list = passing();
    $('count').textContent = list.length;
    var html = list.map(function (it, i) {
      var m = it.margin == null ? '<span class="muted">Marge ?</span>' :
        '<span class="' + (it.margin >= 0 ? 'pos' : 'neg') + '">' + (it.margin >= 0 ? '+' : '') + Math.round(it.margin * 100) + '%</span>';
      return '<div class="card">' +
        '<div class="row"><span class="badge s' + it.score + '">' + it.score + '/10</span>' +
        '<span class="metal ' + (it.metal || '') + '">' + (it.metal === 'gold' ? 'Gold' : 'Silber') + '</span>' +
        '<span class="src">' + esc(it.source) + (it.dist != null ? ' · ' + it.dist + ' km' : '') + '</span>' + m + '</div>' +
        '<div class="title">' + (i + 1) + '. ' + esc(it.title) + '</div>' +
        '<div class="nums">Preis CHF ' + fmt(it.price) + ' · Metallwert ≈ CHF ' + fmt(it.value) +
        (it.fineGrams ? ' (' + it.fineGrams.toFixed(1) + ' g fein, ' + esc(it.weightSource || '') + ')' : '') + '</div>' +
        '<div class="why">' + esc((it.reasons || []).join(' · ')) + '</div>' +
        '<div class="meta">' + esc(it.location || '') + ' · ' + esc(it.activeNote || '') + ' · Agent: ' + esc(it.agent || '') + '</div>' +
        '<button class="link" data-url="' + esc(it.url) + '">Inserat öffnen</button>' +
        '</div>';
    }).join('');
    $('results').innerHTML = html || '<p class="muted">Noch keine Treffer. Starte die Suche im Tab „Suche".</p>';
  }

  function shareLinks() {
    var list = passing();
    var text = 'Edelmetall-Treffer (' + list.length + ')\n' + (spot ? 'Gold CHF ' + spot.goldChfPerG.toFixed(2) + '/g, Silber CHF ' + spot.silverChfPerG.toFixed(3) + '/g\n\n' : '\n') +
      list.map(function (it, i) {
        return (i + 1) + '. [' + it.score + '/10 ' + (it.metal === 'gold' ? 'Gold' : 'Silber') + '] ' + it.title + ' – CHF ' + fmt(it.price) +
          ' / Wert ≈ CHF ' + fmt(it.value) + (it.margin != null ? ' (' + Math.round(it.margin * 100) + '%)' : '') + '\n' + it.url;
      }).join('\n\n');
    Android.share(text);
  }

  function setButtons() {
    $('startBtn').disabled = running;
    $('recheckBtn').disabled = running;
    $('stopBtn').disabled = !running;
  }

  function showTab(name) {
    ['search', 'results', 'settings'].forEach(function (t) {
      $('tab_' + t).style.display = t === name ? 'block' : 'none';
      $('nav_' + t).classList.toggle('active', t === name);
    });
  }

  function init() {
    loadSettings();
    loadItems();
    try { spot = JSON.parse(Android.load('spot') || 'null'); } catch (e) { }
    if (spot) $('spot').textContent = 'Gold CHF ' + spot.goldChfPerG.toFixed(2) + '/g · Silber CHF ' + spot.silverChfPerG.toFixed(3) + '/g (zuletzt)';
    render(); setButtons();
    document.addEventListener('click', function (e) {
      var b = e.target.closest('button.link');
      if (b) Android.openExternal(b.getAttribute('data-url'));
    });
    document.querySelectorAll('#tab_settings input, #tab_settings textarea').forEach(function (el) { el.addEventListener('change', readSettings); });
  }

  return {
    init: init, start: start, stop: stop, resume: resume, recheck: recheck, reset: reset, share: shareLinks, showTab: showTab,
    takeCurrentPage: takeCurrentPage, onWorker: onWorker, onHttp: onHttp,
    toggleWorker: function () { Android.toggleWorker(); },
    fbLogin: function () { Android.openInWorker('https://www.facebook.com/login'); Android.showWorker(true); },
    openTutti: function () { Android.openInWorker('https://www.tutti.ch/de'); Android.showWorker(true); },
    resetDefaults: function () { try { Android.save('settings', '{}'); } catch (e) { } loadSettings(); }
  };
})();
document.addEventListener('DOMContentLoaded', App.init);
