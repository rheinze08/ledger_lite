@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "RCLONE_EXE=C:\Users\Roland\Downloads\rclone\rclone.exe"
set "RELEASE_APK_DIR=app\build\outputs\apk\release"
set "UPLOAD_APK=%RELEASE_APK_DIR%\ledger-lite-release.apk"

cd /d C:\Users\Roland\repos\ledger_lite

echo Stopping Gradle daemons...
call gradlew.bat --stop

echo Pulling from remote main...
git pull origin main || exit /b 1

echo Building APK...
del /q "%RELEASE_APK_DIR%\*.apk" 2>nul
call gradlew.bat assembleRelease --no-daemon || exit /b 1

set "SOURCE_APK="
if exist "%RELEASE_APK_DIR%\app-release.apk" (
    set "SOURCE_APK=%RELEASE_APK_DIR%\app-release.apk"
    echo Found signed release APK.
)
if not defined SOURCE_APK if exist "%RELEASE_APK_DIR%\app-release-unsigned.apk" (
    set "SOURCE_APK=%RELEASE_APK_DIR%\app-release-unsigned.apk"
    echo Signed release APK was not produced.
    echo Uploading unsigned release APK instead.
)
if not defined SOURCE_APK (
    echo Release APK was not produced.
    exit /b 1
)

copy /y "%SOURCE_APK%" "%UPLOAD_APK%" >nul || exit /b 1

echo Pushing to Git...
git add .
git reset HEAD -- .gradle-user-home >nul 2>nul
git diff --cached --quiet || git commit -m "Auto-build: updated APK"
git push origin main || exit /b 1

if not exist "%RCLONE_EXE%" (
    echo rclone was not found at %RCLONE_EXE%.
    exit /b 1
)

echo Uploading %UPLOAD_APK% to Google Drive root as ledger-lite-release.apk...
"%RCLONE_EXE%" copyto "%UPLOAD_APK%" "datawiseguysllc-gdrive:/ledger-lite-release.apk" || exit /b 1

echo Done!
