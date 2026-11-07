param(
    [string]$SheetUrl = ""
)
$ErrorActionPreference = "Stop"

$jbrBin = "C:\Program Files\Android\Android Studio\jbr\bin"
if (Test-Path $jbrBin) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:PATH = "$jbrBin;$env:PATH"
}

$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$buildToolsDir = Get-ChildItem "$sdkRoot\build-tools" | Sort-Object Name -Descending | Select-Object -First 1
$platformDir = Get-ChildItem "$sdkRoot\platforms" | Sort-Object Name -Descending | Select-Object -First 1

Write-Host "Using build-tools: $($buildToolsDir.FullName)"
Write-Host "Using platform: $($platformDir.FullName)"

$aapt2 = Join-Path $buildToolsDir.FullName "aapt2.exe"
$d8 = Join-Path $buildToolsDir.FullName "d8.bat"
$zipalign = Join-Path $buildToolsDir.FullName "zipalign.exe"
$apksigner = Join-Path $buildToolsDir.FullName "apksigner.bat"
$androidJar = Join-Path $platformDir.FullName "android.jar"

$projRoot = $PSScriptRoot
$buildOut = Join-Path $projRoot "build"
if (Test-Path $buildOut) { Remove-Item $buildOut -Recurse -Force }
New-Item -ItemType Directory -Path "$buildOut\classes" -Force | Out-Null

# 1. Link manifest with aapt2
& $aapt2 link -o "$buildOut\unaligned.apk" `
    --manifest "$projRoot\app\src\main\AndroidManifest.xml" `
    -I $androidJar `
    --java "$buildOut\gen"

# 2. Compile Java sources
$javaFiles = Get-ChildItem "$projRoot\app\src\main\java\com\pos\tablet" -Filter "*.java" | ForEach-Object { $_.FullName }
& "javac.exe" -encoding UTF-8 -source 1.8 -target 1.8 `
    -cp $androidJar `
    -d "$buildOut\classes" `
    $javaFiles
if ($LASTEXITCODE -ne 0) { throw "Java compilation failed" }

# 3. Package .class into classes.jar and convert to classes.dex with d8
& "jar.exe" cf "$buildOut\classes.jar" -C "$buildOut\classes" .
& $d8 --lib $androidJar --output "$buildOut" "$buildOut\classes.jar"
if (-not (Test-Path "$buildOut\classes.dex")) { throw "d8 failed to produce classes.dex" }

# 4. Add classes.dex into unaligned.apk
Push-Location $buildOut
& "jar.exe" uf "$buildOut\unaligned.apk" "classes.dex"
Pop-Location

# 5. Zipalign
& $zipalign -f 4 "$buildOut\unaligned.apk" "$buildOut\aligned.apk"

# 6. Generate local debug keystore if not exists & sign APK
$ksPath = Join-Path $projRoot "debug.keystore"
if (-not (Test-Path $ksPath)) {
    & "keytool.exe" -genkeypair -v `
        -keystore $ksPath `
        -storepass android -alias androiddebugkey -keypass android `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}

$finalApk = Join-Path $projRoot "pos_tablet_app.apk"
& $apksigner sign --ks $ksPath --ks-pass pass:android --key-pass pass:android --out $finalApk "$buildOut\aligned.apk"

Write-Host "APK built successfully: $finalApk"

# 7. Install and launch via ADB (if device connected)
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
$devList = & $adb devices
if ($devList -match "\bdevice\b") {
    & $adb shell "input keyevent 224; wm dismiss-keyguard; logcat -c"
    & $adb install -r $finalApk
    & $adb shell am force-stop com.pos.tablet
    if ($SheetUrl -ne "") {
        & $adb shell am start -n com.pos.tablet/.MainActivity --es set_sheet_url "$SheetUrl" --ez auto_upload_menu true
    } else {
        & $adb shell am start -n com.pos.tablet/.MainActivity
    }
    Start-Sleep -Seconds 3
} else {
    Write-Host "No active ADB device detected. APK is ready at: $finalApk"
}
