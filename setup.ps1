# Robonix Android - 一键环境安装脚本
# 用法: PowerShell 中运行 .\setup.ps1

$ErrorActionPreference = "Stop"

Write-Host "=== Robonix Android Build Setup ===" -ForegroundColor Cyan

# 1. JDK 17
$javaDir = "C:\jdk17"
if (-not (Test-Path "$javaDir\bin\java.exe")) {
    Write-Host "[1/4] Downloading JDK 17..." -ForegroundColor Yellow
    $jdkZip = "$env:TEMP\jdk17.zip"
    Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" -OutFile $jdkZip
    Expand-Archive $jdkZip -DestinationPath $javaDir -Force
    Remove-Item $jdkZip
} else { Write-Host "[1/4] JDK 17 found" -ForegroundColor Green }

$javaHome = (Get-ChildItem "$javaDir\jdk-*").FullName
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

# 2. Android SDK
$sdkDir = "C:\android-sdk"
if (-not (Test-Path "$sdkDir\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host "[2/4] Downloading Android SDK command-line tools..." -ForegroundColor Yellow
    $sdkZip = "$env:TEMP\sdk.zip"
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $sdkZip
    New-Item -ItemType Directory -Force -Path "$sdkDir\cmdline-tools\latest" | Out-Null
    Expand-Archive $sdkZip -DestinationPath "$env:TEMP\sdk-tmp" -Force
    Copy-Item "$env:TEMP\sdk-tmp\cmdline-tools\*" "$sdkDir\cmdline-tools\latest\" -Recurse -Force
    Remove-Item $sdkZip
} else { Write-Host "[2/4] Android SDK found" -ForegroundColor Green }

$env:ANDROID_HOME = $sdkDir
$env:PATH = "$sdkDir\cmdline-tools\latest\bin;$sdkDir\platform-tools;$env:PATH"

# 3. Install SDK packages
Write-Host "[3/4] Installing SDK packages..." -ForegroundColor Yellow
yes | sdkmanager --sdk_root=$sdkDir --licenses 2>$null
sdkmanager --sdk_root=$sdkDir "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 4. Gradle wrapper
Write-Host "[4/4] Downloading Gradle wrapper..." -ForegroundColor Yellow
$wrapperDir = ".\gradle\wrapper"
New-Item -ItemType Directory -Force -Path $wrapperDir | Out-Null
Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-8.4-bin.zip" -OutFile "$wrapperDir\gradle-8.4-bin.zip" -ErrorAction SilentlyContinue

# Set persistent env vars
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome, 'User')
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdkDir, 'User')

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Green
Write-Host "Now run: .\gradlew.bat assembleDebug" -ForegroundColor Cyan
Write-Host "(First build downloads ~500MB of dependencies, takes 10-20 min)" -ForegroundColor DarkYellow
