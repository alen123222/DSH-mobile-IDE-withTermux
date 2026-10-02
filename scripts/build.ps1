param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Push-Location $taskRoot
try {
    if (Test-Path 'C:\Program Files\Java\jdk-17\bin\java.exe') { $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17' }
    $env:GRADLE_USER_HOME = Join-Path $taskRoot '.cache\gradle'
    $env:ANDROID_USER_HOME = Join-Path $taskRoot '.cache\android'
    $taskKeyStore = Join-Path $taskRoot '.cache\debug.keystore'
    if (-not (Test-Path $taskKeyStore)) {
        New-Item -ItemType Directory -Force (Split-Path $taskKeyStore -Parent) | Out-Null
        $taskKeyTool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool' }
        & $taskKeyTool -genkeypair -noprompt -keystore $taskKeyStore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the local debug signing key' }
    }
    $taskReadCache = Join-Path $env:USERPROFILE '.gradle\caches'
    if (Test-Path $taskReadCache) { $env:GRADLE_RO_DEP_CACHE = $taskReadCache }
    $taskArgs = @('--no-daemon', ':app:assembleDebug', ':app:lintDebug')
    if ($Offline) { $taskArgs += '--offline' }
    & .\gradlew.bat @taskArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
