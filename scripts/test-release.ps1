param([string]$Serial = 'emulator-5590')
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator.' }
$root = Split-Path $PSScriptRoot
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Target is not an emulator.' }
$apk = Join-Path $root 'app/build/outputs/apk/release/DomainGuard-release.apk'
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Release installation failed.' }
& $adb -s $Serial shell am force-stop heartguard.heartguard.main.mw
# Only the dedicated disposable emulator's crash buffer is cleared.
& $adb -s $Serial logcat -b crash -c
& $adb -s $Serial shell am start -W -n heartguard.heartguard.main.mw/heartguard.heartguard.main.ActivityMain
if ($LASTEXITCODE -ne 0) { throw 'Release launch failed.' }
for ($attempt = 0; $attempt -lt 5; $attempt++) {
    Start-Sleep -Seconds 1
    $crash = & $adb -s $Serial logcat -d -b crash
    if (($crash -join "`n") -match 'Process: heartguard.heartguard.main.mw') {
        $crash | Set-Content (Join-Path $root '.gradle/verification/release-crash.log')
        throw 'The release crashed; see release-crash.log.'
    }
}
$activity = & $adb -s $Serial shell dumpsys activity activities
if (-not ($activity | Select-String 'mResumedActivity:.*heartguard.heartguard.main.mw/')) {
    throw 'DomainGuard is not the resumed activity.'
}
Write-Output 'Minified release launched and remained active without a crash.'
