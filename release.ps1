# Veroeffentlicht eine neue Version auf GitHub:
#   .\release.ps1 1.3 "Was ist neu"
# Setzt version.txt, baut und signiert die APK, committet, taggt, pusht und legt das GitHub-Release mit APK an.
# Die App auf dem Handy findet das Update danach selbst (oder ueber "Nach Updates suchen").
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notes = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if ($Version -notmatch '^\d+\.\d+(\.\d+)?$') { throw "Ungueltige Version: $Version" }
$tag = "v$Version"
if (git tag --list $tag) { throw "Version $tag gibt es schon." }

Set-Content (Join-Path $root 'version.txt') $Version -Encoding ascii
& (Join-Path $root 'build.ps1') -Version $Version
if (-not $?) { throw 'Build fehlgeschlagen' }

$apk = Join-Path $root 'AnimeWebApp.apk'
$asset = Join-Path $root "build\AnimeWebApp-$Version.apk"
Copy-Item $apk $asset -Force

git add -A
git commit -m "Version $Version" | Out-Null
git tag $tag
git push origin HEAD --tags
if (-not $Notes) { $Notes = "Version $Version" }
gh release create $tag $asset --title "Anime WebApp $Version" --notes $Notes
Write-Host "Veroeffentlicht: $tag"
