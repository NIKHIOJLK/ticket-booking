# Starts everything, each in its own window:
#   backend (Spring Boot, port 8080), Conveyor's HTTP task worker, Conveyor's dashboard
#   (port 3000) and the React frontend (port 5173).
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1
#
# Close a window (or press Ctrl+C in it) to stop that part.

$ErrorActionPreference = "Continue"
. (Join-Path $PSScriptRoot "common.ps1")

if (-not (Test-Path $EnvFile)) {
    Write-Problem "backend\.env is missing. Run scripts\setup.ps1 first."
    exit 1
}
$envVars = Read-DotEnv $EnvFile
$javaHome = Find-JavaHome
if (-not $javaHome) { Write-Problem "No Java 21 found. Run scripts\setup.ps1 for details."; exit 1 }
$dbUrl = Get-PgUrl $envVars "ticket_booking"
$secret = $envVars["TASKS_SECRET"]

function Start-Window([string]$Title, [string]$Folder, [string]$Command) {
    $script = "`$host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$Folder'; $Command"
    Start-Process powershell -ArgumentList "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", $script | Out-Null
    Write-Ok "$Title"
}

Write-Step "Starting"

Start-Window "Ticket Booking - backend :8080" $Backend `
    "`$env:JAVA_HOME = '$javaHome'; `$env:LOGGING_FILE_NAME = '$Logs\backend.log'; .\mvnw.cmd -ntp spring-boot:run"

Start-Window "Conveyor - HTTP task worker" $ConveyorDir `
    "`$env:DATABASE_URL = '$dbUrl'; `$env:TASKS_URL = 'http://localhost:8080/internal/tasks'; `$env:TASKS_SECRET = '$secret'; npm run worker:http -- --queues booking-tasks,booking-emails 2>&1 | Tee-Object -FilePath '$Logs\worker.log'"

Start-Window "Conveyor - dashboard :3000" $ConveyorDir `
    "`$env:DATABASE_URL = '$dbUrl'; `$env:DEMO_MODE = 'false'; npm run api"

Start-Window "Ticket Booking - frontend :5173" $Frontend "npm run dev"

Write-Host ""
Write-Host "Give the backend about 20 seconds to start, then open:" -ForegroundColor Cyan
Write-Host "    App:            http://localhost:5173"
Write-Host "    Job dashboard:  http://localhost:3000"
Write-Host "    Emails:         $Backend\outbox  (each email is a folder with email.html and the ticket PDF)"
