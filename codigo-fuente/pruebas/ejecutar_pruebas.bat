@echo off
rem Compila el programa y las pruebas y ejecuta las pruebas automaticas (JDK 11 o superior).
rem   ejecutar_pruebas.bat              PruebaRequisitos (con el inventario de 600.000 filas)
rem   ejecutar_pruebas.bat rapido       igual, sin el inventario de 600.000 filas
rem   ejecutar_pruebas.bat ia           PruebaIA: antes, en otra ventana, arranca el servidor
rem                                     simulado con:  python mock_ia.py 18080 trabajo_mock.log
rem   ejecutar_pruebas.bat rendimiento  mide cada paso con el inventario de 650.000 filas
setlocal
cd /d "%~dp0"
chcp 65001 >nul
set "JOPC=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8"
if exist clases rmdir /s /q clases
mkdir clases\app
mkdir clases\pruebas
echo == Compilando
javac --release 11 -encoding UTF-8 -d clases\app -sourcepath ..\src\main\java ..\src\main\java\catalogador\Main.java
if errorlevel 1 goto fallo
javac --release 11 -encoding UTF-8 -cp clases\app -d clases\pruebas src\catalogador\ui\PruebaRequisitos.java src\PruebaIA.java src\Rendimiento.java src\PrepararDatos.java
if errorlevel 1 goto fallo
set "CP=clases\app;clases\pruebas"
set "EJ=..\..\ejemplos"
if /i "%~1"=="ia" goto ia
if /i "%~1"=="rendimiento" goto rendimiento
set "EXTRA="
if /i "%~1"=="rapido" set "EXTRA=--sin-600k"
echo == PruebaRequisitos
java -Xmx3g %JOPC% -cp "%CP%" catalogador.ui.PruebaRequisitos "%EJ%" trabajo --datos=datos %EXTRA%
goto fin
:ia
echo == PruebaIA (el servidor simulado debe estar funcionando en el puerto 18080)
java %JOPC% -cp "%CP%" PruebaIA datos\Catalogos_PRUEBA.xlsx datos\Inventario_PRUEBA_casos.xlsx 18080 trabajo_mock.log
goto fin
:rendimiento
java -Xmx4g %JOPC% -cp "%CP%" Rendimiento "%EJ%\grandes\CATALOGO_GRANDE_GMDN_30000.xlsx" "%EJ%\grandes\INVENTARIO_GRANDE_650000.xlsx" "%EJ%\grandes\RESULTADO_ESPERADO_GRANDE_650000.xlsx" trabajo_rendimiento
goto fin
:fallo
echo No se ha podido compilar. Hace falta el JDK 11 o superior (javac en el PATH).
:fin
pause
