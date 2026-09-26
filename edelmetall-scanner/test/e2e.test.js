// Integrationstest: echte App-Oberfläche + extract.js gegen nachgebaute tutti/FB-Seiten (offline).
const path = require('path'), fs = require('fs');
const { chromium } = require(require('child_process').execSync('npm root -g').toString().trim() + '/playwright');
const assets = path.join(__dirname, '../app/src/main/assets');
const extractJs = fs.readFileSync(path.join(assets, 'extract.js'), 'utf8');

const searchHtml = `<html><body><main>
${[['silberbesteck-800-jezler/111111', 'Silberbesteck 800 Jezler 24-teilig', 'CHF 450.–', '8200 Schaffhausen'],
   ['iphone-silber/222222', 'iPhone 12 silber', 'CHF 300.–', '8400 Winterthur'],
   ['goldvreneli/333333', 'Goldvreneli 20 Fr 1935', 'CHF 480.–', '8500 Frauenfeld'],
   ['silberschale/444444', 'Silberschale 925', 'CHF 60.–', '1200 Genève'],
   ['abgelaufen/555555', 'Silberbecher 800 Jezler', 'CHF 50.–', '8212 Neuhausen']]
  .map(([p, t, pr, l]) => `<article><a href="https://www.tutti.ch/de/vi/sh/antiquitaeten/${p}"><h2>${t}</h2></a><div>Heute, 10:12</div><div>${l}</div><div>${pr}</div></article>`).join('')}
</main></body></html>`;
const details = {
  '111111': `<main><h1>Silberbesteck 800 Jezler 24-teilig</h1><p>Schönes Tafelsilber 800 punziert, Gewicht 1.2 kg ohne Messer.</p><div>CHF 450.–</div><div>8200 Schaffhausen</div></main>`,
  '333333': `<main><h1>Goldvreneli 20 Fr 1935</h1><p>Echtes Vreneli.</p><div>CHF 480.–</div><div>8500 Frauenfeld</div></main>`,
  '555555': `<main><h1>Silberbecher 800 Jezler</h1><p>Dieses Inserat ist nicht mehr verfügbar</p></main>`,
  '777777': `<div role="main"><h1>Goldkette 750 18 g</h1><div>CHF 900</div><p>Echte Goldkette 750 gestempelt, 18 g</p><div>Verkäuferinformationen</div><p>Silber 999 Barren 1kg</p></div>`,
};
const fbSearch = `<div role="main"><a href="https://www.facebook.com/marketplace/item/777777/?ref=x"><span>CHF 900</span><span>Goldkette 750 18 g</span><span>Schaffhausen</span></a></div>`;

(async () => {
  const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
  const wp = await b.newPage();   // Worker
  const up = await b.newPage();   // UI
  let fail = 0;
  const store = {};
  let routed = false;
  async function runWorker(token, url, mode, scrolls) {
    if (!routed) {
      routed = true;
      await wp.route(/^https:\/\/www\.(tutti\.ch|facebook\.com)\//, route => {
        const u = route.request().url();
        let html;
        if (/facebook.*search/.test(u)) html = fbSearch;
        else if (/tutti.*query/.test(u)) html = /page=1/.test(u) ? searchHtml : '<main></main>';
        else { const id = (u.match(/(\d{6})/) || [])[1]; html = details[id] || '<main>404 Seite nicht gefunden</main>'; }
        route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<!doctype html><meta charset="utf-8">' + html });
      });
    }
    await wp.goto(url);
    const js = extractJs.replaceAll('__TOKEN__', token).replaceAll('__MODE__', mode).replaceAll('__SCROLLS__', '0');
    return wp.evaluate(js2 => new Promise(r => { window.WorkerBridge = { result: (t, j) => r(j) }; eval(js2); }), js);
  }
  await up.exposeFunction('__worker', runWorker);
  await up.exposeFunction('__http', async (url) => /XAU/.test(url) ? '{"price":3547.7}' : '{"price":53.2}');
  await up.addInitScript(() => {
    window.Android = {
      load: k => (window.__store || {})[k] || null, save: (k, v) => { (window.__store = window.__store || {})[k] = v; },
      loadInWorker: (t, u, m, s) => window.__worker(t, u, m, s).then(j => App.onWorker(t, j)),
      http: (t, m, u) => window.__http(u).then(body => App.onHttp(t, 200, body)),
      showWorker() {}, toggleWorker() {}, notify() {}, keepAwake() {}, share: t => { window.__shared = t; }, openExternal() {}, openInWorker() {}
    };
    window.confirm = () => true;
  });
  await up.goto('file://' + path.join(assets, 'index.html'));
  await up.evaluate(() => {
    document.getElementById('s_tuttiQueries').value = 'silber';
    document.getElementById('s_fbQueries').value = 'gold';
    document.getElementById('s_settleMs').value = '500';
    return App.start();
  });
  const cards = await up.$$eval('#results .card', cs => cs.map(c => c.innerText.replace(/\s+/g, ' ')));
  console.log(cards.join('\n---\n'));
  const log = await up.$eval('#log', e => e.innerText);
  console.log('\nLOG:\n' + log);
  const expect = (cond, msg) => { if (!cond) { fail++; console.log('FAIL: ' + msg); } else console.log('OK: ' + msg); };
  expect(cards.length === 3, '3 Treffer (Besteck, Vreneli, FB-Goldkette)');
  expect(!cards.some(c => /iPhone/.test(c)), 'iPhone ausgeschlossen');
  expect(!cards.some(c => /Becher/.test(c)), 'abgelaufenes Inserat ausgeschlossen');
  expect(!cards.some(c => /schale/i.test(c)), 'Genf ausserhalb 80 km ausgeschlossen');
  expect(cards.some(c => /Goldkette/.test(c) && /facebook/.test(c)), 'Facebook-Treffer vorhanden');
  await up.evaluate(() => App.share());
  const shared = await up.evaluate(() => window.__shared);
  expect(/tutti\.ch\/de\/vi/.test(shared) && /marketplace\/item\/777777/.test(shared), 'Teilen-Text enthält Links');
  await b.close();
  process.exit(fail ? 1 : 0);
})();
