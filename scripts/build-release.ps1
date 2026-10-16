<#
.SYNOPSIS
Build, align, sign and verify the Guaili Release APK.
.DESCRIPTION
Defaults to the existing Android debug keystore for compatibility with previous
development releases. Never creates a keystore. For another key, supply
-KeystorePath and -KeyAlias. Passwords are read from GUAILI_STORE_PASSWORD and
GUAILI_KEY_PASSWORD when present; otherwise apksigner prompts for them.
Relative paths are resolved from the project root. Does not install the APK.
.EXAMPLE
.\scripts\build-release.ps1
.EXAMPLE
.\scripts\build-release.ps1 -KeystorePath 'D:\keys\guaili.jks' -KeyAlias guaili
#>
[CmdletBinding()]
param(
    [string]$KeystorePath,
    [string]$KeyAlias,
    [string]$OutputApk = 'dist\release\GuailiAndroid-release.apk',
    [string]$JavaHome,
    [string]$SdkPath,
    [string]$BuildToolsVersion,
    [ValidateRange(1, 64)][int]$MaxWorkers = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousJavaHome = $env:JAVA_HOME
$temporaryFiles = @()

function Resolve-ProjectPath([string]$Value) {
    if ([System.IO.Path]::IsPathRooted($Value)) { return [System.IO.Path]::GetFullPath($Value) }
    return [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Value))
}

function Invoke-ReleaseTool([string]$Tool, [string[]]$ToolArguments, [string]$Failure) {
    & $Tool @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit $LASTEXITCODE)" }
}

Push-Location -LiteralPath $projectRoot
try {
    $customKey = -not [string]::IsNullOrWhiteSpace($KeystorePath)
    if (-not $customKey) { $KeystorePath = Join-Path $env:USERPROFILE '.android\debug.keystore' }
    $KeystorePath = Resolve-ProjectPath $KeystorePath
    if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) {
        throw "Keystore not found: $KeystorePath. Restore the original key; this script never generates one."
    }
    if ([string]::IsNullOrWhiteSpace($KeyAlias)) {
        if ($customKey) { throw 'Specify -KeyAlias when using -KeystorePath.' }
        $KeyAlias = 'androiddebugkey'
    }

    if ([string]::IsNullOrWhiteSpace($JavaHome)) {
        $JavaHome = @($env:JAVA_HOME, 'D:\Program Files\Android\Android Studio\jbr',
            "$env:ProgramFiles\Android\Android Studio\jbr") | Where-Object {
            $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') -PathType Leaf)
        } | Select-Object -First 1
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
        throw 'JDK not found. Specify -JavaHome pointing to a compatible JDK (Android Studio jbr is recommended).'
    }
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path

    if ([string]::IsNullOrWhiteSpace($SdkPath)) {
        $localProperties = Join-Path $projectRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
                Select-Object -First 1
            if ($sdkLine) { $SdkPath = (($sdkLine -split '=', 2)[1].Trim() -replace '\\:', ':') -replace '\\\\', '\' }
        }
        if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
        if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
        if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    }
    $toolsRoot = Join-Path (Resolve-ProjectPath $SdkPath) 'build-tools'
    if (-not (Test-Path -LiteralPath $toolsRoot -PathType Container)) {
        throw "SDK Build Tools not found: $toolsRoot. Specify -SdkPath."
    }
    if ($BuildToolsVersion) {
        if ($BuildToolsVersion -notmatch '^\d+\.\d+\.\d+(?:-[\w.-]+)?$') { throw 'Invalid Build Tools version.' }
        $releaseTools = Join-Path $toolsRoot $BuildToolsVersion
    } else {
        $installedTools = @(Get-ChildItem -LiteralPath $toolsRoot -Directory | Where-Object {
            $_.Name -match '^\d+\.\d+\.\d+$' -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat')) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'zipalign.exe'))
        } | Sort-Object { [version]$_.Name } -Descending)
        if ($installedTools.Count -eq 0) { throw 'No stable SDK Build Tools containing apksigner and zipalign were found.' }
        $releaseTools = $installedTools[0].FullName
    }
    $signer = Join-Path $releaseTools 'apksigner.bat'
    $aligner = Join-Path $releaseTools 'zipalign.exe'
    foreach ($tool in @($signer, $aligner)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Tool not found: $tool" }
    }

    $unsignedApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
    $OutputApk = Resolve-ProjectPath $OutputApk
    if ($OutputApk -eq $unsignedApk) { throw '-OutputApk must not replace the Gradle unsigned APK.' }
    if ([System.IO.Path]::GetExtension($OutputApk) -ne '.apk') { throw '-OutputApk must end with .apk.' }

    Write-Host "Build Tools: $releaseTools"
    Write-Host "Signing key: $KeystorePath (alias: $KeyAlias)"
    if (-not $customKey) { Write-Host 'Using the existing DEVELOPMENT certificate, not a production signing key.' }
    Invoke-ReleaseTool (Join-Path $projectRoot 'gradlew.bat') @(
        ':app:assembleRelease', '--console=plain', "--max-workers=$MaxWorkers"
    ) 'Release build failed'
    if (-not (Test-Path -LiteralPath $unsignedApk -PathType Leaf)) { throw "Unsigned APK not found: $unsignedApk" }

    $outputDirectory = Split-Path $OutputApk -Parent
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $runId = [guid]::NewGuid().ToString('N')
    $alignedApk = Join-Path $outputDirectory ".$runId-aligned.apk"
    $signedTemporaryApk = Join-Path $outputDirectory ".$runId-signed.apk"
    $temporaryFiles = @($alignedApk, $signedTemporaryApk)
    Invoke-ReleaseTool $aligner @('-P', '16', '4', $unsignedApk, $alignedApk) 'APK alignment failed'

    $signArguments = @('sign', '--ks', $KeystorePath, '--ks-key-alias', $KeyAlias,
        '--v4-signing-enabled', 'false', '--out', $signedTemporaryApk)
    if ($env:GUAILI_STORE_PASSWORD) { $signArguments += @('--ks-pass', 'env:GUAILI_STORE_PASSWORD') }
    elseif (-not $customKey) { $signArguments += @('--ks-pass', 'pass:android') }
    if ($env:GUAILI_KEY_PASSWORD) { $signArguments += @('--key-pass', 'env:GUAILI_KEY_PASSWORD') }
    elseif (-not $customKey) { $signArguments += @('--key-pass', 'pass:android') }
    $signArguments += $alignedApk
    Invoke-ReleaseTool $signer $signArguments 'APK signing failed'
    Invoke-ReleaseTool $signer @('verify', '--verbose', '--print-certs', $signedTemporaryApk) 'APK signature verification failed'
    Invoke-ReleaseTool $aligner @('-c', '-P', '16', '4', $signedTemporaryApk) 'Signed APK alignment verification failed'

    # Only replace the deliverable after both checks succeed.
    Move-Item -LiteralPath $signedTemporaryApk -Destination $OutputApk -Force
    $hash = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText("$OutputApk.sha256", "$hash  $([System.IO.Path]::GetFileName($OutputApk))`n",
        [System.Text.UTF8Encoding]::new($false))
    Write-Host "Signed Release APK: $OutputApk"
    Write-Host "SHA-256: $hash"
} finally {
    try {
        foreach ($temporaryFile in $temporaryFiles) {
            if (Test-Path -LiteralPath $temporaryFile -PathType Leaf) { Remove-Item -LiteralPath $temporaryFile -Force }
        }
    } finally {
        $env:JAVA_HOME = $previousJavaHome
        Pop-Location
    }
}
