@echo off
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot
set ANDROID_HOME=C:\Users\Nick and Claudia\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d "C:\Users\Nick and Claudia\source\repos\voice-command-center\voice-command-keyboard"

echo Building debug APK...
echo Start time: %date% %time%

"%TEMP%\gradle-8.2\gradle-8.2\bin\gradle.bat" assembleDebug --no-daemon 2>&1

echo.
echo Exit code: %ERRORLEVEL%
echo End time: %date% %time%

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo SUCCESS: APK built at app\build\outputs\apk\debug\app-debug.apk
    copy "app\build\outputs\apk\debug\app-debug.apk" "%USERPROFILE%\Desktop\VoiceCommandKeyboard-debug.apk"
    echo Copied to Desktop as VoiceCommandKeyboard-debug.apk
) else (
    echo FAILED: APK not found
)
pause
