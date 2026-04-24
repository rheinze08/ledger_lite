@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d C:\Users\Roland\repos\ledger_lite

if "%RELEASE_VERSION_CODE%"=="" (
    echo RELEASE_VERSION_CODE is not set.
    echo Example: set RELEASE_VERSION_CODE=2
    exit /b 1
)

if "%RELEASE_VERSION_NAME%"=="" (
    echo RELEASE_VERSION_NAME is not set.
    echo Example: set RELEASE_VERSION_NAME=0.2.0
    exit /b 1
)

echo Stopping Gradle daemons...
call gradlew.bat --stop

for /f "tokens=* delims= " %%A in ("%RELEASE_VERSION_CODE%") do set "RELEASE_VERSION_CODE=%%A"
for /f "tokens=* delims= " %%A in ("%RELEASE_VERSION_NAME%") do set "RELEASE_VERSION_NAME=%%A"

echo Building Android App Bundle...
call gradlew.bat clean :app:bundleRelease -PRELEASE_VERSION_CODE=%RELEASE_VERSION_CODE% -PRELEASE_VERSION_NAME=%RELEASE_VERSION_NAME% --no-daemon || exit /b 1

if not exist "app\build\outputs\bundle\release\app-release.aab" (
    echo Signed release bundle was not produced.
    echo Configure RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD before building.
    exit /b 1
)

set "RENAMED_BUNDLE=app\build\outputs\bundle\release\ledger-lite-%RELEASE_VERSION_NAME%-%RELEASE_VERSION_CODE%.aab"
copy /y "app\build\outputs\bundle\release\app-release.aab" "%RENAMED_BUNDLE%" >nul || exit /b 1

echo Release bundle ready:
echo %RENAMED_BUNDLE%
