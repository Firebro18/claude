# Edelmetall-Scanner (Android)

Sucht auf **tutti.ch** und **Facebook Marketplace** nach Artikeln, die mit hoher Wahrscheinlichkeit echtes Gold oder Silber enthalten,
und sortiert sie nach Marge zum aktuellen Spotpreis (gold-api.com, CHF).

- **Gold-Agent:** Score 0–10 (Punzen 999/925/900/835/800/750/585/375, Münzen wie Vreneli und Schweizer Silbermünzen bis 1967, Barren, Gewicht, Hersteller).
  Ausschluss von versilbert/vergoldet/Doublé/Alpaka, Farbangaben („iPhone silber“) und Ankaufsinseraten. Nur Score 8–10 kommen in die Liste.
  Optional mit Claude-API-Key: Claude bewertet jedes aktive Inserat zusätzlich.
- **Aktiv-Agent:** öffnet jedes Inserat und prüft, ob es noch läuft (nicht abgelaufen, entfernt oder verkauft).
- Umkreisfilter ab eigener PLZ (Standard 8200 Schaffhausen, 80 km), Ziel 100 Links, Teilen-Funktion.

Die Suche läuft im Browser-Engine des Handys (WebView), weil tutti.ch Server-Zugriffe blockiert. Bei einem Captcha oder dem Facebook-Login
zeigt die App das Browserfenster; nach dem Lösen „Weiter“ tippen. App während der Suche offen lassen.

## Installation
`EdelmetallScanner.apk` aufs Handy laden, öffnen, „Installation aus unbekannten Quellen“ erlauben.

## Bauen / Testen
```
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
gradle assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
node test/scoring.test.js         # Bewertungslogik
node test/e2e.test.js             # Oberfläche + Extraktoren gegen nachgebaute Seiten (Playwright)
```
