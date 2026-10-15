#!/usr/bin/env bash
#
# Costruisce la distribuzione nativa di AmiJa per Linux (app-image portatile).
#
# Produce una cartella autoconsistente — launcher nativo + runtime Java ridotto +
# tutto quello che l'emulatore legge dalla propria home — e la comprime in
#   target/dist/AmiJa-<versione>-<os>-<arch>.tar.gz
# (tar.gz e non zip: conserva il bit di esecuzione del launcher).
#
# L'utente scompatta e lancia: nessun JDK da installare.
#
# Passi: mvn package -> jlink -> jpackage -> copia dei contenuti -> tar.gz.
# Serve un JDK 26 con jlink e jpackage nel PATH (o in JAVA_HOME).
#
# Il gemello per Windows e' build-image.ps1: i due file fanno le stesse cose e
# vanno tenuti allineati.
#
# Uso:
#   packaging/build-image.sh [--version X.Y.Z] [--skip-build] [--smoke-test]
#
#   --smoke-test  dopo il packaging lancia l'immagine da una cartella TERZA e
#                 verifica che il processo regga e che amiga.properties nasca
#                 ACCANTO al launcher: e' la prova che la home non dipende dalla
#                 working directory. Su Linux va invocato dentro xvfb-run.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION=""
SKIP_BUILD=0
SMOKE_TEST=0
while [ $# -gt 0 ]; do
    case "$1" in
        --version)    VERSION="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        --smoke-test) SMOKE_TEST=1; shift ;;
        *) echo "Opzione sconosciuta: $1" >&2; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# Strumenti
# ---------------------------------------------------------------------------
tool() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1" ]; then
        echo "$JAVA_HOME/bin/$1"
    elif command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    else
        echo "$1 non trovato: serve un JDK 26 in JAVA_HOME o nel PATH." >&2
        exit 1
    fi
}
JLINK="$(tool jlink)"
JPACKAGE="$(tool jpackage)"

# ---------------------------------------------------------------------------
# Versione, sistema, architettura
# ---------------------------------------------------------------------------
if [ -z "$VERSION" ]; then
    VERSION="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout)"
fi
# jpackage vuole MAJOR.MINOR.BUILD: un'eventuale coda tipo "-SNAPSHOT" va tolta
# (resta pero' nel nome dell'archivio).
APP_VERSION="${VERSION%%-*}"

case "$(uname -s)" in
    Linux)  OS_NAME="linux" ;;
    *) echo "Sistema non gestito: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64)  ARCH="x64" ;;
    aarch64|arm64) ARCH="arm64" ;;
    *) echo "Architettura non gestita: $(uname -m)" >&2; exit 1 ;;
esac
TARGET="$OS_NAME-$ARCH"
PKG_NAME="AmiJa-$VERSION-$TARGET"

echo "== AmiJa $VERSION -> $TARGET (app-version $APP_VERSION)"

# ---------------------------------------------------------------------------
# 1) Fat jar
# ---------------------------------------------------------------------------
if [ "$SKIP_BUILD" -eq 1 ]; then
    echo "-- mvn package saltato (--skip-build)"
else
    echo "-- mvn package"
    mvn -B clean package
fi
JAR="$ROOT/target/AmiJa.jar"
[ -f "$JAR" ] || { echo "Manca $JAR." >&2; exit 1; }

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
MODULES="java.base,java.desktop,java.logging,java.net.http,jdk.unsupported,jdk.crypto.ec,jdk.zipfs"
JRE="$ROOT/target/jre"
rm -rf "$JRE"
echo "-- jlink ($MODULES)"
"$JLINK" --add-modules "$MODULES" \
         --strip-debug --no-header-files --no-man-pages --compress zip-6 \
         --output "$JRE"

# ---------------------------------------------------------------------------
# 3) App-image nativa
# ---------------------------------------------------------------------------
INPUT="$ROOT/target/dist-input"
STAGE="$ROOT/target/stage"
rm -rf "$INPUT" "$STAGE"
mkdir -p "$INPUT" "$STAGE"
cp "$JAR" "$INPUT/"

ICON="$ROOT/src/main/resources/icons/amija-256.png"
HOME_OPTION='-Damija.home=$ROOTDIR'

echo "-- jpackage --type app-image"
# NB: HOME_OPTION e' fra apici SINGOLI di proposito. $ROOTDIR non e' una variabile di
# shell: e' il segnaposto che il launcher jpackage sostituisce A RUNTIME con la radice
# dell'immagine. E' cio' che rende la home indipendente dalla working directory.
#
# Le quattro -XX:*Inline* sono taratura del JIT: cambiano solo come HotSpot genera il
# codice, non il comportamento emulato. Valgono -2% di wall-time (misurate col banco
# tools/ab-bench, due sessioni, 6/6 coppie concordi ciascuna). ATTENZIONE: l'ottimo NON
# e' monotono — raddoppiarle misura +3,8%, cioe' un rallentamento. Vanno tenute allineate
# con Amija.JIT_OPTIONS, che le ripassa quando la JVM si rilancia da sola.
# Metodo e numeri: docs/performance-jit-plan-2026-09-25.md (tree privato).
"$JPACKAGE" --type app-image --name AmiJa --app-version "$APP_VERSION" \
            --input "$INPUT" --main-jar AmiJa.jar \
            --runtime-image "$JRE" \
            --icon "$ICON" \
            --vendor 'zolfrik' \
            --description 'AmiJa - cycle-exact Amiga emulator' \
            --copyright 'Copyright (C) 2026 zolfrik - GPL-3.0-or-later' \
            --java-options '--enable-native-access=ALL-UNNAMED' \
            --java-options "$HOME_OPTION" \
            --java-options '-XX:MaxInlineSize=100' \
            --java-options '-XX:FreqInlineSize=500' \
            --java-options '-XX:InlineSmallCode=3000' \
            --java-options '-XX:MaxInlineLevel=15' \
            --dest "$STAGE"

