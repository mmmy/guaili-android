param(
    [string]$Serial = 'emulator-5570',
    [ValidateSet('Profiles', 'Benchmark')][string]$Mode = 'Benchmark',
    [switch]$AllowEmulator
)
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $taskArguments = @(':app:assembleBenchmark', ':benchmark:assembleBenchmark', '--console=plain', '--max-workers=2')
    if ($Mode -eq 'Profiles') { $taskArguments += '-PgenerateBaselineProfile=true' }
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Performance APK build failed.' }
    & adb -s $Serial install -r app/build/outputs/apk/benchmark/app-benchmark.apk
    if ($LASTEXITCODE -ne 0) { throw 'Isolated performance app installation failed.' }
    & adb -s $Serial install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
    if ($LASTEXITCODE -ne 0) { throw 'Benchmark test installation failed.' }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $remoteOutput = "/sdcard/Android/media/com.gouge.guaili.benchmark/guaili-performance-$stamp"
    $localOutput = Join-Path (Get-Location) "build/performance/$Mode-$stamp"
    New-Item -ItemType Directory -Force $localOutput | Out-Null
    if ($Mode -eq 'Benchmark') {
        # Loading target code registers a process dependency. End this instrumentation before
        # Macrobenchmark force-stops the app, so it cannot also stop its own test runner.
        $runtimeCheck = & adb -s $Serial shell am instrument -w -r -e class com.gouge.guaili.benchmark.OptimizedRuntimeSmokeTest com.gouge.guaili.benchmark/androidx.test.runner.AndroidJUnitRunner
        $runtimeCheck | Tee-Object -FilePath (Join-Path $localOutput 'runtime-smoke.txt')
        if ($LASTEXITCODE -ne 0 -or ($runtimeCheck -join "`n") -notmatch 'OK \(1 test\)') { throw 'Optimized runtime constructor checks failed.' }
        & adb -s $Serial shell am force-stop com.gouge.guaili.benchmark
    }
    $testClass = if ($Mode -eq 'Profiles') { 'BaselineProfileGenerator' } else { 'StartupBenchmark' }
    $instrumentArguments = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', "com.gouge.guaili.benchmark.$testClass",
        '-e', 'additionalTestOutputDir', $remoteOutput)
    if ($AllowEmulator) { $instrumentArguments += @('-e', 'androidx.benchmark.suppressErrors', 'EMULATOR') }
    $instrumentArguments += 'com.gouge.guaili.benchmark/androidx.test.runner.AndroidJUnitRunner'
    $result = & adb @instrumentArguments
    $result | Tee-Object -FilePath (Join-Path $localOutput 'instrumentation.txt')
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Performance tests failed.' }
    & adb -s $Serial pull $remoteOutput $localOutput
    if ($LASTEXITCODE -ne 0) { throw 'Performance output download failed.' }
    if ($Mode -eq 'Profiles') {
        $profileDirectory = Join-Path (Get-Location) 'app/src/main/baselineProfiles'
        New-Item -ItemType Directory -Force $profileDirectory | Out-Null
        foreach ($kind in @('baseline', 'startup')) {
            # collect(includeInStartupProfile = true) names its only output startup-prof;
            # those methods must also participate in baseline compilation.
            $profiles = @(Get-ChildItem -LiteralPath $localOutput -Recurse -File -Filter '*-prof.txt' | Where-Object {
                $_.Name -like "*-$kind-prof.txt" -or ($kind -eq 'baseline' -and $_.Name -like '*-startup-prof.txt')
            })
            if ($profiles.Count -eq 0) { throw "No $kind profile was generated." }
            $rules = @($profiles | Get-Content -Encoding UTF8 | Where-Object { $_ -and $_ -notmatch 'Lcom/gouge/guaili/performance/' } | Sort-Object -Unique -CaseSensitive)
            [System.IO.File]::WriteAllLines((Join-Path $profileDirectory "$kind-prof.txt"), $rules, [System.Text.UTF8Encoding]::new($false))
        }
    }
    Write-Output "Results: $localOutput"
} finally {
    Pop-Location
}
