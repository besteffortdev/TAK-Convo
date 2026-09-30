# Builds the plugin, link-checks it against ATAK, installs it on a device running the developer
# ATAK, and restarts ATAK with the plugin loaded. See docs/07-development-and-testing.md.
#
#   tools\deploy.ps1 -Serial <serial> [-NoBuild] [-SkipLinkCheck]
#
# Needs: JDK 21 (JAVA_HOME or -JavaHome), adb (Android SDK platform-tools), local.properties
# with sdk.path pointing to the ATAK-CIV SDK (its atak.apk is the link check reference).
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$NoBuild,
    [switch]$SkipLinkCheck,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
# not 'Stop': Windows PowerShell treats anything a native command writes to stderr (adb,
# monkey) as an error; the steps that matter check $LASTEXITCODE instead
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$atakPackage = 'com.atakmap.app.civ'
$loadFlag = 'shouldLoad-com.atakmap.android.takconvo.plugin'
Set-Location $root

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

if (-not $NoBuild) {
    & .\gradlew.bat assembleCivDebug --offline -q
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}
$apk = Get-ChildItem "$root\app\build\outputs\apk\civ\debug\*.apk" |
    Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $apk) { throw 'no APK in app\build\outputs\apk\civ\debug' }

if (-not $SkipLinkCheck) {
    $sdk = (Select-String -Path "$root\local.properties" -Pattern '^sdk\.path=(.*)$').Matches |
        Select-Object -First 1
    if (-not $sdk) { throw 'sdk.path is not set in local.properties' }
    $atakApk = Join-Path ($sdk.Groups[1].Value -replace '\\\\', '\' -replace '\\:', ':') 'atak.apk'
    & $java -Xmx4g tools\AtakLinkCheck.java $atakApk $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw 'link check failed: fix it or accept it in tools\atak-link-ignore.txt' }
}

& $Adb -s $Serial install -r $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

# Reinstalling a plugin makes ATAK clear its "load this plugin" flag; set it back while ATAK
# is stopped, or the plugin stays unloaded until it is re-enabled in ATAK's plugin list.
# ATAK also sets pluginSafeMode while it loads plugins and clears it 10 s later: stopped
# before that, it asks "ATAK exited uncleanly... load the plugins anyway?" on the next start.
& $Adb -s $Serial shell am force-stop $atakPackage
Start-Sleep -Seconds 1
$prefs = "shared_prefs/${atakPackage}_preferences.xml"
& $Adb -s $Serial shell "run-as $atakPackage sed -i -e 's/name=\""$loadFlag\"" value=\""false\""/name=\""$loadFlag\"" value=\""true\""/' -e 's/name=\""pluginSafeMode\"" value=\""true\""/name=\""pluginSafeMode\"" value=\""false\""/' $prefs"
& $Adb -s $Serial shell "run-as $atakPackage grep -E '$loadFlag|pluginSafeMode' $prefs"

& $Adb -s $Serial logcat -c
& $Adb -s $Serial shell monkey -p $atakPackage -c android.intent.category.LAUNCHER 1 2>$null | Out-Null
Write-Host 'ATAK restarted; waiting for the XMPP account'
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    $online = & $Adb -s $Serial logcat -d -s TakConvo.Plugin:D | Select-String 'status: ONLINE'
    if ($online) { Write-Host 'XMPP account online'; exit 0 }
}
Write-Host 'the account did not come online within 60 s; recent log:'
& $Adb -s $Serial logcat -d -s TakConvo.Plugin:D TakConvo.XmppEngine:D AndroidRuntime:E |
    Select-Object -Last 20
