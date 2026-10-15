@echo off
rem Compila el Catalogador y genera Catalogador.jar (solo necesita el JDK 11 o superior).
rem Compila Main y, a partir de el, todas las clases que usa (-sourcepath): funciona aunque
rem la ruta de la carpeta tenga espacios.
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\clases
javac --release 11 -encoding UTF-8 -d build\clases -sourcepath src\main\java src\main\java\catalogador\Main.java
if errorlevel 1 goto fallo
> build\MANIFEST.MF echo Main-Class: catalogador.Main
>> build\MANIFEST.MF echo Implementation-Title: Catalogador de inventario
>> build\MANIFEST.MF echo Implementation-Version: 3.1
jar cfm Catalogador.jar build\MANIFEST.MF -C build\clases .
if errorlevel 1 goto fallo
rmdir /s /q build
echo Listo: Catalogador.jar - ejecutar con: java -jar Catalogador.jar
exit /b 0
:fallo
echo No se ha podido compilar. Hace falta el JDK 11 o superior (javac y jar en el PATH).
exit /b 1
