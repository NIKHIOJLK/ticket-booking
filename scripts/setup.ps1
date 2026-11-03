# One-time setup and health check for Windows. From the ticket-booking folder run:
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\setup.ps1
#
# It is safe to run again at any time. It:
#   1. creates backend\.env (asks for your PostgreSQL password the first time)
#   2. backs up your ticket_booking database to backups\
#   3. creates the ticket_booking_test database used by the tests
#   4. updates Conveyor (next to this folder) and creates its tables in ticket_booking
#   5. runs the backend tests and builds the frontend
# Everything is written to logs\ so problems can be diagnosed.

$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "common.ps1")
$results = [ordered]@{}
$summaryFile = Join-Path $Logs "setup-summary.txt"

function Finish {
    Write-Host ""
    Write-Host "================ Summary ================" -ForegroundColor Cyan
    $lines = @()
    foreach ($k in $results.Keys) {
        $line = "{0,-28} {1}" -f $k, $results[$k]
        $lines += $line
        if ($results[$k] -like "OK*") { Write-Host $line -ForegroundColor Green } else { Write-Host $line -ForegroundColor Yellow }
    }
    Write-TextFile $summaryFile (($lines -join "`r`n") + "`r`n")
    Write-Host ""
    Write-Host "Logs are in $Logs" -ForegroundColor Cyan
    exit
}

# ---------------------------------------------------------------- 0. latest code
Write-Step "Getting the latest code"
if (Test-Path (Join-Path $Root ".git")) {
    git -C $Root pull --ff-only *> (Join-Path $Logs "git-pull.log")
    if ($LASTEXITCODE -eq 0) { $results["Latest code"] = "OK" } else { $results["Latest code"] = "skipped (see logs\git-pull.log)" }
} else {
    $results["Latest code"] = "skipped (not a git folder)"
}
Write-Host "    $($results['Latest code'])"

# ---------------------------------------------------------------- 1. PostgreSQL tools
Write-Step "Looking for PostgreSQL (psql.exe, pg_dump.exe)"
$pgBin = Find-PgBin
if (-not $pgBin) {
    Write-Problem "Couldn't find psql.exe. Is PostgreSQL installed?"
    $results["PostgreSQL tools"] = "NOT FOUND"
    Finish
}
$psql = Join-Path $pgBin "psql.exe"
$pgDump = Join-Path $pgBin "pg_dump.exe"
Write-Ok $pgBin
$results["PostgreSQL tools"] = "OK ($pgBin)"

# ---------------------------------------------------------------- 2. .env
Write-Step "Settings file backend\.env"
if (-not (Test-Path $EnvFile)) {
    $secure = Read-Host "    Your PostgreSQL password (user 'postgres')" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $admin = Read-Host "    Email that should be admin (create events, check-in) [test@example.com]"
    if (-not $admin) { $admin = "test@example.com" }
    $content = @"
# Created by scripts\setup.ps1. Not committed to git.
DB_URL=jdbc:postgresql://localhost:5432/ticket_booking
DB_USERNAME=postgres
DB_PASSWORD=$password
JWT_SECRET=$(New-Secret)
ADMIN_EMAILS=$admin
TASKS_SECRET=$(New-Secret)
HOLD_DURATION=PT10M
MAIL_MODE=outbox
"@
    Write-TextFile $EnvFile ($content.Replace("`r`n", "`n") + "`n")
    Write-Ok "created"
} else {
    Write-Ok "already exists"
}
$envVars = Read-DotEnv $EnvFile
$env:PGPASSWORD = $envVars["DB_PASSWORD"]
$pgUser = if ($envVars["DB_USERNAME"]) { $envVars["DB_USERNAME"] } else { "postgres" }

$check = & $psql -w -h localhost -U $pgUser -d postgres -tAc "SELECT 1" 2>&1
if ($LASTEXITCODE -ne 0) {
    ($check | Out-String) | Out-File -Encoding utf8 (Join-Path $Logs "psql-check.log")
    if (($check | Out-String) -match "password authentication failed") {
        Remove-Item $EnvFile
        Write-Problem "That password didn't work. Run the script again to re-enter it."
        $results["Database login"] = "WRONG PASSWORD"
    } else {
        Write-Problem "Can't connect to PostgreSQL. Is the service running? (Win+R, services.msc, start postgresql-x64-...)"
        $results["Database login"] = "CAN'T CONNECT (see logs\psql-check.log)"
    }
    Finish
}
$results["Database login"] = "OK"
Write-Ok "connected to PostgreSQL"

