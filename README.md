# Anime WebApp (Android)

Eine schlanke Android-App, die eine frei wählbare Streaming-Webseite in einem eigenen Fenster öffnet und dabei drei Dinge ergänzt:

- **Stream-Extraktion** – erkennt beim Abspielen HLS- (`.m3u8`), DASH- (`.mpd`) und Video-Dateien (`.mp4`, `.webm`, …), auch aus eingebetteten Playern.
- **Download** – MP4 über den Android-Download-Manager, HLS über einen Hintergrund-Dienst mit Qualitätswahl und Fortschritt in der Benachrichtigung. Dateien landen unter `Download/AnimeWebApp`.
- **Werbe- und Popup-Blocker** – blockiert bekannte Werbe-Netzwerke, Popups, Umleitungen auf fremde Seiten und App-Store-Links.

Bedient wird alles über den runden ⬇-Knopf (verschiebbar), der ein Feld mit den gefundenen Streams und den Einstellungen öffnet.

## Installation

1. Unter [Releases](../../releases/latest) die Datei `AnimeWebApp-<Version>.apk` aufs Handy laden.
2. Antippen und einmalig „Unbekannte Apps installieren“ erlauben.
3. Beim ersten Start die gewünschte Webseite eingeben.

## Updates

Die App prüft beim Start (höchstens alle 6 Stunden) auf neue Releases und bietet die Installation direkt an. Manuell: ⬇-Knopf → **Nach Updates suchen**.

## Grenzen

- Verschlüsselte HLS-Streams und DASH werden nicht heruntergeladen (DASH-URL lässt sich kopieren, z. B. für yt-dlp).
- Bitte nur Inhalte herunterladen, für die du die Rechte hast.

## Selbst bauen (Windows)

Voraussetzungen: Microsoft OpenJDK 17 und Android SDK (Build-Tools 35.0.0, Plattform android-35) unter `D:\Android\Sdk` oder in `ANDROID_HOME`. Gradle wird nicht benötigt.

```powershell
.\build.ps1              # baut AnimeWebApp.apk (Version aus version.txt)
.\release.ps1 1.3 "Was ist neu"   # baut, taggt, pusht und erstellt das GitHub-Release
```

Beim ersten Build wird unter `signing/` ein Signaturschlüssel erzeugt. Dieser Ordner wird nicht hochgeladen und muss gut aufbewahrt werden – ohne ihn lassen sich Updates nicht über eine installierte Version installieren.
