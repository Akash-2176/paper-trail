<#
  Paper Trail - deploy to every connected loaner.

  Usage:
    .\deploy.ps1                 # install APK + whitelist + push page + relaunch
    .\deploy.ps1 -PageOnly       # hot reload only, no install (Red Light loop)
    .\deploy.ps1 -Weights        # also push model weights from the USB stick

  Hot reload routes via /data/local/tmp + run-as, which only works because the
  build is debuggable (SPIKE-FINDINGS section 5, edge 1).

  Run this from PowerShell, NOT Git Bash: Git Bash rewrites /data/local/tmp into
  C:/Program Files/Git/data/local/tmp (edge 2). PowerShell has no such mangling.
#>

param(
    [switch]$PageOnly,
    [switch]$Weights
)

$ErrorActionPreference = 'Continue'

$Pkg        = 'com.coldboot.papertrail'
$Adb        = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Root       = $PSScriptRoot
$Apk        = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$Page       = Join-Path $Root 'app\src\main\assets\www\index.html'
# Local stage is authoritative: the USB stick has unmounted itself mid-event once
# already. Falls back to the stick only if the stage is absent.
$WeightsSrc = if (Test-Path (Join-Path $Root '.weights-stage')) {
    Join-Path $Root '.weights-stage'
} else { 'E:\01-models' }
$WeightsDst = '/data/local/tmp/models'

function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }

if (-not (Test-Path $Adb))  { Say "FATAL: adb not found at $Adb" Red; exit 1 }
if (-not (Test-Path $Page)) { Say "FATAL: page not found at $Page" Red; exit 1 }
if (-not $PageOnly -and -not (Test-Path $Apk)) {
    Say "FATAL: apk not found at $Apk - run a build first" Red; exit 1
}

# Serials of devices in 'device' state. Ignores unauthorized/offline entries.
$serials = & $Adb devices | Select-Object -Skip 1 |
    Where-Object { $_ -match '^(\S+)\s+device$' } |
    ForEach-Object { $Matches[1] }

if (-not $serials) { Say 'FATAL: no devices in state "device".' Red; exit 1 }

Say ("Devices: {0}  [{1}]" -f $serials.Count, ($serials -join ', ')) Cyan
Say ''

$results = @()

