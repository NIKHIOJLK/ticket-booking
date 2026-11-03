# Shared helpers for setup.ps1 and start.ps1 (Windows PowerShell 5.1 or later).

$Root = Split-Path $PSScriptRoot -Parent
$Backend = Join-Path $Root "backend"
$Frontend = Join-Path $Root "frontend"
$Logs = Join-Path $Root "logs"
$EnvFile = Join-Path $Backend ".env"
$ConveyorDir = Join-Path (Split-Path $Root -Parent) "conveyor"

New-Item -ItemType Directory -Force -Path $Logs | Out-Null

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    OK   $Message" -ForegroundColor Green
}

function Write-Problem([string]$Message) {
    Write-Host "    !!   $Message" -ForegroundColor Yellow
}

# Reads KEY=VALUE lines (ignores blank lines and # comments).
function Read-DotEnv([string]$Path) {
    $vars = @{}
    if (Test-Path $Path) {
        foreach ($line in Get-Content $Path) {
            $t = $line.Trim()
            if ($t -eq "" -or $t.StartsWith("#")) { continue }
            $i = $t.IndexOf("=")
            if ($i -gt 0) { $vars[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim() }
        }
    }
    return $vars
}

# Writes UTF-8 without a byte-order mark (a BOM would break the first setting).
function Write-TextFile([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding $false))
}

function New-Secret {
    $bytes = New-Object byte[] 36
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $rng.GetBytes($bytes)
    return ([Convert]::ToBase64String($bytes)).Replace("+", "A").Replace("/", "B").Replace("=", "")
}

# Folder with psql.exe and pg_dump.exe.
function Find-PgBin {
    $cmd = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($cmd) { return Split-Path $cmd.Source }
    $patterns = @("Program Files\PostgreSQL\*\bin\psql.exe", "PostgreSQL\*\bin\psql.exe", "*\PostgreSQL\*\bin\psql.exe")
    $found = @()
    foreach ($drive in Get-PSDrive -PSProvider FileSystem) {
        foreach ($p in $patterns) {
            $found += @(Get-ChildItem -Path (Join-Path $drive.Root $p) -ErrorAction SilentlyContinue)
        }
    }
    if ($found.Count -eq 0) { return $null }
    # ...\PostgreSQL\<version>\bin\psql.exe: newest version first.
    $best = $found | Sort-Object { [int](((Split-Path (Split-Path $_.FullName)) | Split-Path -Leaf) -replace "\D", "") } -Descending |
        Select-Object -First 1
    return Split-Path $best.FullName
}

# Java major version of a JDK/JRE folder, or 0.
function Get-JavaMajor([string]$JavaHome) {
    $release = Join-Path $JavaHome "release"
    if (Test-Path $release) {
        $m = Select-String -Path $release -Pattern 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($m) { return [int]$m.Matches[0].Groups[1].Value }
    }
    $java = Join-Path $JavaHome "bin\java.exe"
    if (Test-Path $java) {
        $out = (& $java -version 2>&1 | Out-String)
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    }
    return 0
}

# A Java 21+ installation for Maven. Also finds the Java that Eclipse brings with it.
function Find-JavaHome {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) { $candidates += (Split-Path (Split-Path $javaCmd.Source)) }
    $globs = @(
        "$env:ProgramFiles\Java\jdk*",
        "$env:ProgramFiles\Eclipse Adoptium\jdk*",
        "$env:ProgramFiles\Microsoft\jdk*",
        "$env:ProgramFiles\Amazon Corretto\jdk*",
        "$env:ProgramFiles\Zulu\zulu*",
        "$env:ProgramFiles\BellSoft\*",
        "$env:USERPROFILE\.jdks\*",
        "$env:USERPROFILE\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*\jre",
        "$env:USERPROFILE\eclipse\*\eclipse\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*\jre",
        "$env:USERPROFILE\eclipse\*\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*\jre",
        "$env:USERPROFILE\.vscode\extensions\redhat.java-*\jre\*"
    )
    foreach ($g in $globs) {
        $candidates += @(Get-ChildItem -Path $g -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
    $usable = $candidates | Where-Object { $_ -and (Test-Path (Join-Path $_ "bin\java.exe")) -and ((Get-JavaMajor $_) -ge 21) }
    # Prefer a full JDK (with javac) over a runtime.
    $jdk = $usable | Where-Object { Test-Path (Join-Path $_ "bin\javac.exe") } | Select-Object -First 1
    if ($jdk) { return $jdk }
    return ($usable | Select-Object -First 1)
}

# postgres://postgres:<password>@localhost:5432/<db> with the password URL-encoded.
function Get-PgUrl([hashtable]$EnvVars, [string]$Database) {
    $user = if ($EnvVars["DB_USERNAME"]) { $EnvVars["DB_USERNAME"] } else { "postgres" }
    $pw = [Uri]::EscapeDataString($EnvVars["DB_PASSWORD"])
    return "postgres://${user}:$pw@localhost:5432/$Database"
}
