param([string]$JdkHome)

$ErrorActionPreference = 'Stop'
$coreTestRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$coreTestJavac = if ($JdkHome) { Join-Path $JdkHome 'bin/javac.exe' } else { (Get-Command javac.exe).Source }
$coreTestJava = Join-Path (Split-Path -Parent $coreTestJavac) 'java.exe'
$coreTestLegacy = Join-Path $coreTestRoot 'build/moddev/clientLegacyClasspath.txt'
$coreTestMinecraft = Join-Path $coreTestRoot 'build/moddev/artifacts/neoforge-21.1.252.jar'
foreach ($coreTestInput in @($coreTestLegacy, $coreTestMinecraft)) {
    if (-not (Test-Path -LiteralPath $coreTestInput)) { throw 'Existing development dependencies are required; this script does not start Gradle or Minecraft.' }
}
$coreTestClasspath = (@($coreTestMinecraft) + @(Get-Content -LiteralPath $coreTestLegacy | Where-Object { $_ -notmatch 'cloth-config' })) -join ';'
$coreTestOutput = Join-Path $coreTestRoot 'build/core-native-config-tests'
$coreTestGlue = Join-Path $coreTestOutput 'glue'
$coreTestCompiled = Join-Path $coreTestOutput 'classes'
New-Item -ItemType Directory -Path $coreTestCompiled -Force | Out-Null
$coreTestStubs = @{
    'com/xfw/shuttershadow/Shuttershadow.java' = @'
package com.xfw.shuttershadow;
public final class Shuttershadow { public static final String MODID = "shuttershadow"; }
'@
    'com/xfw/shuttershadow/util/Helper.java' = @'
package com.xfw.shuttershadow.util;
public final class Helper {
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CoreNativeConfigTest");
    public static void log(String message) { LOGGER.info(message); }
}
'@
    'com/xfw/shuttershadow/util/MyTaskList.java' = @'
package com.xfw.shuttershadow.util;
public final class MyTaskList {}
'@
}
$coreTestSources = foreach ($coreTestEntry in $coreTestStubs.GetEnumerator()) {
    $coreTestStub = Join-Path $coreTestGlue $coreTestEntry.Key
    New-Item -ItemType Directory -Path (Split-Path -Parent $coreTestStub) -Force | Out-Null
    [System.IO.File]::WriteAllText($coreTestStub, $coreTestEntry.Value, [System.Text.UTF8Encoding]::new($false))
    $coreTestStub
}
$coreTestSources += @(
    (Join-Path $coreTestRoot 'src/main/java/com/xfw/shuttershadow/core/CoreConfig.java'),
    (Join-Path $coreTestRoot 'src/main/java/com/xfw/shuttershadow/core/CoreSettings.java'),
    (Join-Path $PSScriptRoot 'tests/CoreNativeConfigTest.java')
)
& $coreTestJavac '-J-Duser.language=en' -encoding UTF-8 --release 21 -proc:none -cp $coreTestClasspath -d $coreTestCompiled $coreTestSources
if ($LASTEXITCODE -ne 0) { throw 'Native core configuration source compilation failed.' }
& $coreTestJava '-Dfile.encoding=UTF-8' -ea -cp ($coreTestCompiled + ';' + $coreTestClasspath) 'com.xfw.shuttershadow.core.CoreNativeConfigTest' $coreTestOutput
if ($LASTEXITCODE -ne 0) { throw 'Native core configuration behavior checks failed.' }
