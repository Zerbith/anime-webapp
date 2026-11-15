# Baut die APK ohne Gradle direkt mit den Android-SDK-Werkzeugen.
# Ergebnis: AnimeWebApp.apk in diesem Ordner.
param([string]$Version)   # optional; sonst aus version.txt
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }
$bt    = Join-Path $sdk 'build-tools\35.0.0'
$jar   = Join-Path $sdk 'platforms\android-35\android.jar'
$jdk   = (Get-ChildItem "$env:ProgramFiles\Microsoft" -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
if (-not $Version) { $Version = (Get-Content (Join-Path $root 'version.txt') -Raw).Trim() }
if ($Version -notmatch '^\d+\.\d+(\.\d+)?$') { throw "Ungueltige Version: $Version (erwartet z. B. 1.2 oder 1.2.3)" }
$p = ($Version.Split('.') + @('0', '0'))[0..2] | ForEach-Object { [int]$_ }
$versionName = $Version
$version = $p[0] * 10000 + $p[1] * 100 + $p[2]   # 1.2 -> 10200 (muss bei jedem Update steigen)

$out = Join-Path $root 'build'
Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

function Run($exe, [string[]]$a) {
    & $exe @a
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) fehlgeschlagen ($LASTEXITCODE)" }
}

Write-Host '1/6 Ressourcen kompilieren'
Run "$bt\aapt2.exe" @('compile', '--dir', "$root\res", '-o', "$out\res.zip")

Write-Host '2/6 Ressourcen + Manifest verlinken'
Run "$bt\aapt2.exe" @('link', '-I', $jar, '--manifest', "$root\AndroidManifest.xml",
    '--min-sdk-version', '24', '--target-sdk-version', '35',
    '--version-code', "$version", '--version-name', $versionName,
    '--java', "$out\gen", '-o', "$out\base.apk", "$out\res.zip")

Write-Host '3/6 Java kompilieren'
$src = Get-ChildItem "$root\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run "$jdk\bin\javac.exe" (@('-nowarn', '-encoding', 'UTF-8', '--release', '8',
    '-classpath', $jar, '-d', "$out\classes") + $src)

Write-Host '4/6 DEX erzeugen'
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run "$bt\d8.bat" (@('--release', '--min-api', '24', '--lib', $jar, '--output', "$out\dex") + $classes)

Write-Host '5/6 APK packen + ausrichten'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
Copy-Item "$out\base.apk" "$out\unsigned.apk"
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()
Run "$bt\zipalign.exe" @('-f', '-p', '4', "$out\unsigned.apk", "$out\aligned.apk")

Write-Host '6/6 Signieren'
$ks = Join-Path $root 'signing\release.jks'
$pwFile = Join-Path $root 'signing\password.txt'
if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force (Split-Path $ks) | Out-Null
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content $pwFile $pw -NoNewline -Encoding ascii
    Run "$jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $ks, '-alias', 'app', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-storepass', $pw, '-keypass', $pw, '-dname', 'CN=Anime WebApp')
}
$pw = (Get-Content $pwFile -Raw).Trim()
$apk = Join-Path $root 'AnimeWebApp.apk'
Run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-key-alias', 'app', '--ks-pass', "pass:$pw", '--key-pass', "pass:$pw",
    '--out', $apk, "$out\aligned.apk")
Run "$bt\apksigner.bat" @('verify', $apk)
Remove-Item "$apk.idsig" -ErrorAction SilentlyContinue
Write-Host "Fertig: $apk ($([math]::Round((Get-Item $apk).Length / 1KB)) KB)"
