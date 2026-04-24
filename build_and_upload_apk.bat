@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "RCLONE_FALLBACK_EXE=C:\Users\Roland\Downloads\rclone\rclone.exe"
set "RCLONE_EXE="
set "RCLONE_REMOTE=datawiseguysllc-gdrive:"
set "RELEASE_APK_DIR=app\build\outputs\apk\release"
set "UPLOAD_APK=%RELEASE_APK_DIR%\ledger-lite-release.apk"
set "CURRENT_BRANCH="

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
    echo Release signing fallback did not produce an installable APK.
)
if not defined SOURCE_APK (
    echo Release APK was not produced.
    exit /b 1
)

del /q "%UPLOAD_APK%" 2>nul
copy /y "%SOURCE_APK%" "%UPLOAD_APK%" >nul || exit /b 1

echo Pushing to Git...
for /f "delims=" %%I in ('git branch --show-current') do (
    if not defined CURRENT_BRANCH set "CURRENT_BRANCH=%%I"
)
if not defined CURRENT_BRANCH (
    echo Could not determine the current git branch.
    exit /b 1
)

git add .
git reset HEAD -- .gradle-user-home >nul 2>nul
git diff --cached --quiet || git commit -m "Auto-build: updated APK"
git push -u origin "%CURRENT_BRANCH%" || exit /b 1

for /f "delims=" %%I in ('where rclone 2^>nul') do (
    if not defined RCLONE_EXE set "RCLONE_EXE=%%I"
)

if not defined RCLONE_EXE if exist "%RCLONE_FALLBACK_EXE%" (
    set "RCLONE_EXE=%RCLONE_FALLBACK_EXE%"
)

if not defined RCLONE_EXE (
    echo rclone was not found on PATH or at %RCLONE_FALLBACK_EXE%.
    echo Install rclone, add it to PATH, or update RCLONE_FALLBACK_EXE in this script.
    exit /b 1
)

echo Using rclone at %RCLONE_EXE%.
"%RCLONE_EXE%" listremotes | findstr /b /c:"%RCLONE_REMOTE%" >nul
if errorlevel 1 (
    echo rclone remote %RCLONE_REMOTE% was not found.
    echo Run "%RCLONE_EXE%" config and confirm the remote exists before uploading.
    exit /b 1
)

echo Uploading %UPLOAD_APK% to Google Drive root as ledger-lite-release.apk...
"%RCLONE_EXE%" copyto "%UPLOAD_APK%" "%RCLONE_REMOTE%/ledger-lite-release.apk" || exit /b 1

echo Done!
