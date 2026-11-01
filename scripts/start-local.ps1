param([switch]$SeedDemo, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    foreach ($name in @('java', 'docker')) {
        if (-not (Get-Command $name -CommandType Application -ErrorAction SilentlyContinue)) {
            throw "Missing $name. Install the prerequisite and add it to PATH."
        }
    }
    if (-not (Test-Path -LiteralPath 'backend/.env')) {
        throw 'Copy backend/.env.example to backend/.env and configure your own database passwords first.'
    }
    $settings = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $repoRoot 'backend/.env'))) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $parts = $line.Split('=', 2)
        if ($parts.Length -eq 2) { $settings[$parts[0].Trim()] = $parts[1].Trim() }
    }
    foreach ($key in @('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD')) {
        if ([string]::IsNullOrWhiteSpace($settings[$key])) { throw "Set $key in backend/.env." }
    }
    if ($SeedDemo) {
        foreach ($key in @('SEED_CUSTOMER_A_PASSWORD', 'SEED_CUSTOMER_B_PASSWORD', 'SEED_STAFF_PASSWORD')) {
            if ([string]::IsNullOrWhiteSpace($settings[$key]) -or $settings[$key].Length -lt 12 -or [Text.Encoding]::UTF8.GetByteCount($settings[$key]) -gt 72) {
                throw "Set $key to a password of at least 12 characters and at most 72 UTF-8 bytes."
            }
        }
    }
    if (-not $SkipBuild) {
        if (-not (Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue)) { throw 'Maven 3.9+ is required on PATH.' }
        & mvn -B -ntp -f backend/pom.xml package
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $jar = 'backend/target/printer-after-sales-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Build the backend JAR first.' }
    & docker compose --env-file backend/.env up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Docker dependencies failed to start. Check Docker Desktop and port availability.' }
    if ($SeedDemo) {
        & java -jar $jar --seed-demo
        if ($LASTEXITCODE -ne 0) { throw 'Sample data initialization failed.' }
    }
    $port = if ($settings['APP_PORT']) { $settings['APP_PORT'] } else { '8080' }
    Write-Host "Open http://127.0.0.1:$port/app/ after startup. Press Ctrl+C to stop the application."
    & java -jar $jar
    if ($LASTEXITCODE -ne 0) { throw 'Application exited with a non-zero status.' }
} finally { Pop-Location }
