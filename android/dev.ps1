# Sundial dev commands. Run from anywhere: .\android\dev.ps1 <command>
#   build    assemble the debug APK (Sundial Dev)
#   test     JVM unit tests (no device)
#   install  build and install on the connected phone (or emulator)
#   run      install, connect the phone to local Dandelion (adb reverse), then launch
#   reverse  make the phone's localhost:8089 this PC's Dandelion (needed after each reconnect)
#   shot     save a phone screenshot to %TEMP%\sundial-shot.png
#   spike    open the step 0 spike console
#   devices  list connected devices
#   log      follow the app's logcat (tag Sundial)
#   spikelog print the step 0 event log from the phone
#   alarms   show the app's scheduled alarms
param([Parameter(Position = 0)][string]$Command = "build")

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$pkg = "org.dandeliondaily.sundial.dev"

# Gradle needs JDK 17-21; the system Java (25) is too new. Use Android Studio's bundled JDK.
$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
$env:ANDROID_HOME = $sdk

$localProps = Join-Path $here "local.properties"
if (-not (Test-Path $localProps)) {
    "sdk.dir=" + ($sdk -replace '\\', '/') | Out-File -Encoding ascii $localProps
}

function Gradle([string[]]$tasks) {
    Push-Location $here
    try {
        & .\gradlew.bat @tasks --console=plain
        if ($LASTEXITCODE -ne 0) { throw "gradle $tasks failed" }
    } finally { Pop-Location }
}

switch ($Command) {
    "build"    { Gradle @("assembleDebug") }
    "test"     { Gradle @("testDebugUnitTest") }
    "reverse"  { & $adb reverse tcp:8089 tcp:8089 }
    "install"  { Gradle @("installDebug") }
    "run"      {
        Gradle @("installDebug")
        & $adb reverse tcp:8089 tcp:8089
        & $adb shell am start -n "$pkg/org.dandeliondaily.sundial.MainActivity"
    }
    "shot"     {
        $out = Join-Path $env:TEMP "sundial-shot.png"
        cmd /c "`"$adb`" exec-out screencap -p > `"$out`""
        Write-Output $out
    }
    "spike"    { & $adb shell am start -n "$pkg/org.dandeliondaily.sundial.spike.MainActivity" }
    "devices"  { & $adb devices -l }
    "log"      { & $adb logcat -s Sundial:V }
    "spikelog" { & $adb shell run-as $pkg cat files/spike-log.txt }
    "alarms"   { & $adb shell dumpsys alarm | Select-String -SimpleMatch $pkg }
    default    { throw "Unknown command '$Command'. See the top of dev.ps1." }
}
