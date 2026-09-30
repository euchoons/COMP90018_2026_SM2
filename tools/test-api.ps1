#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('New', 'Scoped')]
    [string]$Suite = 'Scoped',
    [switch]$CheckOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $root 'docs/testing/api-test-baseline.json'
$gradlePath = Join-Path $root 'gradlew.bat'

# Compare canonical LF text with Git's blob format; do not change the source file.
function Get-CanonicalTextBytes([string]$Path) {
    $text = [System.IO.File]::ReadAllText($Path)
    return ,([System.Text.Encoding]::UTF8.GetBytes($text.Replace("`r`n", "`n")))
}
function Get-GitTextBlob([string]$Path) {
    [byte[]]$content = Get-CanonicalTextBytes $Path
    [byte[]]$header = [System.Text.Encoding]::ASCII.GetBytes("blob $($content.Length)`0")
    [byte[]]$all = New-Object byte[] ($header.Length + $content.Length)
    [System.Array]::Copy($header, 0, $all, 0, $header.Length)
    [System.Array]::Copy($content, 0, $all, $header.Length, $content.Length)
    $hasher = [System.Security.Cryptography.SHA1]::Create()
    try { return ([System.BitConverter]::ToString($hasher.ComputeHash($all))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose() }
}
function Get-TextDigest([string]$Path) {
    [byte[]]$bytes = Get-CanonicalTextBytes $Path
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hasher.Dispose() }
}

