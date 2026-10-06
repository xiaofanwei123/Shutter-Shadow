param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$moveRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$moveJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$moveJava = Join-Path (Split-Path -Parent $moveJavac) 'java.exe'
$moveCompiled = Join-Path $moveRoot 'build/classes/java/main'
$moveMinecraft = Join-Path $moveRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
$moveLegacy = Join-Path $moveRoot 'build/moddev/clientLegacyClasspath.txt'
foreach ($moveRequired in @($moveCompiled, $moveMinecraft, $moveLegacy)) {
    if (-not (Test-Path -LiteralPath $moveRequired)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$moveClasspath = (@($moveCompiled, $moveMinecraft) + @(Get-Content -LiteralPath $moveLegacy)) -join ';'
$moveOutput = Join-Path $moveRoot 'build/player-move-codec-tests/production'
New-Item -ItemType Directory -Path $moveOutput -Force | Out-Null
$moveSources = @('access/IEPlayerMoveC2SPacket.java', 'mixin/minecraft/common/MixinServerboundMovePlayerPacketRead.java',
    'mixin/minecraft/common/MixinServerboundMovePlayerPacket_S.java', 'mixin/minecraft/client/MixinServerboundMovePlayerPacketWrite.java') |
    ForEach-Object { Join-Path $moveRoot ('src/main/java/com/xfw/shuttershadow/' + $_) }
& $moveJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $moveClasspath -d $moveOutput $moveSources
if ($LASTEXITCODE -ne 0) { throw 'Production movement codec hooks compilation failed.' }
& $moveJava -ea -cp ($moveOutput + ';' + $moveClasspath) (Join-Path $PSScriptRoot 'tests/PlayerMoveCodecTest.java') $moveOutput $moveMinecraft
if ($LASTEXITCODE -ne 0) { throw 'Production movement codec fixture failed.' }
