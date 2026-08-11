# Nightscout Nachtwecker

Android-App, die den Blutzuckerwert aus Nightscout überwacht und bei Grenzwertüberschreitung oder Verbindungs-/Sensorfehler einen Alarm auslöst – auch bei gesperrtem Bildschirm.

> 🤖 Dieses Projekt (Code, Design und diese Anleitung) wurde vollständig mit KI erstellt.

> ⚠️ Die App ist für den persönlichen Gebrauch gedacht. Siehe Hinweise zur Zuverlässigkeit unten.

---

## Voraussetzungen

- Android-Handy
- Erreichbarer Nightscout-Server (die URL, z. B. `https://meine-seite.herokuapp.com`)
  - Kein Login/Token erforderlich – die App greift nur lesend auf die URL zu

---

## Einrichtung

1. Oben rechts auf das ☁️-Symbol tippen → Nightscout-URL eingeben, untere und obere Grenze (mg/dL) festlegen → Speichern
2. Oben links auf das Wecker-Symbol tippen → Alarmton auswählen (eigene MP3s können importiert werden), Vibration ein/aus
3. Auf der Startseite auf **System** tippen und dort:
   - Akku-Optimierung für die App deaktivieren
   - „Nicht stören"-Berechtigung erteilen

   Beides ist Voraussetzung für zuverlässige Alarmauslösung.

---

## Alarmauslösung

| Situation | Bedingung |
|-----------|-----------|
| 🩸 Wert außerhalb der Grenzen | Blutzucker über oder unter der eingestellten Grenze |
| 📡 Sensorfehler | Seit über 15 Minuten kein neuer Messwert |
| 🌐 Verbindungsproblem | Server seit ca. 16 Minuten nicht erreichbar |

Während einer aktiven Snooze-Phase werden keine Alarme ausgelöst.

---

## Alarm quittieren

Der Alarm stoppt erst nach Lösen einer Rechenaufgabe (z. B. `4 + 7 + 3`). Der Bestätigen-Knopf ist zusätzlich 10 Sekunden gesperrt.

Nach der Quittierung gilt automatisch eine 15-minütige Snooze-Phase.

Auf der Snooze-Seite kann eine Pause auch manuell in Minuten eingetragen oder eine laufende Pause vorzeitig beendet werden.

---

## Zuverlässigkeit

App nicht aus den zuletzt geöffneten Apps entfernen – der Alarm-Dienst läuft zwar auch dann im Hintergrund weiter, ein Verbleib in den Recents erhöht aber die Zuverlässigkeit zusätzlich.

⚠️ Auch mit allen Maßnahmen (Akku-Optimierung deaktiviert, „Nicht stören" erlaubt) gibt es **keine 100%ige Garantie** für eine zuverlässige Alarmauslösung – Android-Systemverhalten, Netzwerk- oder Sensorausfälle lassen sich nicht vollständig ausschließen.
