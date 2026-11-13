# Builds Vanish, starts an emulator if none is running, installs and launches the app (Windows PowerShell).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$appDir = Join-Path $root "app"

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

# 1. Android SDK
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if ((-not $sdk) -and $env:LOCALAPPDATA) {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $candidate) { $sdk = $candidate }
}
if ((-not $sdk) -or (-not (Test-Path $sdk))) {
    Fail "Android SDK not found. Install Android Studio once (it installs the SDK and emulator) or set ANDROID_HOME."
}
$env:ANDROID_HOME = $sdk
Set-Content -Path (Join-Path $appDir "local.properties") -Value ("sdk.dir=" + ($sdk -replace '\\', '/')) -Encoding ascii

# 2. Java 17+
$java = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { $java = Join-Path $env:JAVA_HOME "bin\java.exe" }
if (-not $java) {
    $studioJdks = @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr")
    foreach ($c in $studioJdks) {
        if (Test-Path (Join-Path $c "bin\java.exe")) { $java = Join-Path $c "bin\java.exe"; $env:JAVA_HOME = $c; break }
    }
}
if (-not $java) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}
if (-not $java) { Fail "Java not found. Install Android Studio (bundled JDK) or a JDK 17." }

# 3. Gradle wrapper jar (small, downloaded once)
$jar = Join-Path $appDir "gradle\wrapper\gradle-wrapper.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Downloading gradle-wrapper.jar (one time)..."
    $url = "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
    try { Invoke-WebRequest -Uri $url -OutFile $jar -UseBasicParsing }
    catch { Fail "Could not download gradle-wrapper.jar. Open the 'app' folder once in Android Studio (it creates the wrapper), then run this again." }
}

# 4. Emulator / device
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
if (-not (Test-Path $adb)) { Fail "adb not found. Android Studio > SDK Manager > SDK Tools > Android SDK Platform-Tools." }
& $adb start-server | Out-Null

function Get-Devices { (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } }

if (-not (Get-Devices)) {
    if (-not (Test-Path $emulator)) { Fail "Emulator not installed. Android Studio > SDK Manager > SDK Tools > Android Emulator." }
    $avds = @(& $emulator -list-avds | Where-Object { $_ -and ($_ -notmatch '\s') })
    if ($avds.Count -eq 0) { Fail "No virtual device found. Android Studio > Device Manager > Create Device (once)." }
    Write-Host ("Starting emulator " + $avds[0] + " ...")
    Start-Process -FilePath $emulator -ArgumentList @("-avd", $avds[0]) | Out-Null
    & $adb wait-for-device
    $deadline = (Get-Date).AddMinutes(4)
    while ((Get-Date) -lt $deadline) {
        $boot = (& $adb shell getprop sys.boot_completed 2>$null)
        if ($boot -and $boot.Trim() -eq "1") { break }
        Start-Sleep -Seconds 3
    }
}

# 5. Build, install, launch
Write-Host "Building and installing (the first run downloads Gradle and libraries: several minutes)..."
Push-Location $appDir
try {
    & $java -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew" -classpath $jar org.gradle.wrapper.GradleWrapperMain installDebug --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed. Read the first error above." }
}
finally { Pop-Location }

& $adb shell am start -n com.example.vanish/.MainActivity | Out-Null
Write-Host "Vanish is running on the emulator. Relay address in the app: ws://10.0.2.2:8000/ws"
