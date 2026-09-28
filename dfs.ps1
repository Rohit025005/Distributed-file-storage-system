# dfs.ps1 - one entry point for the distributed file storage system.
# Run  .\dfs.ps1 help  to see all commands.

param(
    [Parameter(Position = 0)] [string] $Command = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest
)

$Caller = (Get-Location).Path      # where the user ran the script from
$Root = $PSScriptRoot
Set-Location $Root                 # config/ and data/ paths are relative to the project root
$RunDir = Join-Path $Root "run"    # pid files and logs live here (not in data/, demo wipes data/)
$ClasspathFile = Join-Path $Root "target\classpath.txt"

Add-Type -AssemblyName System.Net.Http

$Metadata = @{ Name = "metadata"; Port = 9090; Class = "com.dfs.cmd.metadata.Main"; Args = "" }
$Nodes = @(
    @{ Name = "node1"; Port = 8081; Class = "com.dfs.cmd.storage.Main"; Args = "node1" },
    @{ Name = "node2"; Port = 8082; Class = "com.dfs.cmd.storage.Main"; Args = "node2" },
    @{ Name = "node3"; Port = 8083; Class = "com.dfs.cmd.storage.Main"; Args = "node3" },
    @{ Name = "node4"; Port = 8084; Class = "com.dfs.cmd.storage.Main"; Args = "node4" }
)
$All = @($Metadata) + $Nodes

# ---------- helpers ----------

function Fail($message) {
    Write-Host "ERROR: $message" -ForegroundColor Red
    exit 1
}

# Builds classes + classpath only when missing or when pom.xml / sources are newer.
function Ensure-Build {
    $needBuild = (-not (Test-Path $ClasspathFile)) -or (-not (Test-Path "target\classes"))
    if (-not $needBuild) {
        $builtAt = (Get-Item $ClasspathFile).LastWriteTime
        $files = @(Get-ChildItem "src\main" -Recurse -File) + (Get-Item "pom.xml")
        $newest = $files | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newest.LastWriteTime -gt $builtAt) { $needBuild = $true }
    }
    if ($needBuild) {
        Write-Host "Building (first run or sources changed)..."
        mvn -q compile
        if ($LASTEXITCODE -ne 0) { Fail "mvn compile failed" }
        mvn -q dependency:build-classpath "-Dmdep.outputFile=target/classpath.txt"
        if ($LASTEXITCODE -ne 0) { Fail "could not build classpath" }
    }
    $script:Classpath = "target\classes;" + (Get-Content $ClasspathFile -Raw).Trim()
}

# Returns the java process for a service, or $null.
# Removes stale pid files. Never returns a non-java process (PID reuse guard).
function Get-RunningProcess($name) {
    $pidFile = Join-Path $RunDir "$name.pid"
    if (-not (Test-Path $pidFile)) { return $null }
    $procId = 0
    $text = "$(Get-Content $pidFile -Raw)".Trim()
    if ([int]::TryParse($text, [ref]$procId)) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc -and $proc.ProcessName -eq "java") { return $proc }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
    return $null
}

# Returns "name (PID n)" of whoever is listening on the port, or $null.
function Get-PortOwner($port) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $conn) { return $null }
    $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
    $procName = "unknown"
    if ($proc) { $procName = $proc.ProcessName }
    return "$procName (PID $($conn.OwningProcess))"
}

# Returns latency in ms if /health answers 200, otherwise -1.
function Probe-Health($port) {
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMilliseconds(1500)
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = $client.GetAsync("http://127.0.0.1:$port/health").Result
        if ($response.IsSuccessStatusCode) { return [int]$timer.ElapsedMilliseconds }
        return -1
    }
    catch { return -1 }
    finally { $client.Dispose() }
}

function Get-LogFiles($name) {
    return @((Join-Path $RunDir "$name.out.log"), (Join-Path $RunDir "$name.err.log"))
}

function Show-LogTail($name) {
    foreach ($file in Get-LogFiles $name) {
        if (Test-Path $file) { Get-Content $file -Tail 15 }
    }
}

function Assert-PortsFree($targets) {
    $problems = @()
    foreach ($svc in $targets) {
        if (Get-RunningProcess $svc.Name) { continue }
        $owner = Get-PortOwner $svc.Port
        if ($owner) { $problems += "port $($svc.Port) ($($svc.Name)) is in use by $owner" }
    }
    if ($problems.Count -gt 0) { Fail ($problems -join "`n       ") }
}

function Start-Dfs($svc) {
    if (Get-RunningProcess $svc.Name) {
        Write-Host "$($svc.Name) already running"
        return
    }
    New-Item -ItemType Directory -Force $RunDir | Out-Null
    $argLine = "-cp `"$script:Classpath`" $($svc.Class) $($svc.Args)"
    $proc = Start-Process java -ArgumentList $argLine -WorkingDirectory $Root -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $RunDir "$($svc.Name).out.log") `
        -RedirectStandardError (Join-Path $RunDir "$($svc.Name).err.log")
    Set-Content (Join-Path $RunDir "$($svc.Name).pid") $proc.Id
    Write-Host "started $($svc.Name) (PID $($proc.Id))"
}

function Wait-Healthy($svc) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-RunningProcess $svc.Name)) {
            Show-LogTail $svc.Name
            Fail "$($svc.Name) exited during startup (log shown above)"
        }
        if ((Probe-Health $svc.Port) -ge 0) {
            Write-Host "$($svc.Name) healthy"
            return
        }
        Start-Sleep -Milliseconds 300
    }
    Fail "$($svc.Name) not healthy after 30s (see: .\dfs.ps1 logs $($svc.Name))"
}

