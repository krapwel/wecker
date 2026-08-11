# Nightscout Nachtwecker

Eine Android-App, die deinen Blutzuckerwert aus Nightscout im Blick behält und Alarm schlägt, wenn er zu hoch oder zu niedrig ist – auch nachts, auch wenn das Handy gesperrt ist.

> 🤖 Dieses Projekt (Code, Design und diese Anleitung) wurde vollständig mit KI erstellt.

> ⚠️ Die App ist für den persönlichen Gebrauch gedacht. Bitte die Hinweise zur Zuverlässigkeit unten beachten.

---

## Voraussetzungen

- Ein **Android-Handy**
- Ein erreichbarer **Nightscout-Server** (die URL davon, z. B. `https://meine-seite.herokuapp.com`)
  - Es wird **kein Login/Token benötigt** – die App liest die Werte nur lesend aus der URL

Das war's schon an Voraussetzungen.

---

## Einrichtung

1. App öffnen → oben rechts auf das **☁️-Symbol** tippen → Nightscout-URL eingeben, untere und obere Grenze (mg/dL) festlegen → Speichern
2. Oben links auf das **Wecker-Symbol** tippen → Alarmton auswählen (eigene MP3s können importiert werden), Vibration ein/aus
3. Auf der Startseite auf **System** tippen und dort:
   - Akku-Optimierung für die App deaktivieren
   - „Nicht stören"-Berechtigung erteilen

   Beides ist wichtig, damit der Alarm auch zuverlässig auslöst.

---

## Wann klingelt der Alarm?

| Situation | Wann |
|-----------|------|
| 🩸 Wert außerhalb der Grenzen | Blutzucker über oder unter deiner eingestellten Grenze |
| 📡 Sensorfehler | Seit über 15 Minuten kein neuer Messwert |
| 🌐 Verbindungsproblem | Server seit ca. 16 Minuten nicht erreichbar |

Während einer aktiven Snooze-Phase bleibt es still.

---

## Alarm ausschalten

Der Alarm stoppt nur, wenn eine kleine Rechenaufgabe gelöst wird (z. B. `4 + 7 + 3`) – so verhindert man, dass man ihn im Halbschlaf einfach wegtippt. Der Bestätigen-Knopf ist außerdem 10 Sekunden gesperrt.

Danach ist automatisch 15 Minuten Ruhe (Snooze), bevor der nächste Alarm möglich ist.

Auf der **Snooze-Seite** kann man auch selbst eine Pause in Minuten eintragen oder eine laufende Pause sofort beenden.

---

## Für maximale Zuverlässigkeit

1. App von der Akku-Optimierung ausnehmen (Button auf der Systemseite)
2. „Nicht stören"-Berechtigung erteilen (Button auf der Systemseite)
3. App nicht aus den zuletzt geöffneten Apps wegwischen – der Alarm-Dienst läuft zwar trotzdem im Hintergrund weiter, aber so ist es am sichersten
