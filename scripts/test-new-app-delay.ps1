param([string]$Serial = 'emulator-5590')
# Replaces DomainGuard data only on the explicitly selected disposable emulator.
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator.' }
$root = Split-Path $PSScriptRoot
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$package = 'heartguard.heartguard.main.mw'
$fixture = 'test.domainguard.installfixture'
$task = Join-Path $env:TEMP 'new-app-install-fixture'
[IO.Directory]::CreateDirectory($task) | Out-Null
function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
function Invoke-Fixture {
    param([string]$Method)
    $result = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'runInstallFixture', 'true',
        '-e', 'class', "heartguard.heartguard.main.NewAppInstallDeviceTest#$Method", "$package.test/androidx.test.runner.AndroidJUnitRunner")
    $result | & python -B -c 'import sys; sys.path.insert(0, sys.argv[1]); from release_runtime import instrumentation_passes; instrumentation_passes(sys.stdin.read(), [sys.argv[2]])' $PSScriptRoot "heartguard.heartguard.main.NewAppInstallDeviceTest#$Method"
    if ($LASTEXITCODE -ne 0) { throw $result }
    Write-Output "$Method passed."
}
if ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') { throw 'Not an emulator.' }
if ((Invoke-Adb -Arguments @('emu', 'avd', 'name')) -notmatch 'DomainGuard_Attribution_Test') { throw 'Not the disposable DomainGuard emulator.' }
$buildTools = Join-Path $env:ANDROID_HOME 'build-tools/35.0.0'
$manifest = Join-Path $task 'AndroidManifest.xml'
'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.domainguard.installfixture" android:versionCode="1" android:versionName="1"><uses-permission android:name="android.permission.INTERNET"/><application android:label="New app delay fixture" android:hasCode="false"/></manifest>' | Set-Content -LiteralPath $manifest -Encoding utf8
& "$buildTools/aapt2.exe" link --manifest $manifest -I "$env:ANDROID_HOME/platforms/android-31/android.jar" --min-sdk-version 23 --target-sdk-version 31 -o "$task/unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw 'Fixture resource link failed.' }
& "$buildTools/zipalign.exe" -f 4 "$task/unsigned.apk" "$task/fixture.apk"
if ($LASTEXITCODE -ne 0) { throw 'Fixture alignment failed.' }
if (!(Test-Path -LiteralPath "$task/test.p12")) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore "$task/test.p12" -storepass fixture-password -keypass fixture-password -alias fixture -dname 'CN=Disposable Test' -keyalg RSA -validity 30 *> "$task/keytool.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture signing key failed.' }
}
& "$buildTools/apksigner.bat" sign --ks "$task/test.p12" --ks-pass pass:fixture-password --ks-key-alias fixture "$task/fixture.apk"
if ($LASTEXITCODE -ne 0) { throw 'Fixture signing failed.' }
Invoke-Adb -Arguments @('install', '-r', "$root/app/build/outputs/apk/debug/DomainGuard-debug.apk") | Write-Output
Invoke-Adb -Arguments @('install', '-r', "$root/app/build/outputs/apk/androidTest/debug/DomainGuard-debug-androidTest.apk") | Write-Output
Invoke-Adb -Arguments @('push', "$task/fixture.apk", '/data/local/tmp/domainguard-install-fixture.apk') | Write-Output
try {
    & $adb -s $Serial uninstall $fixture 2>&1 | Out-Null
    Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package) | Write-Output
    Invoke-Fixture 'prepareStoppedInstall'
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $package) | Write-Output
    Invoke-Adb -Arguments @('install', "$task/fixture.apk") | Write-Output
    Invoke-Fixture 'verifyStoppedInstallAndReinstall'
    Invoke-Adb -Arguments @('uninstall', $fixture) | Write-Output
    Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package) | Write-Output
    Invoke-Fixture 'liveInstallWithoutVpn'
} finally {
    & $adb -s $Serial uninstall $fixture 2>&1 | Out-Null
    Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package) | Write-Output
    Invoke-Adb -Arguments @('shell', 'rm', '-f', '/data/local/tmp/domainguard-install-fixture.apk') | Write-Output
}
Write-Output 'Real install, stopped-process catch-up, update and reinstall tests passed.'
