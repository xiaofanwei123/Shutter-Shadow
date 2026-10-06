param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$enchantmentRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$enchantmentJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$enchantmentJava = Join-Path (Split-Path -Parent $enchantmentJavac) 'java.exe'
$enchantmentCompiled = Join-Path $enchantmentRoot 'build/classes/java/main'
$enchantmentLegacy = Join-Path $enchantmentRoot 'build/moddev/clientLegacyClasspath.txt'
$enchantmentConfig = Join-Path $enchantmentRoot 'src/main/resources/shuttershadow.mixins.json'
foreach ($enchantmentInput in @($enchantmentCompiled, $enchantmentLegacy, $enchantmentConfig)) {
    if (-not (Test-Path -LiteralPath $enchantmentInput)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$enchantmentModules = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
$enchantmentExposure = Get-ChildItem -LiteralPath (Join-Path $enchantmentModules 'curse.maven/exposure-871755/8957000') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -First 1 -ExpandProperty FullName
$enchantmentExtra = @(@('org.jetbrains/annotations/24.1.0') | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $enchantmentModules $_) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -First 1 -ExpandProperty FullName
})
$enchantmentExtra += Get-ChildItem -LiteralPath (Join-Path $enchantmentModules 'io.github.llamalad7/mixinextras-neoforge') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -Last 1 -ExpandProperty FullName
$enchantmentMinecraft = Join-Path $enchantmentRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$enchantmentClasspath = (@($enchantmentCompiled, $enchantmentMinecraft, $enchantmentExposure) + $enchantmentExtra + @(Get-Content -LiteralPath $enchantmentLegacy)) -join ';'
$enchantmentOutput = Join-Path $enchantmentRoot 'build/camera-enchantment-tests/production'
New-Item -ItemType Directory -Path $enchantmentOutput -Force | Out-Null
$enchantmentSources = @('CameraEnchantments.java','DimensionFilmCapture.java','MobDimensionFilmCapture.java','api/SeamlessTeleportation.java','api/ChunkLoading.java','api/ChunkLoader.java','network/RemoteStandPreparation.java','mixin/exposure/CameraItemRemoteCaptureMixin.java','mixin/exposure/ViewfinderNarcissismMixin.java') |
    ForEach-Object { Join-Path $enchantmentRoot ('src/main/java/com/xfw/shuttershadow/' + $_) }
& $enchantmentJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $enchantmentClasspath -d $enchantmentOutput $enchantmentSources
if ($LASTEXITCODE -ne 0) { throw 'Camera enchantment production source compilation failed.' }
& $enchantmentJava '-Dfile.encoding=UTF-8' -ea -cp $enchantmentClasspath (Join-Path $PSScriptRoot 'tests/CameraEnchantmentsTest.java') $enchantmentExposure $enchantmentOutput $enchantmentConfig
if ($LASTEXITCODE -ne 0) { throw 'Camera enchantment native bytecode checks failed.' }