Push-Location $root
try {
    if (-not (Test-Path -LiteralPath $gradlePath -PathType Leaf)) {
        throw 'Run this script inside the Android repository, after merging the package files. gradlew.bat was not found.'
    }
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'The API test manifest is missing.' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $problems = @()
    foreach ($source in $manifest.sources) {
        $path = Join-Path $root $source.path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $problems += "Missing source: $($source.path)"
        } elseif ((Get-GitTextBlob $path) -ne $source.gitBlob) {
            $problems += "Different source version: $($source.path)"
        }
    }
    $buildPath = Join-Path $root 'app/build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildPath -PathType Leaf)) {
        $problems += 'Missing app/build.gradle.kts'
    } else {
        $buildText = Get-Content -LiteralPath $buildPath -Raw
        foreach ($alias in @('libs.junit', 'libs.json', 'libs.mockk', 'libs.robolectric', 'libs.kotlinx.coroutines.test')) {
            if ($buildText -notmatch ('testImplementation\s*\(\s*' + [regex]::Escape($alias) + '\s*\)')) {
                $problems += "Existing test dependency declaration not found: $alias"
            }
        }
    }
    $selected = @($manifest.newTests)
    if ($Suite -eq 'Scoped') { $selected += @($manifest.existingTests) }
    foreach ($test in $selected) {
        $path = Join-Path $root $test.path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $problems += "Missing test: $($test.path)"
        } else {
            $count = [regex]::Matches([System.IO.File]::ReadAllText($path), '@Test\s+fun\s+').Count
            if ($count -ne [int]$test.expectedTests) {
                $problems += "Test inventory changed: $($test.path) (expected $($test.expectedTests), found $count)"
            }
        }
    }
    if ($problems.Count -gt 0) {
        throw ("Preflight stopped. No tests were run; no source was overwritten.`n" +
            ($problems -join "`n") + "`nCompare your branch with baseline $($manifest.baselineCommit). " +
            'Do not reset, delete your work, bypass this check, or change hashes just to make it pass.')
    }
    $expectedTotal = ($selected | Measure-Object -Property expectedTests -Sum).Sum
    Write-Host "Source preflight matched. Suite=$Suite; classes=$($selected.Count); expected methods=$expectedTotal."
    if ($CheckOnly) {
        Write-Host 'CHECK ONLY: compatibility checks completed; JUnit and Gradle were NOT run.'
        exit 0
    }

    $head = 'Unavailable: Git CLI not on PATH or not a Git working tree'
    $dirty = 'Unknown'
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $gitHead = & git rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0) { $head = [string]$gitHead }
        $gitStatus = @(& git status --porcelain 2>$null)
        if ($LASTEXITCODE -eq 0) { $dirty = [string]($gitStatus.Count -gt 0) }
    }
    $testDigests = @{}
    foreach ($test in $selected) { $testDigests[$test.class] = Get-TextDigest (Join-Path $root $test.path) }
    $resultsDir = Join-Path $root 'app/build/test-results/testDebugUnitTest'
    # Remove ONLY selected, generated XML reports so an old run cannot become new evidence.
    foreach ($test in $selected) {
        $oldReport = Join-Path $resultsDir ("TEST-" + $test.class + '.xml')
        if (Test-Path -LiteralPath $oldReport -PathType Leaf) { Remove-Item -LiteralPath $oldReport }
    }
    $gradleArgs = @(':app:testDebugUnitTest')
    foreach ($test in $selected) { $gradleArgs += @('--tests', [string]$test.class) }
    $gradleArgs += @('--rerun-tasks', '--no-build-cache', '--no-daemon', '--console=plain')
    $started = [DateTimeOffset]::UtcNow
    $gradleExit = -1
    $savedPreference = $ErrorActionPreference
    try {
        # Native stderr must not terminate PowerShell before the real exit code is collected.
        $ErrorActionPreference = 'Continue'
        & $gradlePath @gradleArgs
        $gradleExit = $LASTEXITCODE
        if ($null -eq $gradleExit) { $gradleExit = -1 }
    } finally { $ErrorActionPreference = $savedPreference }
    $finished = [DateTimeOffset]::UtcNow

    $rows = @()
    $methods = @()
    foreach ($test in $selected) {
        $row = [ordered]@{
            Class = [string]$test.class; Origin = 'Existing'; Expected = [int]$test.expectedTests
            Tests = 0; Failures = 0; Errors = 0; Skipped = 0; Status = 'NOT_RUN'
        }
        if (@($manifest.newTests.class) -contains $test.class) { $row.Origin = 'New' }
        $report = Join-Path $resultsDir ('TEST-' + $test.class + '.xml')
        if (Test-Path -LiteralPath $report -PathType Leaf) {
            try {
                $xml = New-Object System.Xml.XmlDocument
                $xml.XmlResolver = $null
                $xml.Load($report)
                $node = $xml.SelectSingleNode('/testsuite')
                if ($null -eq $node -or $node.GetAttribute('name') -ne $test.class) { throw 'Unexpected report schema.' }
                foreach ($field in @('Tests', 'Failures', 'Errors', 'Skipped')) {
                    $value = $node.GetAttribute($field.ToLowerInvariant())
                    $row[$field] = if ($value -eq '') { 0 } else { [int]$value }
                }
                $caseNodes = @($xml.SelectNodes('/testsuite/testcase'))
                $row.Status = 'PASS'
                if ($row.Failures -gt 0 -or $row.Errors -gt 0) { $row.Status = 'FAIL' }
                elseif ($row.Skipped -gt 0) { $row.Status = 'SKIPPED' }
                elseif ($row.Tests -ne $row.Expected -or $caseNodes.Count -ne $row.Expected) { $row.Status = 'INCOMPLETE' }
                foreach ($case in $caseNodes) {
                    $methodStatus = 'PASS'
                    if ($null -ne $case.SelectSingleNode('failure') -or $null -ne $case.SelectSingleNode('error')) { $methodStatus = 'FAIL' }
                    elseif ($null -ne $case.SelectSingleNode('skipped')) { $methodStatus = 'SKIPPED' }
                    $name = $case.GetAttribute('name').Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
                    $methods += "| $($test.class) | $name | $methodStatus |"
                }
            } catch { $row.Status = 'INVALID_REPORT' }
        }
        $rows += [pscustomobject]$row
    }
    $complete = @($rows | Where-Object Status -ne 'PASS').Count -eq 0
    $overall = 'INCOMPLETE'
    if ($gradleExit -eq 0 -and $complete) { $overall = 'PASS' }
    elseif (@($rows | Where-Object Status -eq 'FAIL').Count -gt 0) { $overall = 'FAIL' }
    elseif ($gradleExit -ne 0) { $overall = 'BLOCKED' }

    $stamp = $started.ToString('yyyyMMdd-THHmmss-fffZ')
    $evidenceDir = Join-Path $root "docs/evidence/api-tests/$stamp-$Suite"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    $lines = @(
        '# FloraGuide API local-test execution', '',
        "Overall result: **$overall**", '',
        "Suite: $Suite", "Started UTC: $($started.ToString('o'))", "Finished UTC: $($finished.ToString('o'))",
        "Git HEAD: $head", "Working tree has changes: $dirty", "Inspected baseline: $($manifest.baselineCommit)",
        "Production source fingerprint check: MATCHED (six scoped files)", "Expected test methods: $expectedTotal",
        "Gradle exit code: $gradleExit", '',
        '## Command', '', '```powershell', ('.\gradlew.bat ' + ($gradleArgs -join ' ')), '```', '',
        '## Results', '',
        '| Class | Origin | Expected | Run | Failures | Errors | Skipped | Result |',
        '|---|---|---:|---:|---:|---:|---:|---|'
    )
    foreach ($row in $rows) {
        $lines += "| $($row.Class) | $($row.Origin) | $($row.Expected) | $($row.Tests) | $($row.Failures) | $($row.Errors) | $($row.Skipped) | $($row.Status) |"
    }
    $lines += @('', '## Method results from newly generated XML', '', '| Class | Method | Result |', '|---|---|---|')
    $lines += $methods
    $lines += @('', '## Test source fingerprints (SHA-256, canonical LF)', '', '| Class | Digest |', '|---|---|')
    foreach ($test in $selected) { $lines += "| $($test.class) | $($testDigests[$test.class]) |" }
    $lines += @('', '## Limits and review', '',
        'Local JVM/Robolectric tests with synthetic HTTP and mocked Firebase SDK only. No real cloud requests, deployed Rules validation, hardware, accuracy or latency measurements.',
        'The complete project test/lint/build gate and human review are separate and are NOT certified by this scoped result.',
        'Record the JDK/Gradle versions from gradlew.bat --version, reviewer, and project-wide gate result in the PR. Review this summary before committing it.',
        'Raw HTML/XML remain under app/build and are not bundled into this evidence directory.'
    )
    $summaryPath = Join-Path $evidenceDir 'summary.md'
    [System.IO.File]::WriteAllLines($summaryPath, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "API test result: $overall. Evidence: docs/evidence/api-tests/$stamp-$Suite/summary.md"
    if ($overall -ne 'PASS') { exit 1 }
    exit 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
} finally { Pop-Location }
