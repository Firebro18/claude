const S = require('../app/src/main/assets/scoring.js');
const spot = { goldChfPerG: 114.06, silverChfPerG: 1.7 };
const cases = [
  ['Silberbesteck Jezler La Suisse 800er 44-teilig', '', 3900, '>=8'],
  ['Schale Silber 800 Jezler', 'Gewicht 100 g', 80, '>=8'],
  ['Goldvreneli 20 Franken 1935', '', 525, '>=8'],
  ['5 Franken 1954 Silber', '', 25, '>=8'],
  ['Goldring 750', 'Gewicht 4.2 g', 250, '>=8'],
  ['Versilbertes Besteck WMF 90', 'schön', 50, '<8'],
  ['iPhone 13 silber', '128 GB', 400, '<8'],
  ['Kaufe Gold und Silber', 'Bargeld', null, '<8'],
  ['Silberkette', 'schöne Kette', 30, '<8'],
  ['Kette 925 Silber 12 g', '', 20, '>=8'],
  ['Taschenuhr 14k Gold', 'Omega, 62 g total', 900, '>=8'],
  ['Weinglas mit Goldrand', '', 10, '<8'],
  ['Konvolut Silbermünzen 835', '10 x 1 Fr 1920', 60, '>=8'],
  ['Vase 750 ml', 'Glas', 10, '<8'],
  ['Brosche vergoldet 925 Silber 8g', '', 15, '>=8'],
];
let fail = 0;
for (const [title, text, price, exp] of cases) {
  const r = S.scoreItem({ title, text, price });
  const val = S.metalValue(r.fineGrams, r.metal, spot);
  const ok = exp === '>=8' ? r.score >= 8 : r.score < 8;
  if (!ok) fail++;
  console.log((ok ? 'OK  ' : 'FAIL') + ' ' + r.score + ' ' + title + ' | ' + r.metal + ' fine=' + (r.fineGrams && r.fineGrams.toFixed(1)) + ' CHF=' + (val && val.toFixed(0)) + ' | ' + r.reasons.join('; '));
}
const pp = [['CHF 1\'200.–', 1200], ['Fr. 80.-', 80], ['120 CHF', 120], ['Gratis', 0], ['CHF 1.234,50', 1234.5]];
for (const [s, v] of pp) { const x = S.parsePrice(s); if (x !== v) { fail++; console.log('FAIL price', s, x); } }
console.log('dist SH-Winterthur', S.distanceKm(8200, 8400), 'SH-Bern', S.distanceKm(8200, 3000), 'plz', S.plzFromText('8200 Schaffhausen'));
process.exit(fail ? 1 : 0);
