#!/bin/bash
# Abre el Catalogador en Linux. Necesita Java 11 o superior
# (Ubuntu/Debian: sudo apt install openjdk-21-jre · Fedora: sudo dnf install java-21-openjdk).
# Reserva hasta el 60 % de la memoria para inventarios de cientos de miles de filas.
DIR="$(cd "$(dirname "$0")" && pwd)"

aviso() {
    if command -v zenity >/dev/null 2>&1; then zenity --error --title=Catalogador --text="$1"
    elif command -v kdialog >/dev/null 2>&1; then kdialog --error "$1" --title Catalogador
    else echo "$1"; fi
}

# Versión principal de un java («1.8.0_402» → 8; «21.0.3» → 21)
version() {
    "$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | awk -F. '{ if ($1 == "1") print $2; else print $1 }'
}

JAVA=""
for j in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" /usr/lib/jvm/*/bin/java; do
    [ -n "$j" ] && [ -x "$j" ] || continue
    v="$(version "$j")"
    if [ -n "$v" ] && [ "$v" -ge 11 ] 2>/dev/null; then JAVA="$j"; break; fi
done
if [ -z "$JAVA" ]; then
    aviso "No se ha encontrado Java 11 o superior. Instálalo (por ejemplo: sudo apt install openjdk-21-jre) y vuelve a abrir el Catalogador."
    exit 1
fi
exec "$JAVA" -XX:MaxRAMPercentage=60 -jar "$DIR/Catalogador.jar" "$@"
