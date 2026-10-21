param([string]$Serial = 'emulator-5590')
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator.' }
$root = Split-Path $PSScriptRoot
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Target is not an emulator.' }
$verification = Join-Path $root '.gradle/verification/updater'
New-Item -ItemType Directory -Force -Path $verification | Out-Null
$releaseApk = Join-Path $root 'app/build/outputs/apk/release/DomainGuard-release.apk'
if (-not (Test-Path -LiteralPath $releaseApk)) { throw 'Build the signed release APK first.' }
Copy-Item -LiteralPath $releaseApk -Destination (Join-Path $verification 'release.apk')
# This override is local to the test build; the release/source version stays unchanged.
@'
gradle.beforeProject { project ->
    if (project.path == ':app') {
        project.afterEvaluate {
            def android = project.extensions.getByName('android')
            android.defaultConfig.versionName = '2.317-mw.1'
            android.defaultConfig.versionCode = 2025031701
        }
    }
}
'@ | Set-Content -LiteralPath (Join-Path $verification 'bootstrap.init.gradle')
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $root
try {
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' -Djdk.net.unixdomain.tmpdir="' + (Join-Path $root '.gradle/sockets') + '"').Trim()
    & ./gradlew.bat --init-script (Join-Path $verification 'bootstrap.init.gradle') :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Updater fixture build failed.' }
    & $adb -s $Serial install -r -d app/build/outputs/apk/debug/DomainGuard-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not install the older-version test fixture.' }
    & $adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/DomainGuard-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not install updater tests.' }
    & $adb -s $Serial push (Join-Path $verification 'release.apk') /data/local/tmp/heartguard-mw-update.apk
    if ($LASTEXITCODE -ne 0) { throw 'Could not stage the release APK.' }
    $result = & $adb -s $Serial shell am instrument -w -e class heartguard.heartguard.main.UpdateInstallDeviceTest -e updateApkPath /data/local/tmp/heartguard-mw-update.apk heartguard.heartguard.main.mw.test/androidx.test.runner.AndroidJUnitRunner
    $result
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(5 tests\)') { throw 'Updater device tests failed.' }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
