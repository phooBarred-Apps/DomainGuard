param([string]$Serial = 'emulator-5590', [bool]$AutomaticBackup = $true)
# This fixture replaces all DomainGuard data on the disposable emulator.
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator.' }
$root = Split-Path $PSScriptRoot
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$package = 'heartguard.heartguard.main.mw'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
function Invoke-Fixture {
    param([string]$Method)
    $result = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '-w', '-r',
        '-e', 'runBackupFixture', 'true', '-e', 'backupAutomatic', $AutomaticBackup.ToString().ToLowerInvariant(), '-e', 'class',
        "heartguard.heartguard.main.BackupDeviceTest#$Method", $runner)
    $result | & python -B -c 'import sys; sys.path.insert(0, sys.argv[1]); from release_runtime import instrumentation_passes; instrumentation_passes(sys.stdin.read(), [sys.argv[2]])' $PSScriptRoot "heartguard.heartguard.main.BackupDeviceTest#$Method"
    if ($LASTEXITCODE -ne 0) { throw $result }
    Write-Output "$Method passed."
}
if ((Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') {
    throw 'Target is not an emulator.'
}
Invoke-Adb -Arguments @('install', '-r', (Join-Path $root 'app/build/outputs/apk/debug/DomainGuard-debug.apk')) | Write-Output
Invoke-Adb -Arguments @('install', '-r', (Join-Path $root 'app/build/outputs/apk/androidTest/debug/DomainGuard-debug-androidTest.apk')) | Write-Output
Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package) | Write-Output
$transports = Invoke-Adb -Arguments @('shell', 'bmgr', 'list', 'transports')
$match = [regex]::Match($transports, '(?m)^\s*\*\s+(\S+)')
if (-not $match.Success) { throw 'Cannot determine original backup transport.' }
$originalTransport = $match.Groups[1].Value
$originalEnabled = Invoke-Adb -Arguments @('shell', 'bmgr', 'enabled')
$localTransport = 'com.android.localtransport/.LocalTransport'
try {
    Invoke-Adb -Arguments @('shell', 'bmgr', 'transport', $localTransport) | Write-Output
    Invoke-Adb -Arguments @('shell', 'bmgr', 'enable', 'true') | Write-Output
    # Test: Restore real settings, timers and missing-app rules into a new installation.
    # Why: Removing only markers leaves databases and files that can hide an incomplete restore.
    Invoke-Fixture -Method 'prepareBackup'
    # Android also needs package metadata to recognize the restore set.
    $backup = Invoke-Adb -Arguments @('shell', 'bmgr', 'backupnow', '--non-incremental', '@pm@', $package)
    $backup | Write-Output
    if ($backup -notmatch 'Backup finished with result: Success' -or
            $backup -notmatch ('Package ' + [regex]::Escape($package) + ' with result: Success')) { throw 'Local backup failed.' }
    Invoke-Adb -Arguments @('uninstall', $package) | Write-Output
    Invoke-Adb -Arguments @('install', (Join-Path $root 'app/build/outputs/apk/debug/DomainGuard-debug.apk')) | Write-Output
    $sets = Invoke-Adb -Arguments @('shell', 'bmgr', 'list', 'sets')
    $tokenMatch = [regex]::Match($sets, '(?m)^\s*([0-9a-fA-F]+)\s+:')
    if (-not $tokenMatch.Success) { throw "No restore set: $sets" }
    $restore = Invoke-Adb -Arguments @('shell', 'bmgr', 'restore', $tokenMatch.Groups[1].Value, $package)
    $restore | Write-Output
    if ($restore -notmatch 'restoreFinished: 0') { throw 'Local restore failed.' }
    Invoke-Fixture -Method 'verifyRestored'
} finally {
    try {
        Invoke-Fixture -Method 'removeFixture'
        Invoke-Adb -Arguments @('shell', 'bmgr', 'wipe', $localTransport, $package) | Write-Output
    } finally {
        Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package) | Write-Output
        if ($originalEnabled -match 'disabled') {
            Invoke-Adb -Arguments @('shell', 'bmgr', 'enable', 'false') | Write-Output
        }
        Invoke-Adb -Arguments @('shell', 'bmgr', 'transport', $originalTransport) | Write-Output
    }
}
Write-Output 'Android local-transport backup/restore passed. No Google cloud upload was tested.'
