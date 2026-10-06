param([string]$IrisJar)

$ErrorActionPreference = 'Stop'
$compatRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$compatCompiled = Join-Path $compatRoot 'build/classes/java/main'
$compatSodium = Join-Path $compatRoot 'build/render-compat/sodium-mod.jar'
$compatLegacy = Join-Path $compatRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($compatRequired in @($compatCompiled, $compatSodium, $compatLegacy)) {
    if (-not (Test-Path -LiteralPath $compatRequired)) {
        throw 'Run gradlew.bat build prepareClientRun first.'
    }
}
$compatGradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
if (-not $IrisJar) {
    $compatIrisCache = Join-Path $compatGradleCache 'caches/modules-2/files-2.1/maven.modrinth/iris/1.8.14-beta.1+1.21.1-neoforge'
    $IrisJar = Get-ChildItem -LiteralPath $compatIrisCache -Recurse -Filter 'iris-1.8.14-beta.1+1.21.1-neoforge.jar' |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $IrisJar -or -not (Test-Path -LiteralPath $IrisJar)) { throw 'Iris 1.8.14 Beta 1 JAR not found.' }
$compatMinecraft = Join-Path $compatRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$compatExposureCache = Join-Path $compatGradleCache 'caches/modules-2/files-2.1/curse.maven/exposure-871755/8957000'
$compatExposure = Get-ChildItem -LiteralPath $compatExposureCache -Recurse -Filter '*.jar' |
    Select-Object -First 1 -ExpandProperty FullName
$compatClasspath = (@($compatCompiled, $compatSodium, $compatMinecraft, $compatExposure) + @(Get-Content -LiteralPath $compatLegacy)) -join ';'
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/RenderCompatTargetsTest.java') $compatSodium $IrisJar $compatCompiled
if ($LASTEXITCODE -ne 0) { throw 'Render compatibility bytecode checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/IrisCameraDepthTargetTest.java') $compatCompiled
if ($LASTEXITCODE -ne 0) { throw 'Iris camera depth target lifecycle checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/IrisCameraColorTargetTest.java') $compatCompiled
if ($LASTEXITCODE -ne 0) { throw 'Iris camera color target lifecycle checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/SodiumContextSwapTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Sodium context swap checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/RemoteSceneCodecTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Remote scene codec checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/SourceStandCaptureTest.java') $compatCompiled
if ($LASTEXITCODE -ne 0) { throw 'Source stand capture lifecycle checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/ShuttershadowConfigTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Server config checks failed.' }
& java -ea -cp $compatClasspath (Join-Path $PSScriptRoot 'tests/ExposureAuthorizationCancelTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Exposure authorization cancellation checks failed.' }
