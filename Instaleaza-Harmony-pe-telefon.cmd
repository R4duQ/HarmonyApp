@echo off
setlocal EnableExtensions EnableDelayedExpansion
title Harmony - Build si instalare pe telefon

rem Poti porni scriptul direct. Implicit foloseste D:\Harmony.
rem Optional, poti transmite alta cale ca primul argument:
rem Instaleaza-Harmony-pe-telefon.cmd "D:\AltaCale\Harmony"
set "PROJECT_DIR=D:\Harmony"
if exist "%~dp0settings.gradle.kts" (
    for %%D in ("%~dp0.") do set "PROJECT_DIR=%%~fD"
)
if not "%~1"=="" set "PROJECT_DIR=%~1"

set "APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk"

echo.
echo ============================================================
echo       HARMONY - BUILD SI INSTALARE DIRECT PE TELEFON
echo ============================================================
echo.

if not exist "%PROJECT_DIR%\settings.gradle.kts" (
    for /d %%D in ("%PROJECT_DIR%\Harmony*") do (
        if not exist "!PROJECT_DIR!\settings.gradle.kts" if exist "%%~fD\settings.gradle.kts" (
            set "PROJECT_DIR=%%~fD"
            set "APK=%%~fD\app\build\outputs\apk\debug\app-debug.apk"
        )
    )
)

if not exist "!PROJECT_DIR!\settings.gradle.kts" (
    echo Proiectul Harmony nu a fost gasit in:
    echo   !PROJECT_DIR!
    echo.
    set /p "PROJECT_DIR=Scrie calea completa a proiectului Harmony: "
    set "APK=!PROJECT_DIR!\app\build\outputs\apk\debug\app-debug.apk"
)

if not exist "!PROJECT_DIR!\settings.gradle.kts" (
    echo.
    echo EROARE: folderul ales nu contine settings.gradle.kts.
    goto :failed
)

rem Gaseste automat Android SDK / adb.exe.
set "ADB="
if defined ANDROID_SDK_ROOT if exist "!ANDROID_SDK_ROOT!\platform-tools\adb.exe" set "ADB=!ANDROID_SDK_ROOT!\platform-tools\adb.exe"
if not defined ADB if defined ANDROID_HOME if exist "!ANDROID_HOME!\platform-tools\adb.exe" set "ADB=!ANDROID_HOME!\platform-tools\adb.exe"
if not defined ADB if exist "D:\Android\platform-tools\adb.exe" set "ADB=D:\Android\platform-tools\adb.exe"
if not defined ADB if exist "D:\Android\Sdk\platform-tools\adb.exe" set "ADB=D:\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set "ADB=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if not defined ADB (
    for /f "delims=" %%A in ('where adb 2^>nul') do (
        if not defined ADB set "ADB=%%A"
    )
)
if not defined ADB if exist "D:\Android" (
    for /f "delims=" %%A in ('where /r "D:\Android" adb.exe 2^>nul') do (
        if not defined ADB set "ADB=%%A"
    )
)
if not defined ADB (
    echo Android SDK nu a fost gasit automat.
    echo Poti copia calea din Android Studio - Settings - Android SDK.
    echo.
    set /p "ADB_INPUT=Scrie calea catre SDK sau catre adb.exe: "
    set "ADB_INPUT=!ADB_INPUT:"=!"
    if exist "!ADB_INPUT!\platform-tools\adb.exe" set "ADB=!ADB_INPUT!\platform-tools\adb.exe"
    if not defined ADB if exist "!ADB_INPUT!\adb.exe" set "ADB=!ADB_INPUT!\adb.exe"
    if not defined ADB if exist "!ADB_INPUT!" set "ADB=!ADB_INPUT!"
)

if not defined ADB (
    echo EROARE: nu a fost aleasa nicio cale pentru adb.exe.
    goto :failed
)

if not exist "!ADB!" (
    echo EROARE: adb.exe nu a fost gasit aici:
    echo   !ADB!
    echo.
    echo Verifica Android Studio - Settings - Android SDK.
    goto :failed
)

for %%A in ("!ADB!") do set "PLATFORM_TOOLS_DIR=%%~dpA"
for %%A in ("!PLATFORM_TOOLS_DIR!..") do set "ANDROID_SDK_ROOT=%%~fA"
set "ANDROID_HOME=!ANDROID_SDK_ROOT!"

echo Android SDK gasit:
echo   !ANDROID_SDK_ROOT!
echo.

if not exist "!PROJECT_DIR!\gradle\wrapper\gradle-wrapper.jar" (
    echo EROARE: lipseste gradle\wrapper\gradle-wrapper.jar.
    goto :failed
)

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE (
    for /f "delims=" %%J in ('where java 2^>nul') do (
        if not defined JAVA_EXE set "JAVA_EXE=%%J"
    )
)

if not defined JAVA_EXE (
    echo EROARE: Java nu a fost gasit.
    echo Instaleaza Android Studio sau seteaza variabila JAVA_HOME.
    goto :failed
)

echo [1/4] Verific telefonul...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo.
    echo Telefonul nu este pregatit pentru ADB.
    echo 1. Conecteaza telefonul prin USB.
    echo 2. Activeaza Developer options si USB debugging.
    echo 3. Deblocheaza telefonul si accepta mesajul Allow USB debugging.
    echo 4. Ruleaza din nou acest fisier.
    goto :failed
)

echo.
echo [2/4] Construiesc APK-ul de test pentru arm64-v8a...
pushd "!PROJECT_DIR!" >nul

if exist "gradlew.bat" (
    call gradlew.bat :app:assembleDebug -PharmonyAbi=arm64-v8a --refresh-dependencies --no-daemon
) else (
    "!JAVA_EXE!" -Dorg.gradle.appname=gradlew -classpath "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain :app:assembleDebug -PharmonyAbi=arm64-v8a --refresh-dependencies --no-daemon
)

if errorlevel 1 (
    popd >nul
    echo.
    echo EROARE: build-ul APK a esuat. Citeste eroarea Gradle de mai sus.
    goto :failed
)
popd >nul

if not exist "!APK!" (
    echo.
    echo EROARE: build-ul s-a terminat, dar APK-ul nu a fost gasit aici:
    echo   !APK!
    goto :failed
)

echo.
echo [3/4] Instalez Harmony pe telefon si pastrez datele existente...
"%ADB%" install -r -d "!APK!"
if errorlevel 1 (
    echo.
    echo Instalarea a esuat. Aplicatia existenta NU a fost stearsa.
    echo Daca apare INSTALL_FAILED_UPDATE_INCOMPATIBLE, APK-ul vechi si cel
    echo nou sunt semnate cu chei diferite. Nu dezinstala pana nu salvezi
    echo datele importante.
    goto :failed
)

echo.
echo [4/4] Pornesc Harmony...
"%ADB%" shell monkey -p com.harmony.app -c android.intent.category.LAUNCHER 1 >nul 2>&1

echo.
echo ============================================================
echo GATA: Harmony a fost instalat direct pe telefon.
echo APK folosit:
echo   !APK!
echo ============================================================
echo.
pause
exit /b 0

:failed
echo.
echo Operatia s-a oprit fara sa dezinstaleze aplicatia de pe telefon.
echo.
pause
exit /b 1
