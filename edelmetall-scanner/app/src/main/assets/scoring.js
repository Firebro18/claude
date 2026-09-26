// Gold-/Silber-Agent (regelbasiert): Score 0-10, Metall, Feingehalt, geschätztes Feingewicht.
// Reine Funktionen ohne DOM-Zugriff, damit sie auch mit Node getestet werden können.
(function (root) {
  'use strict';

  var OZ = 31.1035;

  // Wörter, bei denen "Silber"/"Gold" nur Farbe oder Beschichtung ist, oder die kein Verkaufsinserat sind.
  var PLATED = /versilbert|vergoldet|hartversilb|silberauflage|goldauflage|\bauflage\b|\b(90|100|150)er\s*(silber)?auflage|plaqu|plated|gold[-\s]?filled|\bgf\b|doubl[eé]|\brgp\b|alpaka|neusilber|christofle|\bepns\b|\bep\b\s*ns|goldfarb|silberfarb|goldton|silberton|gold[-\s]?optik|silber[-\s]?optik|goldlook|silberlook|goldkante|goldrand|silberrand|goldbemalt|goldschrift|imitat|replik|replica|\bfake\b|kopie|modeschmuck|edelstahl|stainless|messing|tombak|chrom/;
  var NOT_OFFER = /\bankauf\b|\bkaufe\b|\bsuche\b|\bgesucht\b|wir kaufen|kaufe[n]? (ihr|dein)|bargeld für|\bankauf von/;
  var NON_METAL_ITEMS = /iphone|samsung|galaxy|huawei|handy|laptop|macbook|notebook|\btv\b|fernseher|monitor|kühlschrank|waschmaschine|\bauto\b|\bvw\b|bmw|audi|mercedes|\bvelo\b|fahrrad|e-bike|roller|felgen|reifen|jacke|mantel|kleid|schuhe|sneaker|hose|pullover|tasche aus leder|sofa|stuhl|tisch|lampe|vorhang|farbe:?\s*silber|farbe:?\s*gold|silbergrau|silber metallic|goldgelb|playstation|xbox|nintendo|kinderwagen|lego|briefmarke(?!n?\s*(aus|in)\s*silber)|porzellan|keramik|glas\b|kristall|pokémon|pokemon|karte\b/;

  var SILVERSMITHS = /jezler|meister|bruckmann|wilkens|koch\s*&?\s*bergfeld|robbe\s*&?\s*berking|georg jensen|tiffany|christoph widmann|bossard|gebr\.? ?hemmerle|puiforcat|buccellati|sauter|hugo boss silber|lutz|zuber|sporrer|vaugoin|jarosinski|schelhaas|peter bertschinger/;
  var GOLDBRANDS = /patek|rolex|omega|iwc|longines|zenith|vacheron|audemars|cartier|bucherer|gübelin|gubelin|chopard|bulgari|bvlgari|van cleef|breguet|jaeger|lecoultre|tissot|doxa|movado|eterna/;
  var BULLION_BRANDS = /degussa|umicore|argor|heraeus|valcambi|pamp|credit suisse|\bubs\b|metalor|münze österreich|perth mint|royal mint|rand refinery|\bprägefrisch\b/;
  var HALLMARK_WORDS = /punze|punziert|gestempelt|stempel|feingehalt|hallmark|repunze|meistermarke|halbmond|krone|bär(en)?punze|eidg\.? kontrollamt/;

  function norm(s) {
    return String(s || '')
      .toLowerCase()
      .replace(/[’`´]/g, "'")
      .replace(/ /g, ' ');
  }

  function num(s) {
    if (s == null) return null;
    var t = String(s).replace(/'/g, '').replace(/\s/g, '');
    // 1.234,50 oder 1,234.50
    if (/\d\.\d{3},\d{1,2}$/.test(t)) t = t.replace(/\./g, '').replace(',', '.');
    else if (/\d,\d{3}\.\d{1,2}$/.test(t)) t = t.replace(/,/g, '');
    else t = t.replace(',', '.');
    var n = parseFloat(t);
    return isFinite(n) ? n : null;
  }

  // Preis aus einem Text wie "CHF 1'200.–", "Fr. 80.-", "120 CHF", "Gratis"
  function parsePrice(text) {
    var t = norm(text);
    if (/gratis|zu verschenken|kostenlos/.test(t)) return 0;
    var m = t.match(/(?:chf|fr\.?|sfr\.?)\s*([\d'’.,\s]*\d)/) ||
      t.match(/([\d'’.,]*\d)\s*(?:chf|fr\.?\b|\.[-–—])/) ||
      t.match(/^\s*([\d'’]+(?:[.,]\d{1,2})?)\s*$/);
    if (!m) return null;
    var v = num(m[1].replace(/\s/g, ''));
    return v != null && v < 10000000 ? v : null;
  }

  // Gewicht in Gramm aus Text. Nimmt "3 x 10 g" als 30 g. Gibt den grössten plausiblen Wert zurück.
  function parseWeight(t) {
    var best = null, m;
    var mult = /(\d{1,3})\s*[x×]\s*(\d+(?:[.,]\d+)?)\s*(kg|kilo|g|gr|gramm|grams?)\b/g;
    while ((m = mult.exec(t))) {
      var g = num(m[1]) * num(m[2]) * (/^k/.test(m[3]) ? 1000 : 1);
      if (g > 0 && g < 50000 && (best == null || g > best)) best = g;
    }
    var re = /(\d+(?:[.,']\d+)*)\s*(kg|kilo|g|gr|gramm|grams?)\b\.?/g;
    while ((m = re.exec(t))) {
      var v = num(m[1]);
      if (v == null) continue;
      var grams = /^k/.test(m[2]) ? v * 1000 : v;
      if (grams > 0 && grams < 50000 && (best == null || grams > best)) best = grams;
    }
    var gw = t.match(/gewicht\s*:?\s*(?:ca\.?|circa|total)?\s*(\d+(?:[.,]\d+)?)(?!\s*(?:kg|g|gr|gramm|%|cm|mm))/);
    if (best == null && gw) best = num(gw[1]);
    var oz = t.match(/(\d+(?:[.,]\d+)?)\s*(?:oz|unze|unzen|ounce)\b/);
    if (oz) {
      var ozg = num(oz[1]) * OZ;
      if (best == null || ozg > best) best = ozg;
    }
    return best;
  }

  function pieceCount(t) {
    var m = t.match(/(\d{1,3})\s*[-\s]?(?:teilig|tlg\.?|teile|stk\.?|stück|pcs|pieces)\b/);
    return m ? parseInt(m[1], 10) : null;
  }

  var SILVER_CTX = /silber|silver|sterling|argent|argento/;
  var GOLD_CTX = /gold|or\s+jaune|oro\b|karat|\bkt\b/;

  function detectPurity(t) {
    var gold = null, silver = null, ev = [];
    if (/feingold|\b999[.,]9\b|\b9999\b|\b24\s?(k|kt|kar|karat)\b/.test(t) && GOLD_CTX.test(t)) { gold = 0.999; ev.push('Feingold/999.9'); }
    else if (/\b(916|917)\b|\b22\s?(k|kt|kar|karat)\b/.test(t) && GOLD_CTX.test(t)) { gold = 0.916; ev.push('22 kt'); }
    else if (/\b750\b(?!\s*(ml|w\b|watt|ccm|cm|mm|kg|g\b))|\b18\s?(k|kt|kar|karat|c|ct)\b|\b18\s?-?\s?karat/.test(t)) { gold = 0.75; ev.push('750 / 18 kt'); }
    else if (/\b585\b|\b14\s?(k|kt|kar|karat|c|ct)\b|\b14\s?-?\s?karat/.test(t)) { gold = 0.585; ev.push('585 / 14 kt'); }
    else if (/\b375\b|\b9\s?(k|kt|kar|karat|ct)\b/.test(t) && GOLD_CTX.test(t)) { gold = 0.375; ev.push('375 / 9 kt'); }
    else if (/\b333\b|\b8\s?(k|kt|kar|karat)\b/.test(t) && GOLD_CTX.test(t)) { gold = 0.333; ev.push('333 / 8 kt'); }
    else if (/\b900\b/.test(t) && /gold/.test(t) && !SILVER_CTX.test(t)) { gold = 0.9; ev.push('900 Gold'); }

    if (/feinsilber|\b999\b/.test(t) && SILVER_CTX.test(t)) { silver = 0.999; ev.push('Feinsilber 999'); }
    else if (/\b925\b|sterling/.test(t)) { silver = 0.925; ev.push('925 / Sterling'); }
    else if (/\b900\b/.test(t) && SILVER_CTX.test(t)) { silver = 0.9; ev.push('900 Silber'); }
    else if (/\b835\b/.test(t) && (SILVER_CTX.test(t) || /franken|fr\.|münze/.test(t))) { silver = 0.835; ev.push('835 Silber'); }
    else if (/\b830\b/.test(t) && SILVER_CTX.test(t)) { silver = 0.83; ev.push('830 Silber'); }
    else if (/\b800\s?er\b|\b800\b/.test(t) && SILVER_CTX.test(t)) { silver = 0.8; ev.push('800 Silber'); }
    else if (/\b(84|88)\s*(zolotnik|solotnik)|\bzolotnik/.test(t)) { silver = 0.875; ev.push('Zolotnik (russisch)'); }
    return { gold: gold, silver: silver, evidence: ev };
  }

  // Bekannte Münzen und Barren mit fixem Feingewicht.
  function detectCoins(t) {
    var n = 1, m = t.match(/(\d{1,3})\s*(?:x|stk\.?|stück|mal)\s/);
    if (m) n = Math.max(1, Math.min(200, parseInt(m[1], 10)));
    var year = null, y = t.match(/\b(18[5-9]\d|19[0-6]\d)\b/);
    if (y) year = parseInt(y[1], 10);

    if (/vreneli|helvetia\s*gold/.test(t)) {
      if (/\b10\s*(fr|franken)/.test(t)) return { metal: 'gold', fine: 2.903 * n, score: 9, label: n + '× 10 Fr. Vreneli' };
      if (/\b100\s*(fr|franken)/.test(t)) return { metal: 'gold', fine: 29.03 * n, score: 9, label: n + '× 100 Fr. Vreneli' };
      return { metal: 'gold', fine: 5.806 * n, score: 9, label: n + '× 20 Fr. Vreneli' };
    }
    if (/krügerrand|krugerrand|kruegerrand/.test(t)) return { metal: 'gold', fine: OZ * n, score: 9, label: n + '× Krügerrand' };
    if (/(maple leaf|philharmoniker|britannia|american eagle|känguru|kangaroo|panda|libertad)/.test(t)) {
      var metal = /silber|silver/.test(t) ? 'silver' : (/gold/.test(t) ? 'gold' : null);
      if (metal) return { metal: metal, fine: OZ * n, score: 9, label: n + '× Anlagemünze 1 oz ' + (metal === 'gold' ? 'Gold' : 'Silber') };
    }
    if (/(gold|silber)\s*barren|barren\s*(gold|silber)/.test(t)) {
      var w = parseWeight(t);
      var metalB = /gold/.test(t) ? 'gold' : 'silver';
      if (w) return { metal: metalB, fine: w * 0.999, score: BULLION_BRANDS.test(t) ? 9 : 8, label: 'Barren ' + w + ' g' };
    }
    // Schweizer Umlaufmünzen bis 1967 = 835 Silber
    if (year && year <= 1967 && /(franken|fr\.|fränkli|fünfliber|fuenfliber|münze|muenze)/.test(t)) {
      if (/fünfliber|fuenfliber|\b5\s*(fr|franken)/.test(t)) return { metal: 'silver', fine: (year < 1931 ? 22.5 : 12.525) * n, score: 9, label: n + '× 5 Fr. ' + year };
      if (/\b2\s*(fr|franken)/.test(t)) return { metal: 'silver', fine: 8.35 * n, score: 9, label: n + '× 2 Fr. ' + year };
      if (/\b1\s*(fr|franken)/.test(t)) return { metal: 'silver', fine: 4.175 * n, score: 9, label: n + '× 1 Fr. ' + year };
      if (/(1\/2|½|halb)\s*(fr|franken)/.test(t)) return { metal: 'silver', fine: 2.0875 * n, score: 9, label: n + '× ½ Fr. ' + year };
    }
    if (/schützentaler|schuetzentaler|schützenfesttaler/.test(t)) return { metal: 'silver', fine: 20 * n, score: 8, label: n + '× Schützentaler' };
    if (/reichsmark/.test(t) && /\b5\s*(rm|reichsmark)/.test(t)) return { metal: 'silver', fine: 12.5 * n, score: 8, label: n + '× 5 Reichsmark' };
    return null;
  }

  // Geschätzter Edelmetallanteil am Bruttogewicht je nach Objektart.
  function contentFactor(t, metal) {
    if (/taschenuhr|savonnette|armbanduhr|\buhr\b|chronograph/.test(t)) return metal === 'gold' ? 0.45 : 0.6;
    if (/besteck|messer/.test(t) && metal === 'silver') return /messer/.test(t) ? 0.85 : 1;
    if (/leuchter|kerzenständer|kandelaber/.test(t)) return 0.6; // oft gefüllt
    if (/(mit|inkl\.?)\s*(stein|diamant|brillant|perle|saphir|rubin|smaragd)/.test(t)) return 0.9;
    if (/etui|zigarettenetui|dose/.test(t)) return 0.95;
    return 1;
  }

  // Ungefähre Gewichte, wenn keins angegeben ist (bewusst vorsichtig).
  function guessWeight(t, metal) {
    var pieces = pieceCount(t);
    if (/besteck|tafelsilber/.test(t)) return pieces ? pieces * 40 : null;
    if (/taschenuhr|savonnette/.test(t)) return metal === 'gold' ? 45 : 80;
    if (/armbanduhr/.test(t)) return metal === 'gold' ? 25 : null;
    if (/\bring\b|ehering|trauring/.test(t)) return metal === 'gold' ? 3 : 5;
    if (/kaffeelöffel|teelöffel|mokkalöffel/.test(t)) return pieces ? pieces * 15 : 15;
    if (/löffel|gabel/.test(t)) return pieces ? pieces * 40 : 40;
    if (/serviettenring/.test(t)) return pieces ? pieces * 25 : 25;
    if (/becher/.test(t)) return 90;
    if (/medaille/.test(t)) return metal === 'gold' ? null : 20;
    return null;
  }

  /**
   * Bewertet ein Inserat. item: {title, text (Beschreibung/Snippet), price}
   * Rückgabe: {score, metal, purity, fineGrams, weightSource, reasons[], excluded}
   */
  function scoreItem(item) {
    var title = norm(item.title);
    var t = norm((item.title || '') + ' \n ' + (item.text || ''));
    var reasons = [];
    var r = { score: 0, metal: null, purity: null, fineGrams: null, weightSource: null, reasons: reasons, excluded: false };

    if (NOT_OFFER.test(title)) {
      r.excluded = true; reasons.push('Such-/Ankaufinserat, kein Verkauf');
      return r;
    }

    var coin = detectCoins(t);
    var p = detectPurity(t);
    var score = 0;

    if (coin) {
      r.metal = coin.metal; r.fineGrams = coin.fine; r.weightSource = 'Münze/Barren (Normgewicht)';
      score = coin.score; reasons.push(coin.label);
    } else if (p.gold || p.silver) {
      // Beide Punzen: 925 mit "vergoldet" ist Silber; Gold-Karat ohne Silberkontext ist Gold.
      var isGold = p.gold && (!p.silver || (GOLD_CTX.test(t) && !/vergoldet/.test(t)));
      r.metal = isGold ? 'gold' : 'silver';
      r.purity = isGold ? p.gold : p.silver;
      score = 7; reasons.push('Feingehalt: ' + p.evidence.join(', '));
    } else if (/echt\s*(silber|gold)|massiv\s*(silber|gold)|(silber|gold)\s*massiv|reines?\s*(silber|gold)|vollsilber/.test(t)) {
      r.metal = /gold/.test(t) ? 'gold' : 'silver';
      r.purity = r.metal === 'gold' ? 0.585 : 0.8; // vorsichtige Annahme
      score = 6; reasons.push('"echt/massiv" ohne Punze – Feingehalt angenommen');
    } else if (SILVER_CTX.test(t) || /gold/.test(t)) {
      r.metal = /gold/.test(t) ? 'gold' : 'silver';
      score = 3; reasons.push('Nur Wort "' + (r.metal === 'gold' ? 'Gold' : 'Silber') + '", keine Punze/Gewichtsangabe');
    } else {
      reasons.push('Kein Edelmetall-Hinweis');
      r.score = 0; return r;
    }

    if (!coin) {
      var w = parseWeight(t);
      if (w) {
        score += 1; reasons.push('Gewicht angegeben: ' + Math.round(w) + ' g');
        if (r.purity) { r.fineGrams = w * contentFactor(t, r.metal) * r.purity; r.weightSource = 'Angabe'; }
      } else if (r.purity) {
        var g = guessWeight(t, r.metal);
        if (g) { r.fineGrams = g * contentFactor(t, r.metal) * r.purity; r.weightSource = 'geschätzt'; reasons.push('Gewicht geschätzt ~' + Math.round(g) + ' g'); }
      }
      if (SILVERSMITHS.test(t) || (r.metal === 'gold' && GOLDBRANDS.test(t)) || BULLION_BRANDS.test(t)) { score += 1; reasons.push('Bekannter Hersteller'); }
      if (HALLMARK_WORDS.test(t)) { score += 0.5; reasons.push('Punze/Stempel erwähnt'); }
      if (/\becht\b|massiv|vollsilber|nicht versilbert/.test(t)) score += 0.5;
      if (/antik|nachlass|erbstück|grossmutter|grossvater/.test(t) && score >= 7) score += 0.5;
    }

    // Ausschlüsse: Beschichtung, Farbe, Nicht-Metall-Artikel
    var plated = t.replace(/nicht versilbert|nicht vergoldet|kein(e)? (versilberung|vergoldung)/g, '').match(PLATED);
    if (plated) {
      var silverGilt = /vergoldet/.test(plated[0]) && p.silver; // vergoldetes Silber ist Silber
      if (!silverGilt) { score = Math.min(score, 2); r.excluded = true; reasons.push('Ausschluss: "' + plated[0] + '"'); }
    }
    if (NON_METAL_ITEMS.test(title) && !coin && !(p.gold || p.silver)) {
      score = Math.min(score, 1); r.excluded = true; reasons.push('Artikel ist kein Edelmetallobjekt (Farbe/Sonstiges)');
    }

    r.score = Math.max(0, Math.min(10, Math.round(score)));
    return r;
  }

  function metalValue(fineGrams, metal, spot) {
    if (!fineGrams || !metal || !spot) return null;
    var perG = metal === 'gold' ? spot.goldChfPerG : spot.silverChfPerG;
    return perG ? fineGrams * perG : null;
  }

  // Grobe Koordinaten der Schweizer PLZ-Regionen (erste zwei Ziffern).
  var PLZ2 = {
    10: [46.52, 6.63], 11: [46.51, 6.5], 12: [46.2, 6.15], 13: [46.7, 6.5], 14: [46.78, 6.64], 15: [46.72, 6.85], 16: [46.62, 7.0], 17: [46.8, 7.15], 18: [46.46, 6.85], 19: [46.23, 7.36],
    20: [47.0, 6.93], 21: [46.92, 6.6], 22: [46.98, 6.9], 23: [47.1, 6.83], 24: [47.06, 6.75], 25: [47.14, 7.25], 26: [47.15, 7.0], 27: [47.25, 7.3], 28: [47.36, 7.34], 29: [47.42, 7.08],
    30: [46.95, 7.45], 31: [46.9, 7.55], 32: [47.05, 7.3], 33: [47.06, 7.62], 34: [47.0, 7.7], 35: [46.93, 7.8], 36: [46.76, 7.63], 37: [46.6, 7.6], 38: [46.69, 7.86], 39: [46.3, 7.95],
    40: [47.56, 7.59], 41: [47.5, 7.65], 42: [47.45, 7.55], 43: [47.53, 7.9], 44: [47.48, 7.73], 45: [47.21, 7.53], 46: [47.35, 7.9], 47: [47.3, 7.7], 48: [47.25, 7.85], 49: [47.21, 7.79],
    50: [47.39, 8.05], 51: [47.48, 8.2], 52: [47.47, 8.25], 53: [47.55, 8.25], 54: [47.47, 8.31], 55: [47.39, 8.18], 56: [47.33, 8.28], 57: [47.25, 8.18],
    60: [47.05, 8.3], 61: [47.05, 8.0], 62: [47.17, 8.1], 63: [47.17, 8.52], 64: [47.02, 8.65], 65: [46.19, 9.02], 66: [46.17, 8.8], 67: [46.4, 8.85], 68: [45.95, 8.95], 69: [46.0, 8.95],
    70: [46.85, 9.53], 71: [46.78, 9.2], 72: [46.95, 9.62], 73: [47.02, 9.45], 74: [46.65, 9.45], 75: [46.5, 9.85], 76: [46.35, 9.6], 77: [46.3, 10.05],
    80: [47.38, 8.54], 81: [47.45, 8.55], 82: [47.7, 8.63], 83: [47.35, 8.75], 84: [47.5, 8.73], 85: [47.56, 8.9], 86: [47.28, 8.85], 87: [47.2, 9.0], 88: [47.23, 8.65], 89: [47.3, 8.45],
    90: [47.42, 9.37], 91: [47.38, 9.3], 92: [47.45, 9.15], 93: [47.5, 9.45], 94: [47.4, 9.6], 95: [47.46, 9.05], 96: [47.3, 9.1]
  };

  function haversine(a, b) {
    var R = 6371, toR = Math.PI / 180;
    var dLat = (b[0] - a[0]) * toR, dLon = (b[1] - a[1]) * toR;
    var s = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(a[0] * toR) * Math.cos(b[0] * toR) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.sqrt(s));
  }

  function plzFromText(s) {
    var m = String(s || '').match(/\b([1-9]\d{3})\b(?=\s*[A-Za-zÀ-ÿ])|(?:,\s*|\b)([1-9]\d{3})\s*$/);
    return m ? parseInt(m[1] || m[2], 10) : null;
  }

  function distanceKm(plzFrom, plzTo) {
    var a = PLZ2[Math.floor(plzFrom / 100)], b = PLZ2[Math.floor(plzTo / 100)];
    if (!a || !b) return null;
    return Math.round(haversine(a, b));
  }

  var api = { scoreItem: scoreItem, parsePrice: parsePrice, parseWeight: parseWeight, metalValue: metalValue, plzFromText: plzFromText, distanceKm: distanceKm, detectPurity: detectPurity };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Scoring = api;
})(this);
