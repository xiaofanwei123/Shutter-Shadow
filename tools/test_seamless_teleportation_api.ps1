param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$apiRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$apiJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$apiJava = Join-Path (Split-Path -Parent $apiJavac) 'java.exe'
$apiCompiled = Join-Path $apiRoot 'build/classes/java/main'
$apiMinecraft = Join-Path $apiRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$apiLegacy = Join-Path $apiRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($apiRequired in @($apiCompiled, $apiMinecraft, $apiLegacy)) {
    if (-not (Test-Path -LiteralPath $apiRequired)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$apiModules = if ($env:GRADLE_USER_HOME) { Join-Path $env:GRADLE_USER_HOME 'caches/modules-2/files-2.1' } else { Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1' }
$apiAnnotations = Get-ChildItem -LiteralPath (Join-Path $apiModules 'org.jetbrains/annotations/24.1.0') -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '(sources|javadoc)' } | Select-Object -First 1 -ExpandProperty FullName
$apiClasspath = (@($apiCompiled, $apiMinecraft, $apiAnnotations) + @(Get-Content -LiteralPath $apiLegacy)) -join ';'
$apiOutput = Join-Path $apiRoot 'build/seamless-teleportation-api-tests/production'
New-Item -ItemType Directory -Path $apiOutput -Force | Out-Null
$apiSource = Join-Path $apiRoot 'src/main/java/com/xfw/shuttershadow/api/SeamlessTeleportation.java'
& $apiJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $apiClasspath -d $apiOutput $apiSource
if ($LASTEXITCODE -ne 0) { throw 'Public teleportation API source compilation failed.' }
& $apiJava '-Dfile.encoding=UTF-8' -ea -cp $apiClasspath (Join-Path $PSScriptRoot 'tests/SeamlessTeleportationApiTest.java') $apiOutput
if ($LASTEXITCODE -ne 0) { throw 'Public teleportation API production fixture failed.' }
