<p align="center">
  <a href="README.md">Русский 🇷🇺</a> ·
  <a href="README.en.md">English 🇺🇸</a> ·
  <a href="README.zh-CN.md">简体中文 🇨🇳</a> ·
  <a href="README.de.md"><strong>Deutsch 🇩🇪</strong></a> ·
  <a href="README.fr.md">Français 🇫🇷</a> ·
  <a href="README.es.md">Español 🇪🇸</a> ·
  <a href="README.it.md">Italiano 🇮🇹</a>
</p>

<h1 align="center">TGX</h1>
<p align="center">Ein lokaler Proxy für Telegram unter Android</p>

<p align="center">
  <img src="assets/giftgx.gif" width="85%" alt="TGX-Logoanimation" />
</p>

## Was ist TGX?

TGX ist eine Android-App, die auf dem Gerät einen lokalen MTProto-Proxy für Telegram startet. Telegram kann damit über einen eigenen Proxy verbunden werden, ohne den gesamten Internetverkehr des Telefons durch TGX zu leiten. TGX ist kein VPN und ändert nicht die Verbindung anderer Apps. Ob Telegram erreichbar ist, hängt vom Netzwerk und der Route ab; TGX garantiert weder eine Umgehung von Sperren noch Anonymität oder Funktion in jedem Netzwerk.

## Projekt unterstützen

Die Unterstützung ist freiwillig und hilft bei Entwicklung und Wartung von TGX.

- **BTC:** `bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5`
- **ETH:** `0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b`
- **USDT (TRON):** `TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg`

## Funktionsweise

<p align="center">
  <img src="assets/TGX.png" width="90%" alt="Konzeptionelle Darstellung der TGX-Routen" />
</p>

1. TGX startet einen Android-Vordergrunddienst und lauscht unter `127.0.0.1:1443`.
2. Telegram verbindet sich dort mit einem MTProto-Proxy. TGX prüft die initialen MTProto-Daten und wählt eine Route zu einem Telegram-Rechenzentrum.
3. Für die Übertragung nutzt TGX TLS/WebSocket: vorgewärmte Verbindungen, Telegram-WebSocket-Endpunkte und anschließend über Cloudflare proxierte Domains. Für konfigurierte Rechenzentren steht bei nicht nutzbaren WebSocket-Routen auch eine direkte TCP-Route bereit.
4. TGX lädt die Cloudflare-Domainliste aus [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), validiert sie und aktualisiert sie ungefähr stündlich. Bei einem Downloadfehler bleibt die letzte gültige lokale Kopie erhalten.
5. Der Dienst überwacht den Android-Netzwerkstatus und verwaltet den lokalen Server sowie Upstream-Verbindungen. Ein Netzwerkwechsel oder Routenfehler kann Verbindungen unterbrechen; ein erfolgreicher Betrieb ist nicht garantiert.

TGX verarbeitet nur Telegram-Verbindungen, die an den lokalen Proxy geleitet werden. Es ist kein systemweiter VPN-Tunnel.

## Installation und erste Schritte

### 1. APK herunterladen

Lade die App ausschließlich über die offiziellen [GitHub Releases](https://github.com/sw1ssl0ve/TGX/releases) herunter. Zum Zeitpunkt der Erstellung dieses README gab es im Repository noch keine Releases mit APK. Installiere keine Builds von Drittanbieter-Websites.

### 2. App installieren

Falls eine APK veröffentlicht wird, öffne sie unter Android und bestätige die Installation. Blockiert Android die Installation, erlaube Apps aus dieser Quelle in den Geräteeinstellungen. Die Menünamen unterscheiden sich je nach Android-Version und Hersteller.

### 3. Berechtigungen

Android kann nach der Benachrichtigungsberechtigung fragen. Damit kann TGX den laufenden Vordergrunddienst anzeigen; über die Benachrichtigung lässt sich der Dienst auch stoppen. Bei Ablehnung fordert TGX den Dienststart trotzdem an, Android kann die Benachrichtigung jedoch ausblenden. Internetzugriff musst du nicht manuell freigeben: Die App deklariert `INTERNET` im Manifest.

### 4. Erste Verbindung

Öffne TGX und starte den Proxy. Nach dem ersten erfolgreichen Start fragt TGX einmal, ob Telegram geöffnet werden soll. Bestätige dies in TGX und füge den vorgeschlagenen MTProto-Proxy in Telegram hinzu oder aktiviere ihn. TGX öffnet einen `tg://proxy`-Link mit `127.0.0.1:1443` und deinem Secret. Ist Telegram nicht installiert oder kann den Link nicht öffnen, zeigt TGX eine Meldung an. Du kannst den Proxy dann in Telegram manuell mit lokaler Adresse, Port und dem Secret aus den TGX-Einstellungen einrichten.

### 5. Funktion prüfen

Prüfe in Telegram, ob eine Verbindung hergestellt wird, Chats und Unterhaltungen laden und eine Testnachricht gesendet und empfangen werden kann. Teste bei Bedarf auch ein Bild oder andere Medien. Der Status in TGX allein bestätigt nicht, dass Telegram Daten erfolgreich überträgt.

### 6. Wenn Telegram keine Verbindung herstellt

Prüfe das Secret in den TGX-Einstellungen; es muss aus 32 hexadezimalen Zeichen bestehen. Du kannst ein neues Secret erzeugen und auf **Speichern und Telegram öffnen** tippen. TGX speichert es, startet einen laufenden Dienst neu oder startet den Dienst und öffnet anschließend Telegram mit dem neuen Link. Bestätige den Proxy in Telegram und wähle den aktuellen Eintrag. TGX kann die in Telegram gespeicherte Proxyliste weder lesen noch bearbeiten. Falls Telegram einen alten Eintrag neben dem neuen behält, wähle den aktuellen Proxy und entferne den veralteten Eintrag bei Bedarf manuell. Wenn Telegram nicht geöffnet wurde, prüfe die Installation und richte den Proxy manuell ein.

## Projektgeschichte und Dank

TGX greift Ideen und kompatible technische Ansätze aus [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) auf, darunter MTProto über WebSocket und das Routing über Cloudflare-Proxy-Domains. TGX ist eine eigenständige Android-App mit eigener Kotlin-Implementierung, Oberfläche und Verwaltung des Android-Dienstes; es ist kein offizielles Flowseal-Produkt.

Vielen Dank an Flowseal und die Mitwirkenden für die Veröffentlichung dieser Arbeit. Das Repository tg-ws-proxy nennt die MIT-Lizenz ([Lizenztext](https://github.com/Flowseal/tg-ws-proxy/blob/main/LICENSE)). Im TGX-Repository gibt es derzeit keine `LICENSE`-Datei. Dieses README legt daher keine Lizenz für TGX fest und erteilt keine Erlaubnis zur Wiederverwendung des Codes. Die Lizenzierung von TGX und die Anwendbarkeit der MIT-Anforderungen auf abgeleitete technische Inhalte muss der Repository-Eigentümer gesondert klären.

## Projekt teilen

Wenn TGX für dich nützlich ist, gib dem Repository einen ⭐, teile es mit Freunden oder informiere Telegram-Communities, für die es hilfreich sein könnte: [github.com/sw1ssl0ve/TGX](https://github.com/sw1ssl0ve/TGX).
