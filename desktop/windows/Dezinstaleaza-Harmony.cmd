@echo off
rem Harmony for Windows - dezinstalare.
rem Dublu-clic pe acest fisier; Windows cere permisiunea de administrator.
rem Merge si cand dezinstalarea din Setari da "Could not set file security ... Config.Msi ... Error: 5"
rem (Harmony instalat pe alt disc decat C:).
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
$roots = @(
    'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
)
$roots += Get-ChildItem Registry::HKEY_USERS -ErrorAction SilentlyContinue |
    ForEach-Object { "Registry::$($_.Name)\Software\Microsoft\Windows\CurrentVersion\Uninstall" }
$apps = foreach ($r in $roots) {
    Get-ChildItem $r -ErrorAction SilentlyContinue | Get-ItemProperty -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Harmony' -and $_.PSChildName -match '^\{[0-9A-Fa-f-]+\}$' }
}
$apps = @($apps | Sort-Object PSChildName -Unique)
if ($apps.Count -eq 0) {
    Write-Host 'Harmony nu pare instalat pe acest PC.'
    return
}
Get-Process -Name Harmony -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
foreach ($a in $apps) {
    Write-Host "Dezinstalez Harmony $($a.DisplayVersion) $($a.PSChildName) ..."
    $p = Start-Process msiexec.exe -ArgumentList "/x $($a.PSChildName) /qb /norestart" -Wait -PassThru
    if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) {
        Write-Host 'Gata: Harmony a fost dezinstalat.'
    } else {
        Write-Host "msiexec s-a oprit cu codul $($p.ExitCode)."
    }
}
Write-Host ''
Write-Host "Setarile si biblioteca raman in $env:APPDATA\Harmony; sterge folderul daca nu le mai vrei."
