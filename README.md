# Webseitenblockierer – Android App

Eine native Android-App, die dieselbe Idee wie die Chrome-Erweiterung umsetzt:
gesperrte Webseiten werden im Browser mit einer Sperr-Überlagerung überdeckt.
Statt eines Content-Scripts nutzt die App eine **Bedienungshilfe**
(Accessibility Service), um die aktuell geöffnete Webseite im Browser zu
erkennen, und ein **Overlay-Fenster** ("Über anderen Apps anzeigen"), um die
Sperre einzublenden. Dadurch funktioniert die Sperre in *jedem* Browser auf
dem Gerät, nicht nur in Chrome.

## Funktionsweise

- **Bedienungshilfe** liest die Adresszeile unterstützter Browser aus und
  ermittelt den Hostnamen der aktuellen Seite.
- Die Sperre wird erst bei einem **tatsächlichen Seitenaufruf** ausgelöst, nicht
  schon, wenn eine gesperrte Adresse nur als Autocomplete-Vorschlag in der
  Adresszeile erscheint (solange die Adresszeile bearbeitet wird, greift die
  Sperre nicht).
- Ist der Host gesperrt und kein aktives Freigabefenster offen, wird die
  Überlagerung angezeigt. Der Sperrbildschirm hält den Browser **nicht dauerhaft
  fest**: Er wird pro Aufruf nur einmal gezeigt. Nach dem Schließen (auch per
  **Zurück-Taste** als Notfalllösung) kann man zu einer anderen Seite navigieren;
  der Sperrbildschirm erscheint erst wieder, wenn die gesperrte Seite erneut
  aufgerufen wird.
- Freigaben werden mit **Guthaben** bezahlt (Punktekonto): Im Sperrbildschirm
  gibt man ein, wie viele Sekunden man einlösen möchte (höchstens den
  Kontostand). Die Sekunden werden abgebucht und die Seite so lange freigegeben.
  Es gibt keine feste Sperrfrist mehr.
- Während einer zeitlich begrenzten Freigabe zeigt ein kleiner **Countdown**
  („Noch 08:42 Minuten") oben am Bildschirm laufend an, wie viel Zeit bis zur
  erneuten Sperre verbleibt.

Unterstützte Browser u. a.: Chrome, Brave, Edge, Samsung Internet, Opera,
Firefox, Kiwi, DuckDuckGo, Vivaldi, Yandex.

## Training (nur10) und Guthaben

Die Übungs-App **nur10** ist als Kotlin-Version in die App integriert
(„Training starten" auf dem Startbildschirm oder „Training öffnen" im
Sperrbildschirm):

- **Kniebeugen, Klimmzüge, Rückenheber** werden über den Beschleunigungssensor
  gezählt, **Liegestütze** durch Antippen des Bildschirms.
- **Jede Wiederholung bringt 1 Sekunde Guthaben** für gesperrte Seiten.
- Das Guthaben **verfällt täglich um Mitternacht**.
- Ansicht „Zahl" oder „Bilder": In der Bilder-Ansicht erscheint alle 10
  Wiederholungen ein zufälliges **eigenes Bild**. Bilder lassen sich im Training
  hinzufügen und einzeln (antippen) oder alle löschen; sie werden in den
  App-Speicher kopiert.
- Ton: kein Ton, Standard-Ton pro Wiederholung oder **eigene Lieder**. Lieder
  werden in die App hochgeladen und aus der Liste gewählt (antippen); „x“
  löscht ein Lied. Die Musik läuft, solange Wiederholungen kommen, pausiert nach
  5 Sekunden ohne Wiederholung und macht an derselben Stelle weiter. Ist ein Lied
  zu Ende, wird zufällig das nächste gespeicherte Lied gespielt.
- Statistik pro Tag und Monat.

## APK herunterladen und installieren

1. Öffne auf dem Handy die Release-Seite des Repositories und lade
   `app-debug.apk` aus dem Release **"android-latest"** herunter.
2. Erlaube ggf. "Installation aus unbekannten Quellen" für den Browser.
3. Installiere die APK.
4. Öffne die App und aktiviere die beiden Berechtigungen:
   - **Bedienungshilfe aktivieren** → Webseitenblockierer einschalten.
   - **Über anderen Apps anzeigen erlauben** → für die App aktivieren.
5. Füge gesperrte Seiten hinzu (z. B. `youtube.com`).

## Selbst bauen

Voraussetzung: Android SDK (Platform 34, Build-Tools 34.0.0), JDK 17.

```bash
./gradlew assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk
```

## Hinweis zur Bedienungshilfe

Die App verwendet einen Accessibility Service ausschließlich lokal, um die
Adresszeile des Browsers zu lesen und die Sperre einzublenden. Es werden keine
Daten übertragen. Google prüft Apps mit Accessibility Service im Play Store
streng – für die Verteilung als APK (Sideload) ist das unproblematisch.