foreach ($s in $serials) {
    Say "=== $s ===" Cyan
    $steps = [ordered]@{}

    # --- install ---------------------------------------------------------
    if (-not $PageOnly) {
        $o = & $Adb -s $s install -r -t $Apk 2>&1 | Out-String
        $steps['install'] = ($o -match 'Success')
    } else {
        $steps['install'] = $null   # skipped
    }

    # --- battery optimisation exemption (BUILD-PLAN Red Light rule 3) -----
    & $Adb -s $s shell "dumpsys deviceidle whitelist +$Pkg" > $null 2>&1
    $wl = & $Adb -s $s shell "dumpsys deviceidle whitelist" 2>&1 | Out-String
    $steps['whitelist'] = ($wl -match [regex]::Escape($Pkg))

    # --- permissions ------------------------------------------------------
    foreach ($p in @('READ_SMS', 'CAMERA', 'RECORD_AUDIO', 'POST_NOTIFICATIONS')) {
        & $Adb -s $s shell "pm grant $Pkg android.permission.$p" > $null 2>&1
    }
    $dump = & $Adb -s $s shell "dumpsys package $Pkg" 2>&1 | Out-String
    $steps['readSms'] = ($dump -match 'READ_SMS: granted=true')

    # --- page push, /data/local/tmp + run-as ------------------------------
    # files/www only exists after the app has run once and seeded from assets. On a
    # freshly installed device it does not, so create it rather than assuming device
    # B and C look like device A.
    & $Adb -s $s shell "run-as $Pkg mkdir -p files/www/js" > $null 2>&1
    & $Adb -s $s push $Page /data/local/tmp/index.html > $null 2>&1
    $cp = & $Adb -s $s shell "run-as $Pkg cp /data/local/tmp/index.html files/www/index.html 2>&1" 2>&1 | Out-String
    # product-layer JS modules (ADR-011: plain JS, no bundler)
    $jsDir = Join-Path $Root 'app\src\main\assets\www\js'
    if (Test-Path $jsDir) {
        foreach ($j in Get-ChildItem $jsDir -Filter *.js) {
            & $Adb -s $s push $j.FullName "/data/local/tmp/$($j.Name)" > $null 2>&1
            $r = & $Adb -s $s shell "run-as $Pkg cp /data/local/tmp/$($j.Name) files/www/js/$($j.Name) 2>&1" 2>&1 | Out-String
            if (-not [string]::IsNullOrWhiteSpace($r)) { $cp += $r }
        }
    }
    # run-as prints nothing on success; any output means it failed.
    $steps['page'] = [string]::IsNullOrWhiteSpace($cp)
    if (-not $steps['page']) { Say "    run-as: $($cp.Trim())" DarkYellow }

    # --- weights ----------------------------------------------------------
    if ($Weights) {
        if (Test-Path $WeightsSrc) {
            & $Adb -s $s shell "mkdir -p $WeightsDst" > $null 2>&1
            $allOk = $true
            foreach ($f in Get-ChildItem $WeightsSrc -File) {
                $remote = "$WeightsDst/$($f.Name)"
                $sz = (& $Adb -s $s shell "stat -c %s $remote 2>/dev/null" 2>&1 | Out-String).Trim()
                if ($sz -eq [string]$f.Length) {
                    Say "    weights: $($f.Name) already present, skipped" DarkGray
                    continue
                }
                Say "    weights: pushing $($f.Name) ..." DarkGray
                & $Adb -s $s push $f.FullName $remote > $null 2>&1
                $sz2 = (& $Adb -s $s shell "stat -c %s $remote 2>/dev/null" 2>&1 | Out-String).Trim()
                if ($sz2 -ne [string]$f.Length) { $allOk = $false; Say "    weights: FAILED $($f.Name)" Red }
            }
            $steps['weights'] = $allOk
        } else {
            Say "    weights: $WeightsSrc not mounted - skipped" DarkYellow
            $steps['weights'] = $false
        }
    }

    # --- relaunch ---------------------------------------------------------
    & $Adb -s $s shell "am force-stop $Pkg" > $null 2>&1
    & $Adb -s $s shell "am start -n $Pkg/.MainActivity" > $null 2>&1
    Start-Sleep -Milliseconds 2500
    $pid_ = (& $Adb -s $s shell "pidof $Pkg" 2>&1 | Out-String).Trim()
    $steps['launch'] = -not [string]::IsNullOrWhiteSpace($pid_)

    # --- report -----------------------------------------------------------
    foreach ($k in $steps.Keys) {
        if ($null -eq $steps[$k]) { Say ("  {0,-10} SKIP" -f $k) DarkGray }
        elseif ($steps[$k])       { Say ("  {0,-10} PASS" -f $k) Green }
        else                      { Say ("  {0,-10} FAIL" -f $k) Red }
    }

    $failed = @($steps.Keys | Where-Object { $steps[$_] -eq $false })
    $results += [pscustomobject]@{
        Serial = $s
        Pid    = $pid_
        Status = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL: ' + ($failed -join ',') }
    }
    Say ''
}

Say '======== SUMMARY ========' Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$bad = @($results | Where-Object { $_.Status -ne 'PASS' })
if ($bad.Count -gt 0) { Say "$($bad.Count) device(s) FAILED" Red; exit 1 }
Say 'All devices PASS' Green
Say ''
Say 'Keep this open in a dedicated terminal (per device):' DarkGray
foreach ($s in $serials) { Say "  adb -s $s logcat -c; adb -s $s logcat -s PTLAB:* chromium:I" DarkGray }
exit 0
