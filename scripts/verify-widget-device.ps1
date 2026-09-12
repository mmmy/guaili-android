param([string]$Serial = 'emulator-5554')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & python scripts/backup-widget-device.py --serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'Device backup failed; installation was not started.' }
    # Build only. connectedDebugAndroidTest may uninstall the app and erase device data.
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build or local checks failed.' }
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    $result = & adb -s $Serial shell am instrument -w -r -e class com.gouge.guaili.widget.WidgetBusinessFlowTest com.gouge.guaili.test/androidx.test.runner.AndroidJUnitRunner
    $result | Tee-Object -FilePath build/widget-device-test-result.txt
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Device tests failed; see build/widget-device-test-result.txt.' }
    # Deliberately leave both packages installed; never clear/uninstall the user's app.
} finally {
    Pop-Location
}
