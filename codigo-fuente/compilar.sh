#!/bin/bash
# Compila el Catalogador y genera Catalogador.jar (solo necesita el JDK 11 o superior).
cd "$(dirname "$0")" || exit 1
rm -rf build && mkdir -p build/clases
# Main y, a partir de él, todas las clases que usa (-sourcepath): no hace falta una lista de ficheros
javac --release 11 -encoding UTF-8 -d build/clases -sourcepath src/main/java src/main/java/catalogador/Main.java || exit 1
printf 'Main-Class: catalogador.Main\nImplementation-Title: Catalogador de inventario\nImplementation-Version: 3.1\n' > build/MANIFEST.MF
jar cfm Catalogador.jar build/MANIFEST.MF -C build/clases . && rm -rf build
echo "Listo: Catalogador.jar  (ejecutar con: java -jar Catalogador.jar)"
