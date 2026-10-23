<#
.SYNOPSIS
    Costruisce la distribuzione nativa di AmiJa per Windows (app-image portatile).

.DESCRIPTION
    Produce una cartella autoconsistente — launcher .exe + runtime Java ridotto +
    tutto quello che l'emulatore legge dalla propria home — e la comprime in
    target/dist/AmiJa-<versione>-windows-<arch>.zip.

    L'utente scompatta e fa doppio clic: nessun JDK da installare.

    Passi: mvn package -> jlink -> jpackage -> copia dei contenuti -> zip.
    Serve un JDK 26 con jlink e jpackage nel PATH (o in JAVA_HOME).

    Il gemello per Linux e' build-image.sh: i due file fanno le stesse cose
    e vanno tenuti allineati.

.PARAMETER Version
    Versione dell'archivio. Se omessa la legge da pom.xml.

.PARAMETER SkipBuild
    Salta "mvn package" e riusa il target/AmiJa.jar gia' presente.

.PARAMETER SmokeTest
    Dopo il packaging lancia l'immagine da una cartella terza e verifica che il
    processo regga e che amiga.properties nasca ACCANTO al launcher (vedi §5.1 del
    piano): e' la prova che la home non dipende dalla working directory.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File packaging\build-image.ps1 -SmokeTest
#>
[CmdletBinding()]
param(
    [string] $Version,
    [switch] $SkipBuild,
    [switch] $SmokeTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# La radice del progetto e' la cartella che contiene packaging/.
$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {

# ---------------------------------------------------------------------------
# Strumenti
# ---------------------------------------------------------------------------
function Resolve-Tool([string] $name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "$name non trovato: serve un JDK 26 in JAVA_HOME o nel PATH."
}

$JLink    = Resolve-Tool 'jlink'
$JPackage = Resolve-Tool 'jpackage'
$Mvn = (Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue)
if (-not $Mvn) { $Mvn = Get-Command 'mvn' -ErrorAction SilentlyContinue }
if (-not $Mvn -and -not $SkipBuild) { throw "mvn non trovato nel PATH (oppure usa -SkipBuild)." }

# ---------------------------------------------------------------------------
# Versione e nome del target
# ---------------------------------------------------------------------------
if (-not $Version) {
    # Letta direttamente dal pom (la stessa <version> che usa Maven). Il gemello .sh
    # usa "mvn help:evaluate"; qui no, perche' passare -Dexpression=... attraverso
    # PowerShell e mvn.cmd e' fragile e in silenzio restituisce l'intero POM.
    $pom = [xml](Get-Content (Join-Path $Root 'pom.xml') -Raw)
    $Version = $pom.project.version
    if (-not $Version) { throw "Non riesco a leggere <version> da pom.xml." }
}
# jpackage su Windows pretende MAJOR.MINOR.BUILD con major <= 255: un'eventuale
# coda tipo "-SNAPSHOT" va tolta (resta nel nome dell'archivio).
$AppVersion = ($Version -split '-')[0]

$Arch = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'x64' }
    'ARM64' { 'arm64' }
    default { throw "Architettura non gestita: $($env:PROCESSOR_ARCHITECTURE)" }
}
$Target  = "windows-$Arch"
$PkgName = "AmiJa-$Version-$Target"

Write-Host "== AmiJa $Version -> $Target (app-version $AppVersion)" -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# 1) Fat jar
# ---------------------------------------------------------------------------
if ($SkipBuild) {
    Write-Host "-- mvn package saltato (-SkipBuild)"
} else {
    Write-Host "-- mvn package"
    & $Mvn.Source -B clean package
    if ($LASTEXITCODE -ne 0) { throw "mvn package fallito." }
}
$Jar = Join-Path $Root 'target\AmiJa.jar'
if (-not (Test-Path $Jar)) { throw "Manca $Jar." }

