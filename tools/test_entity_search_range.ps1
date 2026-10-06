param(
    [string]$JdkHome
)

$ErrorActionPreference = 'Stop'
$captureRepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($JdkHome) {
    $captureJavac = Join-Path $JdkHome 'bin/javac.exe'
} else {
    $captureJavac = (Get-Command javac.exe -ErrorAction Stop).Source
}
$captureJava = Join-Path (Split-Path -Parent $captureJavac) 'java.exe'
$captureJavacVersion = ((& $captureJavac -version 2>&1) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $captureJavacVersion -notmatch '^javac 21(?:\.|\s|$)') {
    throw "Java 21 is required. Found: $captureJavacVersion. Pass -JdkHome with a JDK 21 directory."
}

$captureOutput = Join-Path $captureRepoRoot 'build/entity-search-range-tests'
New-Item -ItemType Directory -Path $captureOutput -Force | Out-Null
$captureRangeSource = Join-Path $captureRepoRoot 'src/main/java/com/xfw/shuttershadow/util/CaptureEntitySearchRange.java'
$captureRangeTestSource = Join-Path $PSScriptRoot 'tests/CaptureEntitySearchRangeTest.java'
Write-Output "Compiling production entity search range checks with $captureJavacVersion"
& $captureJavac -encoding UTF-8 --release 21 -d $captureOutput $captureRangeSource $captureRangeTestSource
if ($LASTEXITCODE -ne 0) {
    throw "Capture entity search range test compilation failed (exit $LASTEXITCODE)."
}
& $captureJava -ea -cp $captureOutput 'com.xfw.shuttershadow.util.CaptureEntitySearchRangeTest'
if ($LASTEXITCODE -ne 0) {
    throw "Capture entity search range tests failed (exit $LASTEXITCODE)."
}
