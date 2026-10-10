@echo off
rem Harmony for Windows - dezinstalare.
rem Dublu-clic pe acest fisier; Windows cere permisiunea de administrator.
rem Merge si cand dezinstalarea din Setari se opreste cu o eroare, de exemplu
rem "Could not set file security ... Config.Msi ... Error: 5" sau "The error code is 2318":
rem daca Windows Installer nu poate scoate Harmony, scriptul il scoate el
rem (folderul, scurtaturile si inregistrarea din Windows).
title Harmony - dezinstalare
net session >nul 2>&1 || (
    echo Cer drepturi de administrator...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s = Get-Content -LiteralPath '%~f0' -Raw; Invoke-Expression ($s.Substring($s.LastIndexOf('#' + 'PS-START')))"
echo.
pause
exit /b

#PS-START
$UpgradeCode = '{6F3D6A7E-6B1E-4C86-9D5E-6A3F4F0E8C21}'
$log = Join-Path $env:TEMP 'Harmony-dezinstalare.log'

# Windows Installer keeps codes "packed": each part of the GUID reversed.
function Pack([string]$guid) {
    $g = $guid.Trim('{}').Replace('-', '').ToUpper()
    $o = (-join $g[7..0]) + (-join $g[11..8]) + (-join $g[15..12])
    for ($i = 16; $i -lt 32; $i += 2) { $o += "$($g[$i + 1])$($g[$i])" }
    $o
}
function Unpack([string]$packed) {
    $g = Pack $packed
    '{' + $g.Substring(0, 8) + '-' + $g.Substring(8, 4) + '-' + $g.Substring(12, 4) + '-' + $g.Substring(16, 4) + '-' + $g.Substring(20) + '}'
}

$users = @(Get-ChildItem Registry::HKEY_USERS -ErrorAction SilentlyContinue | ForEach-Object { "Registry::$($_.Name)" })
$uninstallRoots = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
) + @($users | ForEach-Object { "$_\Software\Microsoft\Windows\CurrentVersion\Uninstall" })
$installerRoots = @('HKCU:\Software\Microsoft\Installer', 'HKLM:\SOFTWARE\Classes\Installer') +
    @($users | ForEach-Object { "$_\Software\Microsoft\Installer" })
$userData = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData'
$managed = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\Managed'

# Every Harmony Windows Installer knows of: from the uninstall list and from Harmony's upgrade code.
$entries = foreach ($r in $uninstallRoots) {
    Get-ChildItem $r -ErrorAction SilentlyContinue | Get-ItemProperty -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Harmony' -and $_.PSChildName -match '^\{[0-9A-Fa-f-]+\}$' }
}
$codes = @($entries | ForEach-Object { $_.PSChildName.ToUpper() })
foreach ($r in $installerRoots) {
    $k = Get-Item "$r\UpgradeCodes\$(Pack $UpgradeCode)" -ErrorAction SilentlyContinue
    if ($k) { $codes += @($k.GetValueNames() | Where-Object { $_ -match '^[0-9A-Fa-f]{32}$' } | ForEach-Object { Unpack $_ }) }
}
$codes = @($codes | Sort-Object -Unique)