# ---------------------------------------------------------------- 3. backup + databases
Write-Step "Backing up ticket_booking"
$hasMain = (& $psql -w -h localhost -U $pgUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'ticket_booking'") -eq "1"
if ($hasMain) {
    $backups = Join-Path $Root "backups"
    New-Item -ItemType Directory -Force -Path $backups | Out-Null
    $file = Join-Path $backups ("ticket_booking-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".sql")
    & $pgDump -w -h localhost -U $pgUser -f $file ticket_booking 2> (Join-Path $Logs "pg_dump.log")
    if ($LASTEXITCODE -eq 0) { Write-Ok $file; $results["Backup"] = "OK ($file)" }
    else { Write-Problem "pg_dump failed (logs\pg_dump.log)"; $results["Backup"] = "FAILED (see logs\pg_dump.log)"; Finish }

    # The schema as it is now, so changes can be checked against it.
    & $pgDump -w -h localhost -U $pgUser --schema-only -f (Join-Path $Logs "schema-before.sql") ticket_booking 2>$null
} else {
    & $psql -w -h localhost -U $pgUser -d postgres -c "CREATE DATABASE ticket_booking" *> (Join-Path $Logs "createdb.log")
    Write-Ok "created a new, empty ticket_booking database"
    $results["Backup"] = "OK (new database, nothing to back up)"
}

Write-Step "Test database ticket_booking_test"
$hasTest = (& $psql -w -h localhost -U $pgUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'ticket_booking_test'") -eq "1"
if (-not $hasTest) {
    & $psql -w -h localhost -U $pgUser -d postgres -c "CREATE DATABASE ticket_booking_test" *> (Join-Path $Logs "createdb-test.log")
}
Write-Ok "ready"
$results["Test database"] = "OK"

# ---------------------------------------------------------------- 4. Conveyor
Write-Step "Conveyor (the job queue) in $ConveyorDir"
if (-not (Test-Path $ConveyorDir)) {
    git clone https://github.com/NIKHIOJLK/conveyor.git $ConveyorDir *> (Join-Path $Logs "conveyor-clone.log")
}
if (Test-Path $ConveyorDir) {
    git -C $ConveyorDir pull --ff-only *> (Join-Path $Logs "conveyor-pull.log")
    Push-Location $ConveyorDir
    cmd /c "npm install --no-audit --no-fund > `"$Logs\conveyor-install.log`" 2>&1"
    $env:DATABASE_URL = Get-PgUrl $envVars "ticket_booking"
    cmd /c "npm run migrate > `"$Logs\conveyor-migrate.log`" 2>&1"
    $code = $LASTEXITCODE
    Remove-Item Env:DATABASE_URL
    Pop-Location
    if ($code -eq 0) { Write-Ok "updated, and its tables exist in ticket_booking"; $results["Conveyor"] = "OK" }
    else { Write-Problem "migration failed (logs\conveyor-migrate.log)"; $results["Conveyor"] = "FAILED (see logs\conveyor-migrate.log)" }
} else {
    Write-Problem "couldn't get Conveyor (logs\conveyor-clone.log)"
    $results["Conveyor"] = "FAILED (see logs\conveyor-clone.log)"
}

# ---------------------------------------------------------------- 5. backend tests
Write-Step "Backend: compiling and running the tests (the first run downloads libraries, a few minutes)"
$javaHome = Find-JavaHome
if (-not $javaHome) {
    Write-Problem "No Java 21 found. Install a JDK 21 (e.g. from adoptium.net) and run this again."
    $results["Backend tests"] = "NO JAVA 21"
} else {
    $env:JAVA_HOME = $javaHome
    $env:Path = (Join-Path $javaHome "bin") + ";" + $env:Path
    Write-Host "    Using Java at $javaHome"
    Push-Location $Backend
    cmd /c "mvnw.cmd -B -ntp test > `"$Logs\backend-test.log`" 2>&1"
    $code = $LASTEXITCODE
    Pop-Location
    $summary = Select-String -Path (Join-Path $Logs "backend-test.log") -Pattern "Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$" |
        Select-Object -Last 1
    $counts = if ($summary) { $summary.Line.Trim() } else { "" }
    if ($code -eq 0) { Write-Ok "all tests passed. $counts"; $results["Backend tests"] = "OK $counts" }
    else { Write-Problem "build or tests failed (logs\backend-test.log)"; $results["Backend tests"] = "FAILED $counts (see logs\backend-test.log)" }
}

# ---------------------------------------------------------------- 6. frontend
Write-Step "Frontend: installing and building"
Push-Location $Frontend
cmd /c "npm ci --no-audit --no-fund > `"$Logs\frontend-install.log`" 2>&1"
$installCode = $LASTEXITCODE
cmd /c "npm run build > `"$Logs\frontend-build.log`" 2>&1"
$buildCode = $LASTEXITCODE
Pop-Location
if ($installCode -eq 0 -and $buildCode -eq 0) { Write-Ok "built"; $results["Frontend"] = "OK" }
else { Write-Problem "failed (logs\frontend-install.log, logs\frontend-build.log)"; $results["Frontend"] = "FAILED (see logs)" }

Write-Host ""
Write-Host "Next: powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1" -ForegroundColor Cyan
Finish
