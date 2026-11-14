# Runs the full test suite, including database tests, against the Neon "test" branch.
# No Docker needed. Requires the Neon CLI, signed in (neon login), and the folder linked (.neon).
# Usage:  .\scripts\test-with-neon.ps1            (extra args go to Maven, e.g. -Dtest=ApplicationIntegrationTest)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$projectId = (Get-Content (Join-Path $root '.neon') -Raw | ConvertFrom-Json).projectId

# Always the branch named "test" - never production, whatever .env.local points at.
$url = neon connection-string test --project-id $projectId
if ($LASTEXITCODE -ne 0 -or -not $url) { throw "Could not get the Neon 'test' branch connection string" }

$env:TEST_DATABASE_URL = $url
# Windows PowerShell 5.1 turns any stderr line from a native program into an error under 'Stop'
# (Maven prints harmless warnings there), so let Maven's exit code decide instead.
$ErrorActionPreference = 'Continue'
try {
    Push-Location $root
    & .\mvnw.cmd -B -ntp verify @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
    Remove-Item Env:TEST_DATABASE_URL -ErrorAction SilentlyContinue
}
