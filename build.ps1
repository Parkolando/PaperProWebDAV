param([string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$build = Join-Path $base 'build'
$android = Join-Path $Sdk 'platforms/android-33/android.jar'
$tools = Join-Path $Sdk 'build-tools/30.0.3'
foreach ($dir in @('classes', 'stubs', 'dex', 'empty')) {
    New-Item -ItemType Directory -Force (Join-Path $build $dir) | Out-Null
}
function Check-Exit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed ($LASTEXITCODE)" }
}
$stubFiles = @(Get-ChildItem (Join-Path $base 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -cp $android -d "$build/stubs" @stubFiles
Check-Exit 'Compile host API stubs'
$classPath = "$android;$build/stubs;$base/lib/xposed-api-53.jar;$base/lib/okhttp-3.12.13.jar;$base/lib/okio-1.15.0.jar"
$sourceFiles = @(Get-ChildItem (Join-Path $base 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 --release 8 -cp $classPath -d "$build/classes" @sourceFiles
Check-Exit 'Compile module'
& jar cf "$build/module.jar" -C "$build/classes" .
Check-Exit 'Package classes'
& jar cf "$build/host-stubs.jar" -C "$build/stubs" .
Check-Exit 'Package host API stubs'
& java -cp "$tools/lib/d8.jar" com.android.tools.r8.D8 --min-api 19 --lib $android --classpath "$build/host-stubs.jar" --classpath "$base/lib/xposed-api-53.jar" --classpath "$base/lib/okhttp-3.12.13.jar" --classpath "$base/lib/okio-1.15.0.jar" --output "$build/dex" "$build/module.jar"
Check-Exit 'Build KitKat dex'
if (-not (Test-Path -LiteralPath "$build/dex/classes.dex")) { throw 'D8 produced no dex file' }
& "$tools/aapt.exe" package -f -M "$base/AndroidManifest.xml" -I $android -A "$base/assets" -F "$build/unsigned.apk" "$build/empty"
Check-Exit 'Build APK'
Push-Location "$build/dex"
try {
    & "$tools/aapt.exe" add "$build/unsigned.apk" classes.dex
    Check-Exit 'Add dex'
} finally { Pop-Location }
& "$tools/zipalign.exe" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
Check-Exit 'Align APK'
$key = Join-Path $base 'local-signing-key.p12'
if (-not (Test-Path -LiteralPath $key)) {
    & keytool -genkeypair -keystore $key -storepass paper-webdav-local -keypass paper-webdav-local -alias paper-webdav -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Local Paper WebDAV'
    Check-Exit 'Create local development key'
}
& "$tools/apksigner.bat" sign --ks $key --ks-pass pass:paper-webdav-local --key-pass pass:paper-webdav-local --v1-signing-enabled true --v2-signing-enabled false --out "$build/paper-webdav.apk" "$build/aligned.apk"
Check-Exit 'Sign module'
& "$tools/apksigner.bat" verify --min-sdk-version 19 --verbose "$build/paper-webdav.apk"
Check-Exit 'Verify signature'
Write-Output "Built $build/paper-webdav.apk"
