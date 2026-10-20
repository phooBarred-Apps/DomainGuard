param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease')
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not $AndroidHome) { throw 'Set JAVA_HOME to JDK 17 and ANDROID_HOME to the Android SDK.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
Push-Location $PSScriptRoot
try {
    $socketDirectory = Join-Path $PSScriptRoot '.gradle/sockets'
    New-Item -ItemType Directory -Force -Path $socketDirectory | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' -Djdk.net.unixdomain.tmpdir="' + $socketDirectory + '"').Trim()
    & ./gradlew.bat @Tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Gradle checks or build failed.' }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
