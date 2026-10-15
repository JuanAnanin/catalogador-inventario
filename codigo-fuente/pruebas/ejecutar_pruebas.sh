#!/bin/bash
# Compila el programa y las pruebas y ejecuta las pruebas automáticas (JDK 11 o superior).
#   ./ejecutar_pruebas.sh            PruebaRequisitos (con el inventario de 600.000 filas) y,
#                                    si hay python3, PruebaIA con el servidor de IA simulado
#   ./ejecutar_pruebas.sh rapido     igual, sin el inventario de 600.000 filas
#   ./ejecutar_pruebas.sh rendimiento   mide cada paso con el inventario de 650.000 filas
cd "$(dirname "$0")" || exit 1
rm -rf clases && mkdir -p clases/app clases/pruebas
echo "== Compilando"
javac --release 11 -encoding UTF-8 -d clases/app -sourcepath ../src/main/java ../src/main/java/catalogador/Main.java || exit 1
javac --release 11 -encoding UTF-8 -cp clases/app -d clases/pruebas \
      src/catalogador/ui/PruebaRequisitos.java src/PruebaIA.java src/Rendimiento.java src/PrepararDatos.java || exit 1
CP="clases/app:clases/pruebas"
JOPC="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8"
EJ="../../ejemplos"
if [ "$1" = "rendimiento" ]; then
  java -Xmx4g $JOPC -cp "$CP" Rendimiento "$EJ/grandes/CATALOGO_GRANDE_GMDN_30000.xlsx" "$EJ/grandes/INVENTARIO_GRANDE_650000.xlsx" \
       "$EJ/grandes/RESULTADO_ESPERADO_GRANDE_650000.xlsx" trabajo_rendimiento
  exit $?
fi
echo "== PruebaRequisitos"
EXTRA=""; [ "$1" = "rapido" ] && EXTRA="--sin-600k"
java -Xmx3g $JOPC -cp "$CP" catalogador.ui.PruebaRequisitos "$EJ" trabajo --datos=datos $EXTRA
R1=$?
R2=0
if command -v python3 >/dev/null 2>&1; then
  echo "== PruebaIA (con el servidor de IA simulado mock_ia.py)"
  rm -f trabajo_mock.log
  python3 mock_ia.py 18080 trabajo_mock.log 0.01 >trabajo_mock.out 2>&1 &
  MOCK=$!
  sleep 1
  if kill -0 $MOCK 2>/dev/null; then
    java $JOPC -cp "$CP" PruebaIA datos/Catalogos_PRUEBA.xlsx datos/Inventario_PRUEBA_casos.xlsx 18080 trabajo_mock.log
    R2=$?
    kill $MOCK 2>/dev/null
  else
    echo "No se ha podido arrancar el servidor simulado (¿el puerto 18080 está ocupado?):"; cat trabajo_mock.out
    R2=1
  fi
else
  echo "(PruebaIA no se ejecuta: hace falta python3 para el servidor de IA simulado)"
fi
[ $R1 -eq 0 ] && [ $R2 -eq 0 ] && echo "== TODO CORRECTO" || echo "== HAY FALLOS"
exit $(( R1 + R2 ))
