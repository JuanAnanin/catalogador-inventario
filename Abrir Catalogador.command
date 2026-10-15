#!/bin/bash
# Lanzador del Catalogador para macOS: busca un Java 11+ instalado y abre el programa.
JAR="$(cd "$(dirname "$0")" && pwd)/Catalogador.jar"

# Versión principal de un java («1.8.0_402» → 8; «21.0.3» → 21)
version() {
    "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

valido() {
    [ -n "$1" ] && [ -x "$1" ] || return 1
    local v
    v="$(version "$1")"
    [ -n "$v" ] && [ "$v" -ge 11 ] 2>/dev/null
}

buscar_java() {
    local c
    if valido "$JAVA_HOME/bin/java"; then echo "$JAVA_HOME/bin/java"; return; fi
    c="$(/usr/libexec/java_home -v 11+ 2>/dev/null)/bin/java"
    if valido "$c"; then echo "$c"; return; fi
    for c in /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
             "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
             "$HOME"/.jdk/*/Contents/Home/bin/java "$HOME"/.jdk/*/bin/java "$HOME"/.sdkman/candidates/java/*/bin/java \
             /opt/homebrew/opt/openjdk*/bin/java /usr/local/opt/openjdk*/bin/java; do
        if valido "$c"; then echo "$c"; return; fi
    done
    c="$(command -v java 2>/dev/null)"
    if valido "$c"; then echo "$c"; fi
}

JAVA="$(buscar_java)"
if [ -z "$JAVA" ]; then
    osascript -e 'display alert "Catalogador" message "No se ha encontrado Java (versión 11 o superior).\n\nInstálalo (por ejemplo, Temurin desde https://adoptium.net) y vuelve a abrir el programa."'
    open "https://adoptium.net/es/temurin/releases/?os=mac"
    exit 1
fi
ERR="${TMPDIR:-/tmp}/catalogador_error.txt"
"$JAVA" -XX:MaxRAMPercentage=60 -Xdock:name=Catalogador -Dapple.awt.application.appearance=system -jar "$JAR" 2>"$ERR"
if [ $? -ne 0 ]; then
    MSG="$(tail -5 "$ERR" | tr '"' "'" )"
    osascript -e "display alert \"Catalogador no ha podido arrancar\" message \"Java usado: $JAVA\n\n$MSG\""
fi