# Harmony's own folders: only those that hold Harmony.exe and app\Harmony.cfg (so nothing else named Harmony is touched).
$candidates = @("$env:LOCALAPPDATA\Harmony", "$env:LOCALAPPDATA\Programs\Harmony", "$env:ProgramFiles\Harmony", "${env:ProgramFiles(x86)}\Harmony")
foreach ($e in $entries) {
    if ($e.InstallLocation) { $candidates += $e.InstallLocation.TrimEnd('\') }
    if ($e.DisplayIcon) { $candidates += (Split-Path ($e.DisplayIcon -split ',')[0].Trim('"') -Parent) }
}
foreach ($d in (Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue)) {
    $candidates += "$($d.Root)Harmony"
    $candidates += "$($d.Root)Program Files\Harmony"
}
$folders = @($candidates | Where-Object { $_ } | Sort-Object -Unique |
    Where-Object { (Test-Path -LiteralPath "$_\Harmony.exe") -and (Test-Path -LiteralPath "$_\app\Harmony.cfg") })

if ($codes.Count -eq 0 -and $folders.Count -eq 0) {
    Write-Host 'Harmony nu pare instalat pe acest PC.'
    return
}

Get-Process -Name Harmony -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

# A clean temporary folder: a missing or broken TEMP is a common cause of Windows Installer errors.
$tmp = Join-Path $env:SystemRoot 'Temp\HarmonyUninstall'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp

$stuck = @()
foreach ($code in $codes) {
    # Without its cached copy of the package Windows Installer can't uninstall (error 2318 / 1612)
    # and with a window it even waits for the original .msi: then go straight to removing it here.
    $pp = Pack $code
    $cached = Get-ChildItem $userData -ErrorAction SilentlyContinue | ForEach-Object {
        (Get-ItemProperty "$($_.PSPath)\Products\$pp\InstallProperties" -ErrorAction SilentlyContinue).LocalPackage
    } | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    if (-not $cached) {
        Write-Host "Harmony $code nu mai are pachetul de dezinstalare al Windows; il scot eu."
        $stuck += $code
        continue
    }
    Write-Host "Dezinstalez Harmony $code ..."
    $p = Start-Process msiexec.exe -ArgumentList "/x $code /qb /norestart /l*v `"$log`"" -PassThru
    $null = $p.Handle  # keeps the exit code readable after WaitForExit
    if (-not $p.WaitForExit(300000)) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        Write-Host 'Windows Installer nu a terminat in 5 minute; il scot eu.'
        $stuck += $code
        continue
    }
    if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) {
        Write-Host 'Gata: Windows Installer a dezinstalat Harmony.'
    } elseif ($p.ExitCode -eq 1605) {
        Write-Host 'Windows Installer nu il mai stie; curat ce a ramas.'
        $stuck += $code
    } else {
        Write-Host "Windows Installer s-a oprit cu codul $($p.ExitCode); il scot eu."
        $stuck += $code
    }
}

foreach ($code in $stuck) {
    $pp = Pack $code
    # The installer's cached copy of the package.
    Get-ChildItem $userData -ErrorAction SilentlyContinue | ForEach-Object {
        $props = Get-ItemProperty "$($_.PSPath)\Products\$pp\InstallProperties" -ErrorAction SilentlyContinue
        if ($props -and $props.LocalPackage -and $props.LocalPackage -like "$env:SystemRoot\Installer\*") {
            Remove-Item -LiteralPath $props.LocalPackage -Force -ErrorAction SilentlyContinue
        }
        Remove-Item "$($_.PSPath)\Products\$pp" -Recurse -Force -ErrorAction SilentlyContinue
    }
    Get-ChildItem $managed -ErrorAction SilentlyContinue | ForEach-Object {
        Remove-Item "$($_.PSPath)\Installer\Products\$pp", "$($_.PSPath)\Installer\Features\$pp" -Recurse -Force -ErrorAction SilentlyContinue
    }
    foreach ($r in $installerRoots) {
        Remove-Item "$r\Products\$pp", "$r\Features\$pp" -Recurse -Force -ErrorAction SilentlyContinue
        Get-ChildItem "$r\UpgradeCodes" -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.GetValueNames() -contains $pp) {
                Remove-ItemProperty -LiteralPath $_.PSPath -Name $pp -ErrorAction SilentlyContinue
                if ((Get-Item -LiteralPath $_.PSPath).ValueCount -eq 0) { Remove-Item -LiteralPath $_.PSPath -Force -ErrorAction SilentlyContinue }
            }
        }
    }
    foreach ($r in $uninstallRoots) { Remove-Item "$r\$code" -Recurse -Force -ErrorAction SilentlyContinue }
    # Harmony's entries in the shared list of installed components.
    $hklm = [Microsoft.Win32.Registry]::LocalMachine
    $ud = $hklm.OpenSubKey('SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData')
    if ($ud) {
        foreach ($sid in $ud.GetSubKeyNames()) {
            $comps = $hklm.OpenSubKey("SOFTWARE\Microsoft\Windows\CurrentVersion\Installer\UserData\$sid\Components", $true)
            if (-not $comps) { continue }
            foreach ($c in $comps.GetSubKeyNames()) {
                $ck = $comps.OpenSubKey($c, $true)
                if ($ck -and $ck.GetValue($pp) -ne $null) {
                    $ck.DeleteValue($pp, $false)
                    $empty = $ck.ValueCount -eq 0
                    $ck.Close()
                    if ($empty) { $comps.DeleteSubKeyTree($c, $false) }
                } elseif ($ck) { $ck.Close() }
            }
            $comps.Close()
        }
        $ud.Close()
    }
}

# What's left on disk: the program folder and its shortcuts.
foreach ($f in $folders) {
    if (Test-Path -LiteralPath $f) {
        Remove-Item -LiteralPath $f -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath "$f\Harmony.exe") { Write-Host "Nu am putut sterge $f (reporneste PC-ul si ruleaza din nou)." }
        else { Write-Host "Sters: $f" }
    }
}
$shell = New-Object -ComObject WScript.Shell
$places = @([Environment]::GetFolderPath('Programs'), [Environment]::GetFolderPath('CommonPrograms'),
            [Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory'))
foreach ($place in $places | Where-Object { $_ -and (Test-Path -LiteralPath $_) }) {
    Get-ChildItem -LiteralPath $place -Filter 'Harmony*.lnk' -Recurse -Depth 1 -ErrorAction SilentlyContinue | ForEach-Object {
        $target = $shell.CreateShortcut($_.FullName).TargetPath
        if ($target -like '*\Harmony.exe' -and -not (Test-Path -LiteralPath $target)) {
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
            $parent = Split-Path $_.FullName -Parent
            if ((Split-Path $parent -Leaf) -eq 'Harmony' -and -not (Get-ChildItem -LiteralPath $parent -Force)) { Remove-Item -LiteralPath $parent -Force }
        }
    }
}
Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
if ($stuck.Count -gt 0 -or $codes.Count -eq 0) { Write-Host 'Gata: Harmony a fost scos de pe PC. Acum poti instala versiunea noua.' }
if (Test-Path $log) { Write-Host "Jurnalul Windows Installer: $log" }
Write-Host "Setarile si biblioteca raman in $env:APPDATA\Harmony; sterge folderul daca nu le mai vrei."
