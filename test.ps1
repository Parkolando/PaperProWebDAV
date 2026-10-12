$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$build = Join-Path $base 'build'
New-Item -ItemType Directory -Force "$build/tests" | Out-Null
$key = "$build/test-server.p12"
if (-not (Test-Path -LiteralPath $key)) {
    & keytool -genkeypair -keystore $key -storepass test-password -keypass test-password -alias localhost -keyalg RSA -validity 7 -dname 'CN=localhost' -ext 'SAN=dns:localhost'
    if ($LASTEXITCODE -ne 0) { throw 'Test certificate generation failed' }
}
$classpath = "$base/lib/okhttp-3.12.13.jar;$base/lib/okio-1.15.0.jar;$base/lib/kxml2-2.3.0.jar"
& javac -encoding UTF-8 -cp $classpath -d "$build/tests" "$base/src/dev/paper/webdav/DavClient.java" "$base/src/dev/paper/webdav/Epub.java" "$base/tests/DavClientTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& java -ea -cp "$build/tests;$classpath" dev.paper.webdav.DavClientTest $key
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed' }
