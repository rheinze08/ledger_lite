@echo off

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d C:\Users\Roland\repos\voice_ledger_lite

echo Stopping Gradle daemons...
call gradlew.bat --stop

echo Pulling from remote main...
git pull origin main || exit /b 1

echo Building APK...
del /q "app\build\outputs\apk\release\*.apk" 2>nul
call gradlew.bat assembleRelease --no-daemon || exit /b 1

if not exist "app\build\outputs\apk\release\app-release.apk" (
    echo Signed release APK was not produced.
    echo Configure RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD before uploading.
    exit /b 1
)

echo Pushing to Git...
git add .
git reset HEAD -- .gradle-user-home >nul 2>nul
git diff --cached --quiet || git commit -m "Auto-build: updated APK"
git push origin main || exit /b 1

echo Uploading APK to Google Drive root...
"C:\Users\Roland\Downloads\rclone\rclone.exe" copy "app\build\outputs\apk\release" "datawiseguysllc-gdrive:/" --include "*.apk" || exit /b 1

echo Done!