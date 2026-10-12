param([string]$Serial = (& adb get-serialno), [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk")
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$android = "$Sdk/platforms/android-33/android.jar"
$tools = "$Sdk/build-tools/30.0.3"
$build = "$base/build"
function Checked([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step failed ($LASTEXITCODE)" }
}
New-Item -ItemType Directory -Force "$build/runtime-check", "$build/runtime-dex" | Out-Null
& javac --release 8 -encoding UTF-8 -cp "$android;$build/classes;$base/lib/okhttp-3.12.13.jar" -d "$build/runtime-check" "$base/tests/RuntimeCheck.java"
Checked 'Compile runtime check'
& jar cf "$build/runtime-check.jar" -C "$build/runtime-check" .
Checked 'Package runtime check'
& java -cp "$tools/lib/d8.jar" com.android.tools.r8.D8 --min-api 19 --lib $android --classpath "$build/module.jar" --output "$build/runtime-dex" "$build/runtime-check.jar"
Checked 'Convert runtime check'
& adb -s $Serial shell mkdir -p /data/local/tmp/paper-webdav/dalvik-cache
Checked 'Create runtime check directory'
& adb -s $Serial push "$build/runtime-dex/classes.dex" /data/local/tmp/paper-webdav/runtime.dex
Checked 'Push runtime check'
& adb -s $Serial push "$build/paper-webdav.apk" /data/local/tmp/paper-webdav/module.apk
Checked 'Push module for isolated check'
& python -c "import zipfile; z=zipfile.ZipFile(r'$base/original/com.ridi.paper.apk'); z.extract('classes2.dex',r'$base/original')"
Checked 'Extract host secondary dex'
& adb -s $Serial push "$base/original/classes2.dex" /data/local/tmp/paper-webdav/host2.dex
Checked 'Push host secondary dex'
$output = & adb -s $Serial shell 'ANDROID_DATA=/data/local/tmp/paper-webdav CLASSPATH=/system/priv-app/com.ridi.paper.apk:/data/local/tmp/paper-webdav/host2.dex:/data/local/tmp/paper-webdav/module.apk:/data/local/tmp/paper-webdav/runtime.dex app_process /system/bin dev.paper.webdav.RuntimeCheck'
Checked 'Run on KitKat'
$output | Write-Output
if (-not ($output -match '^PASS \d+ KitKat runtime and original APK API checks')) { throw 'Runtime check did not confirm all checks' }