# ---------------------------------------------------------------------------
# 2) Runtime Java minimo
# ---------------------------------------------------------------------------
# I moduli sono quelli riportati da
#   jdeps --multi-release 26 --ignore-missing-deps --print-module-deps target/AmiJa.jar
# piu' due che jdeps NON puo' vedere perche' arrivano via ServiceLoader:
#   jdk.crypto.ec  handshake TLS ECDHE dell'assistente di download (AssetInstaller)
#   jdk.zipfs      accesso ad archivi via FileSystems (rete di sicurezza, pochi KB)
# java.logging e' entrato con FlatLaf (2026-08-06), che usa java.util.logging: senza,
# il Look and Feel non si carica affatto. Rilanciare jdeps se cambiano le dipendenze.
$Modules = 'java.base,java.desktop,java.logging,java.net.http,jdk.unsupported,jdk.crypto.ec,jdk.zipfs'
$Jre = Join-Path $Root 'target\jre'
if (Test-Path $Jre) { Remove-Item -Recurse -Force $Jre }
Write-Host "-- jlink ($Modules)"
& $JLink --add-modules $Modules `
         --strip-debug --no-header-files --no-man-pages --compress zip-6 `
         --output $Jre
if ($LASTEXITCODE -ne 0) { throw "jlink fallito." }

# ---------------------------------------------------------------------------
# 3) App-image nativa
# ---------------------------------------------------------------------------
# NB: non chiamarla $Input — e' una variabile automatica di PowerShell.
$InputDir = Join-Path $Root 'target\dist-input'
$Stage = Join-Path $Root 'target\stage'
foreach ($d in @($InputDir, $Stage)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    New-Item -ItemType Directory -Path $d | Out-Null
}
Copy-Item $Jar $InputDir

