param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$commandRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$commandJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$commandJava = Join-Path (Split-Path -Parent $commandJavac) 'java.exe'
$commandCompiled = Join-Path $commandRoot 'build/classes/java/main'
$commandMinecraft = Join-Path $commandRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$commandLegacy = Join-Path $commandRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($commandRequired in @($commandCompiled, $commandMinecraft, $commandLegacy)) {
    if (-not (Test-Path -LiteralPath $commandRequired)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$commandClasspath = (@($commandCompiled, $commandMinecraft) + @(Get-Content -LiteralPath $commandLegacy)) -join ';'
$commandOutput = Join-Path $commandRoot 'build/seamless-teleport-command-tests/production'
New-Item -ItemType Directory -Path $commandOutput -Force | Out-Null
& $commandJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $commandClasspath -d $commandOutput (Join-Path $commandRoot 'src/main/java/com/xfw/shuttershadow/SeamlessTeleportCommand.java')
if ($LASTEXITCODE -ne 0) { throw 'Seamless teleport command source compilation failed.' }
& $commandJava '-Dfile.encoding=UTF-8' '-Duser.language=en' -ea -cp $commandClasspath (Join-Path $PSScriptRoot 'tests/SeamlessTeleportCommandTest.java') $commandOutput
if ($LASTEXITCODE -ne 0) { throw 'Seamless teleport command production fixture failed.' }
