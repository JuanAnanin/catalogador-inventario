@echo off
rem Abre el Catalogador en Windows. Necesita Java 11 o superior (https://adoptium.net).
rem Busca Java en JAVA_HOME, en las carpetas de instalacion habituales y en el PATH,
rem y descarta las versiones antiguas (Java 8 o anterior), habituales en los hospitales.
rem Reserva hasta el 60 por ciento de la memoria (inventarios de cientos de miles de filas).
setlocal EnableExtensions
set "JAR=%~dp0Catalogador.jar"
set "JAVAW="
if exist "%JAR%" goto buscar
echo.
echo  No se encuentra Catalogador.jar junto a este fichero.
echo  Si lo has abierto desde dentro del .zip, descomprimelo primero
echo  (clic derecho, Extraer todo) y vuelve a hacer doble clic aqui.
echo.
pause
exit /b 1

:buscar
if defined JAVA_HOME call :probar "%JAVA_HOME%\bin"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*" "%ProgramFiles%\Java\*" "%ProgramFiles%\Microsoft\jdk-*" "%ProgramFiles%\Zulu\*" "%ProgramFiles%\Amazon Corretto\*" "%ProgramFiles%\BellSoft\*" "%LOCALAPPDATA%\Programs\Eclipse Adoptium\*" "%USERPROFILE%\.jdks\*") do if not defined JAVAW call :probar "%%~D\bin"
if not defined JAVAW for %%J in (java.exe) do if not "%%~$PATH:J"=="" call :probar "%%~dp$PATH:J"
if defined JAVAW goto abrir
echo.
echo  No se ha encontrado Java 11 o superior en este ordenador.
echo  Se abre la pagina de descarga: instala "Temurin" (version LTS, Windows x64, .msi)
echo  y vuelve a hacer doble clic en este fichero.
echo.
start "" "https://adoptium.net/es/temurin/releases/?os=windows"
pause
exit /b 1

:abrir
start "" "%JAVAW%" -XX:MaxRAMPercentage=60 -jar "%JAR%" %*
exit /b 0

:probar
rem %1 = carpeta bin de un Java. Vale si tiene javaw.exe y no es Java 10 o anterior.
set "B=%~1"
if "%B:~-1%"=="\" set "B=%B:~0,-1%"
if not exist "%B%\javaw.exe" goto :eof
if not exist "%B%\java.exe" goto valido
"%B%\java.exe" -version 2>&1 | findstr /r /c:"version .1\.[0-8]" /c:"version .9[^0-9]" /c:"version .10[^0-9]" >nul
if not errorlevel 1 goto :eof
:valido
set "JAVAW=%B%\javaw.exe"
goto :eof
