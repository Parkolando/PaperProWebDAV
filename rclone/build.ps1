# Builds rclone for the Ridi Paper Pro (ARMv7, Android 4.4) with the DNS fix in main.go.
# Needs Go (go.dev/dl) on PATH, or pass -Go <path to go.exe>. Output: rclone-armv7, rclone-armv7.gz and their SHA-256.
param([string]$Go = 'go')
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $env:GOOS = 'linux'; $env:GOARCH = 'arm'; $env:GOARM = '7'; $env:CGO_ENABLED = '0'
    & $Go build -trimpath -buildvcs=false -ldflags '-s -w' -o rclone-armv7 .
    if ($LASTEXITCODE -ne 0) { throw 'go build failed' }
    $in = [IO.File]::OpenRead("$PSScriptRoot/rclone-armv7")
    $out = [IO.File]::Create("$PSScriptRoot/rclone-armv7.gz")
    $gzip = New-Object IO.Compression.GZipStream($out, [IO.Compression.CompressionLevel]::Optimal)
    try { $in.CopyTo($gzip) } finally { $gzip.Dispose(); $out.Dispose(); $in.Dispose() }
    Get-FileHash rclone-armv7, rclone-armv7.gz -Algorithm SHA256 | ForEach-Object { '{0}  {1}' -f $_.Hash.ToLower(), (Split-Path $_.Path -Leaf) }
} finally { Pop-Location }