function Stop-Dfs($svc) {
    $proc = Get-RunningProcess $svc.Name
    if (-not $proc) {
        Write-Host "$($svc.Name) not running"
        return
    }
    Stop-Process -Id $proc.Id -Force
    $proc.WaitForExit(5000) | Out-Null
    Remove-Item (Join-Path $RunDir "$($svc.Name).pid") -Force -ErrorAction SilentlyContinue
    Write-Host "stopped $($svc.Name)"
}

function Get-Targets($what) {
    if ($what -eq "all") { return $All }
    if ($what -eq "nodes") { return $Nodes }
    if ($what -eq "metadata") { return @($Metadata) }
    Fail "unknown target '$what' (use all, nodes or metadata)"
}

function Resolve-CallerPath($path) {
    if ([System.IO.Path]::IsPathRooted($path)) { return $path }
    return Join-Path $Caller $path
}

# ---------- commands ----------

function Cmd-Demo {
    $running = @($All | Where-Object { Get-RunningProcess $_.Name })
    if ($running.Count -gt 0) { Fail "background services are running. Run .\dfs.ps1 stop first (demo wipes data\)." }
    Assert-PortsFree $All
    Ensure-Build
    & java -cp $script:Classpath com.dfs.cmd.demo.Main
}

function Cmd-Start($what) {
    $targets = Get-Targets $what
    Assert-PortsFree $targets
    Ensure-Build
    # nodes first, metadata last (metadata health-checks the nodes)
    $nodeTargets = @($targets | Where-Object { $_.Name -ne "metadata" })
    $metaTargets = @($targets | Where-Object { $_.Name -eq "metadata" })
    foreach ($svc in $nodeTargets) { Start-Dfs $svc }
    foreach ($svc in $nodeTargets) { Wait-Healthy $svc }
    foreach ($svc in $metaTargets) {
        Start-Dfs $svc
        Wait-Healthy $svc
    }
    Write-Host "Ready. Try: .\dfs.ps1 status"
}

function Cmd-Stop($what) {
    foreach ($svc in (Get-Targets $what)) { Stop-Dfs $svc }
}

function Cmd-Status {
    $rows = foreach ($svc in $All) {
        $ms = Probe-Health $svc.Port
        $proc = Get-RunningProcess $svc.Name
        $state = "DOWN"
        $latency = "-"
        $procId = "-"
        if ($ms -ge 0) { $state = "UP"; $latency = "$ms ms" }
        if ($proc) { $procId = $proc.Id }
        [PSCustomObject]@{ Service = $svc.Name; Port = $svc.Port; Status = $state; Latency = $latency; PID = $procId }
    }
    $rows | Format-Table -AutoSize
}

function Cmd-Logs($what) {
    $names = @()
    if ($what -eq "all") { $names = @($All | ForEach-Object { $_.Name }) }
    elseif (@($All | Where-Object { $_.Name -eq $what }).Count -gt 0) { $names = @($what) }
    else { Fail "unknown target '$what' (use all, metadata, node1..node4)" }

    Write-Host "Following logs, Ctrl+C to stop..."
    $seen = @{}
    while ($true) {
        foreach ($name in $names) {
            foreach ($file in Get-LogFiles $name) {
                if (-not (Test-Path $file)) { continue }
                $lines = @(Get-Content $file -ErrorAction SilentlyContinue)
                if (-not $seen.ContainsKey($file)) { $seen[$file] = [Math]::Max(0, $lines.Count - 10) }
                if ($lines.Count -lt $seen[$file]) { $seen[$file] = 0 }   # file was restarted
                for ($i = $seen[$file]; $i -lt $lines.Count; $i++) {
                    Write-Host "[$name] $($lines[$i])"
                }
                $seen[$file] = $lines.Count
            }
        }
        Start-Sleep -Milliseconds 500
    }
}

function Cmd-Client($clientArgs) {
    if ((Probe-Health 9090) -lt 0) { Fail "metadata server is not running. Start it with: .\dfs.ps1 start" }
    Ensure-Build
    & java -cp $script:Classpath com.dfs.cmd.client.Main @clientArgs
}

function Show-Help {
    Write-Host @"
Usage: .\dfs.ps1 <command>

  demo                        one-shot scripted demo (own JVM, wipes data\)
  start   [all|nodes|metadata]   start in background (default: all)
  stop    [all|nodes|metadata]   stop background services
  status                      health table of all 5 services
  logs    [all|metadata|node1..node4]   follow logs (Ctrl+C to stop)

  upload <path>               upload a file, prints its file id
  ls                          list files
  download <id> <out-path>    download a file
  delete <id>                 delete a file
"@
}

# ---------- dispatch ----------

$first = ""
if ($Rest -and $Rest.Count -gt 0) { $first = $Rest[0] }
$what = "all"
if ($first -ne "") { $what = $first }

switch ($Command) {
    "demo"   { Cmd-Demo }
    "start"  { Cmd-Start $what }
    "stop"   { Cmd-Stop $what }
    "status" { Cmd-Status }
    "logs"   { Cmd-Logs $what }
    "ls"     { Cmd-Client @("ls") }
    "upload" {
        if ($first -eq "") { Fail "usage: .\dfs.ps1 upload <path>" }
        $path = Resolve-CallerPath $first
        if (-not (Test-Path $path)) { Fail "file not found: $path" }
        Cmd-Client @("upload", $path)
    }
    "download" {
        if ($Rest.Count -lt 2) { Fail "usage: .\dfs.ps1 download <id> <out-path>" }
        Cmd-Client @("download", $Rest[0], (Resolve-CallerPath $Rest[1]))
    }
    "delete" {
        if ($first -eq "") { Fail "usage: .\dfs.ps1 delete <id>" }
        Cmd-Client @("delete", $first)
    }
    default  { Show-Help }
}