Write-Host "-- jpackage --type app-image"
# NB: '-Damija.home=$ROOTDIR' e' fra apici SINGOLI di proposito. $ROOTDIR non e' una
# variabile PowerShell: e' il segnaposto che il launcher jpackage sostituisce A RUNTIME
# con la radice dell'immagine. E' cio' che rende la home indipendente dalla working dir.
#
# Le quattro -XX:*Inline* sono taratura del JIT: cambiano solo come HotSpot genera il
# codice, non il comportamento emulato. Valgono -2% di wall-time (misurate col banco
# tools/ab-bench, due sessioni, 6/6 coppie concordi ciascuna). ATTENZIONE: l'ottimo NON
# e' monotono — raddoppiarle misura +3,8%, cioe' un rallentamento. Vanno tenute allineate
# con Amija.JIT_OPTIONS, che le ripassa quando la JVM si rilancia da sola.
# Metodo e numeri: docs/performance-jit-plan-2026-09-25.md (tree privato).
& $JPackage --type app-image --name AmiJa --app-version $AppVersion `
            --input $InputDir --main-jar 'AmiJa.jar' `
            --runtime-image $Jre `
            --icon (Join-Path $PSScriptRoot 'icons\amija.ico') `
            --vendor 'zolfrik' `
            --description 'AmiJa - cycle-exact Amiga emulator' `
            --copyright 'Copyright (C) 2026 zolfrik - GPL-3.0-or-later' `
            --java-options '--enable-native-access=ALL-UNNAMED' `
            --java-options '-Damija.home=$ROOTDIR' `
            --java-options '-XX:MaxInlineSize=100' `
            --java-options '-XX:FreqInlineSize=500' `
            --java-options '-XX:InlineSmallCode=3000' `
            --java-options '-XX:MaxInlineLevel=15' `
            --dest $Stage
if ($LASTEXITCODE -ne 0) { throw "jpackage fallito." }

# ---------------------------------------------------------------------------
# 4) Contenuti letti dalla home, accanto al launcher
# ---------------------------------------------------------------------------
$Dist = Join-Path $Root 'target\dist'
$Pkg  = Join-Path $Dist $PkgName
if (Test-Path $Pkg) { Remove-Item -Recurse -Force $Pkg }
New-Item -ItemType Directory -Path $Pkg -Force | Out-Null

# Il contenuto di stage\AmiJa (AmiJa.exe, app\, runtime\) va alla RADICE
# dell'archivio: l'utente scompatta e trova subito l'eseguibile.
Get-ChildItem (Join-Path $Stage 'AmiJa') -Force | Move-Item -Destination $Pkg

Write-Host "-- copia di shaders/, blurs/, floppysounds/, segnaposto e documenti"
Copy-Item (Join-Path $Root 'shaders')      $Pkg -Recurse
Copy-Item (Join-Path $Root 'blurs')        $Pkg -Recurse
# floppysounds/ va per intero: i campioni sono materiale del progetto.
Copy-Item (Join-Path $Root 'floppysounds') $Pkg -Recurse
# Di whdload/ va SOLO il readme.txt: se un giorno contenesse i file veri (materiale
# di terze parti non ridistribuibile) l'archivio continuerebbe a non spedirli.
# Stessa cautela di src/assembly/dist.xml.
New-Item -ItemType Directory -Path (Join-Path $Pkg 'whdload') | Out-Null
Copy-Item (Join-Path $Root 'whdload\readme.txt') (Join-Path $Pkg 'whdload')
foreach ($f in @('LICENSE', 'README.md', 'THIRD-PARTY-NOTICES.md', 'USER_MANUAL.txt')) {
    Copy-Item (Join-Path $Root $f) $Pkg
}
# Il logo che README.md mostra in testa: stesso percorso relativo che ha nel
# repository, cosi' l'<img> funziona sia su GitHub sia dall'archivio scompattato.
New-Item -ItemType Directory -Path (Join-Path $Pkg 'logo') | Out-Null
Copy-Item (Join-Path $Root 'logo\amija-256.png') (Join-Path $Pkg 'logo')

# ---------------------------------------------------------------------------
# 5) Smoke test: la home non deve dipendere dalla working directory
# ---------------------------------------------------------------------------
if ($SmokeTest) {
    Write-Host "-- smoke test" -ForegroundColor Cyan
    $Exe = Join-Path $Pkg 'AmiJa.exe'
    $Cfg = Join-Path $Pkg 'amiga.properties'
    if (Test-Path $Cfg) { Remove-Item -Force $Cfg }

    # Lanciato da una cartella TERZA: se la home fosse la CWD, amiga.properties
    # nascerebbe li' dentro e il test fallirebbe.
    $Away = Join-Path $Root 'target\smoke-cwd'
    if (Test-Path $Away) { Remove-Item -Recurse -Force $Away }
    New-Item -ItemType Directory -Path $Away | Out-Null

    $proc = Start-Process -FilePath $Exe -WorkingDirectory $Away -PassThru
    Start-Sleep -Seconds 12
    $alive = -not $proc.HasExited
    $born  = Test-Path $Cfg
    $strayCfg = Test-Path (Join-Path $Away 'amiga.properties')
    if ($alive) { Stop-Process -Id $proc.Id -Force; Start-Sleep -Seconds 1 }

    Write-Host "   processo vivo dopo 12 s : $alive"
    Write-Host "   amiga.properties accanto all'exe : $born"
    Write-Host "   amiga.properties nella CWD (deve essere False) : $strayCfg"
    if (-not $alive)  { throw "SMOKE TEST FALLITO: il processo e' morto all'avvio." }
    if (-not $born)   { throw "SMOKE TEST FALLITO: amiga.properties non e' nato accanto al launcher." }
    if ($strayCfg)    { throw "SMOKE TEST FALLITO: amiga.properties e' finito nella working directory." }
    Remove-Item -Recurse -Force $Away
    Remove-Item -Force $Cfg
    Write-Host "   OK" -ForegroundColor Green
}

# ---------------------------------------------------------------------------
# 6) Archivio
# ---------------------------------------------------------------------------
$Zip = Join-Path $Dist "$PkgName.zip"
if (Test-Path $Zip) { Remove-Item -Force $Zip }
Write-Host "-- zip"
# Il tar di Windows (bsdtar) e non Compress-Archive: quello di Windows PowerShell 5.1
# scrive i percorsi interni con '\', fuori standard zip. Percorso esplicito, perche'
# sul PATH puo' esserci il tar GNU di Git, che lo zip non lo sa fare.
& (Join-Path $env:SystemRoot 'System32\tar.exe') -a -c -f $Zip -C $Dist $PkgName
if ($LASTEXITCODE -ne 0) { throw "tar fallito (exit $LASTEXITCODE)" }

$sizeMb = [math]::Round((Get-Item $Zip).Length / 1MB, 1)
Write-Host "== fatto: $Zip ($sizeMb MB)" -ForegroundColor Green

} finally {
    Pop-Location
}
