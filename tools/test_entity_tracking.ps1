param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$trackingRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trackingJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$trackingJava = Join-Path (Split-Path -Parent $trackingJavac) 'java.exe'
$trackingCompiled = Join-Path $trackingRoot 'build/classes/java/main'
$trackingMinecraft = Join-Path $trackingRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$trackingLegacy = Join-Path $trackingRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($trackingRequired in @($trackingCompiled, $trackingMinecraft, $trackingLegacy)) {
    if (-not (Test-Path -LiteralPath $trackingRequired)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$trackingGradleModules = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
$trackingExtraJars = @('curse.maven/exposure-871755/8957000','org.jetbrains/annotations/24.1.0') | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $trackingGradleModules $_) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -First 1 -ExpandProperty FullName
}
$trackingExtraJars += Get-ChildItem -LiteralPath (Join-Path $trackingGradleModules 'io.github.llamalad7/mixinextras-neoforge') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -Last 1 -ExpandProperty FullName
$trackingClasspath = (@($trackingCompiled, $trackingMinecraft) + $trackingExtraJars + @(Get-Content -LiteralPath $trackingLegacy)) -join ';'
$trackingOutput = Join-Path $trackingRoot 'build/native-entity-tracking-tests/production'
New-Item -ItemType Directory -Path $trackingOutput -Force | Out-Null
$trackingSources = @('access/IETrackedEntity.java','core/chunk_loading/EntitySync.java','core/teleportation/ServerTeleportationManager.java','mixin/minecraft/server/MixinTrackedEntity.java','mixin/minecraft/server/MixinChunkMap_C.java','mixin/minecraft/server/MixinServerEntity.java') |
    ForEach-Object { Join-Path $trackingRoot ('src/main/java/com/xfw/shuttershadow/' + $_) }
& $trackingJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $trackingClasspath -d $trackingOutput $trackingSources
if ($LASTEXITCODE -ne 0) { throw 'Production entity tracking source compilation failed.' }
& $trackingJava -ea -cp $trackingClasspath (Join-Path $PSScriptRoot 'tests/EntityTrackingTest.java') $trackingOutput $trackingMinecraft
if ($LASTEXITCODE -ne 0) { throw 'Production entity tracking behavior fixture failed.' }
