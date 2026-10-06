param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$travelRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$travelJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$travelJava = Join-Path (Split-Path -Parent $travelJavac) 'java.exe'
$travelCompiled = Join-Path $travelRoot 'build/classes/java/main'
$travelMinecraft = Join-Path $travelRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$travelLegacy = Join-Path $travelRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($travelRequired in @($travelCompiled, $travelMinecraft, $travelLegacy)) {
    if (-not (Test-Path -LiteralPath $travelRequired)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$travelModules = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
$travelMixinExtras = Get-ChildItem -LiteralPath (Join-Path $travelModules 'io.github.llamalad7/mixinextras-neoforge') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -Last 1 -ExpandProperty FullName
$travelClasspath = (@($travelCompiled, $travelMinecraft, $travelMixinExtras) + @(Get-Content -LiteralPath $travelLegacy)) -join ';'
$travelOutput = Join-Path $travelRoot 'build/dimension-travel-tests/production'
New-Item -ItemType Directory -Path $travelOutput -Force | Out-Null
$travelSource = Join-Path $travelRoot 'src/main/java/com/xfw/shuttershadow/mixin/minecraft/server/MixinServerPlayer.java'
& $travelJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $travelClasspath -d $travelOutput $travelSource
if ($LASTEXITCODE -ne 0) { throw 'Production dimension travel hook compilation failed.' }
& $travelJava -ea -cp $travelClasspath (Join-Path $PSScriptRoot 'tests/DimensionTravelTrackingTest.java') $travelOutput $travelMinecraft
if ($LASTEXITCODE -ne 0) { throw 'Production dimension travel hook fixture failed.' }
