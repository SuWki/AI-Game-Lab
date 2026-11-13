param(
    [Parameter(Mandatory=$true)][string]$GamePath,
    [switch]$ApplyResultFix,
    [switch]$ApplyReviewFeature,
    [switch]$InstallDependencies,
    [switch]$BrowserChecks,
    [ValidateRange(1024,65535)][int]$PreviewPort = 4174
)
$ErrorActionPreference = 'Stop'
$gameRoot = (Resolve-Path -LiteralPath $GamePath).Path
$baseline = 'bf3e44bf2c32fd3a585cc6b982531f16ef807d13'
if ($ApplyReviewFeature) { $ApplyResultFix = $true }
function Check-Exit([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)" }
}
Push-Location $gameRoot
try {
    $revision = (& git rev-parse HEAD).Trim()
    Check-Exit 'Read game version'
    if ($revision -ne $baseline) { throw "Unsupported demo revision: $revision; expected $baseline" }
    $patches = @()
    if ($ApplyResultFix) { $patches += 'result-summary.patch' }
    if ($ApplyReviewFeature) { $patches += 'run-review.patch' }
    foreach ($patch in $patches) {
        & git apply --check "$PSScriptRoot/$patch" 2>$null
        if ($LASTEXITCODE -eq 0) {
            & git apply "$PSScriptRoot/$patch"
            Check-Exit "Apply $patch"
        } else {
            & git apply --reverse --check "$PSScriptRoot/$patch" 2>$null
            Check-Exit 'Verify fix already applied (source conflict requires review)'
        }
    }
    foreach ($file in @('agl-engine-baseline.ts', 'result-summary.test.ts')) {
        Copy-Item -LiteralPath "$PSScriptRoot/$file" -Destination (Join-Path $gameRoot "tools/$file")
    }
    if ($InstallDependencies) { & npm ci --ignore-scripts; Check-Exit 'Install dependencies' }
    & npm run build
    Check-Exit 'Build'
    if ($ApplyResultFix) {
        & npm exec --yes --package=tsx@4.23.15 -- tsx tools/result-summary.test.ts
        Check-Exit 'Stage/outcome regression'
    }
    & npm exec --yes --package=tsx@4.23.15 -- tsx tools/agl-engine-baseline.ts
    Check-Exit 'Real engine baseline'
    if ($ApplyReviewFeature) {
        Copy-Item -LiteralPath "$PSScriptRoot/run-review.test.ts" -Destination (Join-Path $gameRoot 'tools/run-review.test.ts')
        & npm exec --yes --package=tsx@4.23.15 -- tsx tools/run-review.test.ts
        Check-Exit 'Run review export integrity'
    }
    $out = Join-Path $gameRoot 'output/qa/agl-engine'
    & node "$PSScriptRoot/check-result-consistency.mjs" "$out/summary.json" "$out/result-consistency.json"
    $findingExit = $LASTEXITCODE
    if ($findingExit -notin @(0,1)) { throw "Consistency checker crashed: $findingExit" }
    $finding = Get-Content -LiteralPath "$out/result-consistency.json" -Raw | ConvertFrom-Json
    if ($finding.id -ne 'GD-F01' -or $finding.checked -ne 18 -or
        ($finding.status -eq 'PASS') -ne ($findingExit -eq 0)) { throw 'Invalid consistency result' }
    $report = Get-Content -LiteralPath "$out/summary.json" -Raw | ConvertFrom-Json
    [ordered]@{
        revision=$revision; sourceMode= $(if($ApplyResultFix){'patched'}else{'as-is'})
        recordedAtUtc=[DateTime]::UtcNow.ToString('o'); consistencyExit=$findingExit
        engineSha256=(Get-FileHash src/systems/RunEngine.ts -Algorithm SHA256).Hash
        lockSha256=(Get-FileHash package-lock.json -Algorithm SHA256).Hash
        harnessSha256=(Get-FileHash tools/agl-engine-baseline.ts -Algorithm SHA256).Hash
        configHashes=@(Get-ChildItem src/data -Recurse -File | Sort-Object FullName | ForEach-Object {
            [ordered]@{ path=[IO.Path]::GetRelativePath($gameRoot,$_.FullName); sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
        total=$report.total; completed=$report.completed; wins=$report.wins
    } | ConvertTo-Json | Set-Content -LiteralPath "$out/execution.json" -Encoding utf8
    if ($findingExit -ne 0) { throw 'GD-F01 reproduced; see output/qa/agl-engine/result-consistency.json' }
    if ($BrowserChecks) {
        $reviewArgs = @()
        if ($ApplyReviewFeature) { $reviewArgs += '--expect-review' }
        & node "$PSScriptRoot/local-suite.mjs" $gameRoot $PreviewPort @reviewArgs
        Check-Exit 'Local browser suite'
    }
} finally { Pop-Location }
