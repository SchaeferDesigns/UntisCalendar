# Untis Kalender

Android App, die den WebUntis Stundenplan automatisch in einen Google Kalender überträgt.

## Funktionen

1. Login mit Untis Benutzername und Passwort (JSON-RPC API von WebUntis)
2. Abgleich jede Stunde im Hintergrund und per Knopfdruck
3. Ausgefallene Stunden bleiben stehen und heißen "ENTFALL: Fach"
4. Vertretungen, Raumänderungen und Hinweise stehen in der Beschreibung
5. Doppelstunden werden zu einem Eintrag zusammengefasst
6. Keine Erinnerungen an den Einträgen
7. Passwort liegt verschlüsselt im Android Keystore, nichts verlässt das Handy außer der Anfrage an Untis

## Einrichtung

1. In Google Calendar (Web) einen neuen Kalender "Untis" anlegen und dort die Standard Benachrichtigungen entfernen.
2. APK von der neuesten Release Seite installieren.
3. App öffnen, Kalenderzugriff erlauben, Benutzername und Passwort eingeben, Kalender "Untis" wählen, synchronisieren.

## Build

Jeder Push auf `main` baut die APK über GitHub Actions und veröffentlicht sie als Release.

Optional für dauerhaft gleiche Signatur (sonst muss die App vor einem Update eventuell neu installiert werden):
Secrets `UNTIS_KEYSTORE_BASE64`, `UNTIS_KEYSTORE_PASSWORD`, `UNTIS_KEY_ALIAS`, `UNTIS_KEY_PASSWORD` im Repo hinterlegen.

Lokal: `./gradlew testDebugUnitTest assembleRelease`
