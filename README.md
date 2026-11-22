# 🦊 Fox VPN

Kostenloses VPN mit eingebautem privatem Browser, im Fox-Stil.
Land wählen, großen Knopf drücken, fertig. Kein Konto nötig.

## Funktionen
- **Ein-Knopf-Verbindung** mit Länderwahl oder automatisch zum schnellsten Server
- **Server von VPN Gate** (Universität Tsukuba), kostenlos, viele Länder, die Liste wird automatisch geladen
- **„Anderer Server“**, falls einer nicht klappt
- **Anzeige der sichtbaren IP** samt Land
- **Privater Browser** ohne Cache und ohne Drittanbieter-Cookies, mit Werbe- und Tracker-Blocker und einem Knopf zum Löschen aller Spuren

## Installation
1. Die neueste `Fox-VPN.apk` unter **Releases** herunterladen und installieren.
2. Auf Verbinden tippen und die VPN-Abfrage einmal bestätigen.

Der VPN-Motor ist eingebaut, eine Zusatz-App ist nicht mehr nötig.

## Hinweis
Die VPN-Gate-Server betreiben Freiwillige. Das reicht gut für Länderwechsel und Streaming.
Für Banking und Passwörter solltest du sie nicht nutzen.

## Bauen
Bei jedem Push auf `main` holt GitHub Actions den VPN-Motor
[ics-openvpn](https://github.com/schwabe/ics-openvpn) (GPLv2) in einer festen Version.
Danach baut `fox/patch.py` die Fox-Oberfläche ein, die APK wird gebaut und an ein neues Release gehängt.

## Lizenz
Fox VPN enthält ics-openvpn und OpenVPN und steht deshalb unter der **GPLv2**.