# ---------------------------------------------------------------------------
# 4) Contenuti letti dalla home, accanto al launcher
# ---------------------------------------------------------------------------
DIST="$ROOT/target/dist"
PKG="$DIST/$PKG_NAME"
rm -rf "$PKG"
mkdir -p "$PKG"

# Il contenuto di stage/AmiJa (bin/, lib/) va alla RADICE dell'archivio.
mv "$STAGE/AmiJa/"* "$PKG/"
LAUNCHER="$PKG/bin/AmiJa"

echo "-- copia di shaders/, blurs/, floppysounds/, segnaposto e documenti"
cp -R "$ROOT/shaders"       "$PKG/"
cp -R "$ROOT/blurs"         "$PKG/"
# floppysounds/ va per intero: i campioni sono materiale del progetto.
cp -R "$ROOT/floppysounds"  "$PKG/"
# Di whdload/ va SOLO il readme.txt: se un giorno contenesse i file veri (materiale
# di terze parti non ridistribuibile) l'archivio continuerebbe a non spedirli.
# Stessa cautela di src/assembly/dist.xml.
mkdir -p "$PKG/whdload"
cp "$ROOT/whdload/readme.txt" "$PKG/whdload/"
for f in LICENSE README.md THIRD-PARTY-NOTICES.md USER_MANUAL.txt; do
    cp "$ROOT/$f" "$PKG/"
done
# Il logo che README.md mostra in testa: stesso percorso relativo che ha nel
# repository, cosi' l'<img> funziona sia su GitHub sia dall'archivio scompattato.
mkdir -p "$PKG/logo"
cp "$ROOT/logo/amija-256.png" "$PKG/logo/"

# ---------------------------------------------------------------------------
# 5) Smoke test: la home non deve dipendere dalla working directory
# ---------------------------------------------------------------------------
if [ "$SMOKE_TEST" -eq 1 ]; then
    echo "-- smoke test"
    CFG="$PKG/amiga.properties"
    rm -f "$CFG"
    AWAY="$ROOT/target/smoke-cwd"
    rm -rf "$AWAY"; mkdir -p "$AWAY"

    # Lanciato da una cartella TERZA: se la home fosse la CWD, amiga.properties
    # nascerebbe li' dentro e il test fallirebbe.
    # Niente subshell: cosi' $! e' il PID del launcher vero e non quello di una
    # shell intermedia, che poi lascerebbe la JVM viva a fine step.
    cd "$AWAY"
    "$LAUNCHER" >"$ROOT/target/smoke.log" 2>&1 &
    SMOKE_PID=$!
    cd "$ROOT"
    sleep 12

    ALIVE=0; kill -0 "$SMOKE_PID" 2>/dev/null && ALIVE=1
    BORN=0;  [ -f "$CFG" ] && BORN=1
    STRAY=0; [ -f "$AWAY/amiga.properties" ] && STRAY=1
    if [ "$ALIVE" -eq 1 ]; then
        kill "$SMOKE_PID" 2>/dev/null || true
        sleep 2
        kill -9 "$SMOKE_PID" 2>/dev/null || true
        wait "$SMOKE_PID" 2>/dev/null || true
    fi

    echo "   processo vivo dopo 12 s          : $ALIVE"
    echo "   amiga.properties accanto al launcher : $BORN"
    echo "   amiga.properties nella CWD (deve essere 0) : $STRAY"
    echo "   --- home risolta dall'applicazione ---"
    grep -F '[HOME]' "$ROOT/target/smoke.log" || echo "   (nessuna riga [HOME]!)"
    if [ "$ALIVE" -ne 1 ]; then
        echo "SMOKE TEST FALLITO: il processo e' morto all'avvio. Log:" >&2
        cat "$ROOT/target/smoke.log" >&2
        exit 1
    fi
    [ "$BORN"  -eq 1 ] || { echo "SMOKE TEST FALLITO: amiga.properties non e' nato accanto al launcher." >&2; exit 1; }
    [ "$STRAY" -eq 0 ] || { echo "SMOKE TEST FALLITO: amiga.properties e' finito nella working directory." >&2; exit 1; }
    rm -rf "$AWAY"; rm -f "$CFG"
    echo "   OK"
fi

# ---------------------------------------------------------------------------
# 6) Archivio
# ---------------------------------------------------------------------------
TARBALL="$DIST/$PKG_NAME.tar.gz"
rm -f "$TARBALL"
echo "-- tar.gz"
tar -czf "$TARBALL" -C "$DIST" "$PKG_NAME"

echo "== fatto: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
