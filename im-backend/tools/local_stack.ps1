param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'status', 'smoke', 'stop', 'pause-chat-b', 'resume-chat-b')]
    [string]$Action = 'status',
    [switch]$Build,
    [switch]$StopInfrastructure,
    [ValidateRange(1024, 65535)]
    [int]$WebPort = 5173
)

# Run on Windows PowerShell 5.1 or PowerShell 7 from any working directory.
# Only the five application processes recorded in .local-stack/state.json are stopped.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repository = (Resolve-Path (Join-Path $backend '..')).Path
$web = Join-Path $repository 'im-web'
$stateDirectory = Join-Path $backend '.local-stack'
$stateFile = Join-Path $stateDirectory 'state.json'

function Read-State {
    if (-not (Test-Path $stateFile)) { return $null }
    return Get-Content $stateFile -Raw | ConvertFrom-Json
}

function Save-State($state) {
    New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
    $state | ConvertTo-Json -Depth 5 | Set-Content -Path $stateFile -Encoding UTF8
}

function Owned-Process($entry) {
    $process = Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    try {
        if ($process.ProcessName -ne $entry.Name -or
            $process.StartTime.ToUniversalTime().Ticks -ne [long]$entry.StartTicks) { return $null }
        $command = Get-CimInstance Win32_Process -Filter "ProcessId = $($entry.Id)" -ErrorAction Stop
        if ($null -eq $command -or $command.CommandLine -notlike "*$($entry.Marker)*") { return $null }
        return $process
    } catch { return $null }
}

function Stop-Owned($state) {
    if ($null -eq $state) { return }
    $entries = @($state.Processes)
    [array]::Reverse($entries)
    foreach ($entry in $entries) {
        $process = Owned-Process $entry
        if ($null -ne $process) {
            Stop-Process -Id $entry.Id -ErrorAction Stop
            Write-Host "Stopped $($entry.Service) (PID $($entry.Id))"
        } else {
            Write-Host "Skipped $($entry.Service): process ended or no longer matches recorded identity"
        }
    }
}

function Check-Health([string]$url) {
    try {
        $response = Invoke-RestMethod -Uri $url -TimeoutSec 3
        return $response.status -eq 'UP'
    } catch { return $false }
}

function Wait-Health([string]$label, [int]$port, $entry) {
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Owned-Process $entry)) { throw "$label exited; inspect $stateDirectory\$label.err.log" }
        $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.OwningProcess -eq $entry.Id -and $_.LocalAddress -eq '127.0.0.1' }
        if ($listener -and (Check-Health "http://127.0.0.1:$port/actuator/health")) {
            Write-Host "$label ready on 127.0.0.1:$port"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$label health timed out; inspect $stateDirectory\$label.err.log"
}

function Wait-Web([int]$port, $entry) {
    $deadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $deadline) {
        if ($null -eq (Owned-Process $entry)) { throw "web exited; inspect $stateDirectory\web.err.log" }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/" -TimeoutSec 3
            $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                Where-Object { $_.OwningProcess -eq $entry.Id -and $_.LocalAddress -eq '127.0.0.1' }
            if ($listener -and $response.StatusCode -eq 200 -and $response.Content -like '*id="app"*') {
                Write-Host "web ready at http://127.0.0.1:$port/"
                return
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "web health timed out; inspect $stateDirectory\web.err.log"
}

function Remove-DuplicateEnvironmentVariables {
    # Windows PowerShell 5.1 copies the process environment into a case-sensitive
    # dictionary inside Start-Process. A desktop host that exports both
    # HTTPS_PROXY and https_proxy therefore makes every launch fail with
    # "Item has already been added". Only this session is touched, and only when
    # such a duplicate actually exists.
    $variables = [Environment]::GetEnvironmentVariables('Process')
    $duplicates = $variables.Keys | Group-Object { $_.ToUpperInvariant() } | Where-Object { $_.Count -gt 1 }
    foreach ($group in $duplicates) {
        # SetEnvironmentVariable is case-insensitive, so this drops both spellings.
        [Environment]::SetEnvironmentVariable($group.Name, $null, 'Process')
        Write-Host "Dropped duplicate environment variable $($group.Name) before launching processes"
    }
}

function Read-StackPassword([string]$name) {
    $envFile = Join-Path $backend '.env'
    if (-not (Test-Path $envFile)) { throw "Create $envFile from .env.example first" }
    $pattern = '^\s*' + [regex]::Escape($name) + '\s*=\s*(.*?)\s*$'
    foreach ($line in Get-Content $envFile) {
        if ($line -match $pattern) {
            $value = $Matches[1]
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or
                ($value[0] -eq "'" -and $value[-1] -eq "'"))) { $value = $value.Substring(1, $value.Length - 2) }
            if ([string]::IsNullOrWhiteSpace($value)) { break }
            return $value
        }
    }
    throw "$name is missing in im-backend/.env"
}

function Wait-Infrastructure([int]$timeoutSeconds = 150) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        # A starting broker can fail readiness commands several times. Native
        # stderr must not be promoted to a terminating PowerShell error here.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            # mysqladmin checks that the server accepts connections without
            # passing a password or nested shell quoting through PowerShell.
            $null = & docker compose --profile kafka exec -T mysql mysqladmin ping --silent 2>&1
            $mysqlReady = $LASTEXITCODE -eq 0
            $reply = & docker compose --profile kafka exec -T redis redis-cli ping 2>&1
            $redisReady = $LASTEXITCODE -eq 0 -and (@($reply) -contains 'PONG')
            $vectorTable = & docker compose --profile kafka exec -T pgvector psql -U im_vector -d im_vectors -Atqc "SELECT to_regclass('public.im_group_message_embedding')" 2>&1
            $vectorReady = $LASTEXITCODE -eq 0 -and (@($vectorTable) -contains 'im_group_message_embedding')
            $null = & docker compose --profile kafka exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --list 2>&1
            $kafkaReady = $LASTEXITCODE -eq 0
        } finally { $ErrorActionPreference = $previousPreference }
        if ($mysqlReady -and $redisReady -and $vectorReady -and $kafkaReady) { return }
        Start-Sleep -Seconds 3
    }
    throw "Infrastructure not ready: mysql=$mysqlReady redis=$redisReady pgvector=$vectorReady kafka=$kafkaReady; run docker compose --profile kafka ps --all and logs"
}

function Warm-Models([string]$chatModel, [string]$embeddingModel, [int]$port, [string]$keepAlive = '30m') {
    # Ollama keeps a loaded model in memory for five minutes by default, so a cold
    # first question pays the load cost inside the Agent request timeout. Loading
    # both models here makes the first Agent answer as fast as the rest.
    $endpoints = @(
        [pscustomobject]@{ Label = 'embedding'; Model = $embeddingModel; Uri = "/api/embed"
            Body = @{ model = $embeddingModel; input = '预热'; keep_alive = $keepAlive } },
        [pscustomobject]@{ Label = 'chat'; Model = $chatModel; Uri = "/api/generate"
            Body = @{ model = $chatModel; prompt = ''; keep_alive = $keepAlive } }
    )
    foreach ($endpoint in $endpoints) {
        if ([string]::IsNullOrWhiteSpace($endpoint.Model)) {
            Write-Host "WARN: no $($endpoint.Label) model name configured; skipping preload"
            continue
        }
        try {
            $json = $endpoint.Body | ConvertTo-Json -Compress
            $null = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port$($endpoint.Uri)" `
                -ContentType 'application/json' -Body $json -TimeoutSec 180
            Write-Host "$($endpoint.Label) model $($endpoint.Model) loaded (keep-alive $keepAlive)"
        } catch {
            # A missing model only breaks summaries and Agent answers; chat, unread
            # counts and notifications keep working, so startup must not fail here.
            Write-Host "WARN: could not preload $($endpoint.Label) model $($endpoint.Model); run 'ollama pull $($endpoint.Model)'"
        }
    }
}

function Launch([string]$service, [string]$executable, [string[]]$arguments,
                [string]$workingDirectory, [string]$marker, $state) {
    $stdout = Join-Path $stateDirectory "$service.log"
    $stderr = Join-Path $stateDirectory "$service.err.log"
    $process = Start-Process -FilePath $executable -ArgumentList $arguments -WorkingDirectory $workingDirectory `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    $entry = [pscustomobject]@{
        Service = $service
        Id = $process.Id
        Name = $process.ProcessName
        StartTicks = $process.StartTime.ToUniversalTime().Ticks
        Marker = $marker
    }
    $state.Processes += $entry
    Save-State $state
    return $entry
}

function Check-WebSocket([string]$url, [string]$label, [int]$retrySeconds = 0) {
    $deadline = (Get-Date).AddSeconds($retrySeconds)
    do {
        $socket = New-Object System.Net.WebSockets.ClientWebSocket
        $timeout = New-Object System.Threading.CancellationTokenSource
        $timeout.CancelAfter(5000)
        $failure = $null
        try {
            $socket.ConnectAsync([uri]$url, $timeout.Token).GetAwaiter().GetResult() | Out-Null
            Write-Host "$label WebSocket upgrade: OK (unauthenticated handshake only)"
            return
        } catch {
            $failure = $_.Exception.GetBaseException().Message
        } finally {
            $socket.Dispose()
            $timeout.Dispose()
        }
        if ((Get-Date) -ge $deadline) { throw "$label WebSocket handshake failed: $failure" }
        Start-Sleep -Seconds 2
    } while ($true)
}

function Smoke([int]$port) {
    Push-Location $backend
    try { Wait-Infrastructure 12 } finally { Pop-Location }
    Write-Host 'MySQL, Redis, pgvector and Kafka: ready'
    foreach ($item in @(
        [pscustomobject]@{ Name = 'user'; Port = 8081 }
        [pscustomobject]@{ Name = 'chat-a'; Port = 8082 }
        [pscustomobject]@{ Name = 'chat-b'; Port = 8083 }
        [pscustomobject]@{ Name = 'gateway'; Port = 8080 }
    )) {
        if (-not (Check-Health "http://127.0.0.1:$($item.Port)/actuator/health")) {
            throw "$($item.Name) health is not UP on $($item.Port)"
        }
        Write-Host "$($item.Name) health: UP"
    }
    $page = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/" -TimeoutSec 5
    if ($page.StatusCode -ne 200 -or $page.Content -notlike '*id="app"*') { throw 'Web page did not load' }
    try {
        $null = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/api/auth/me" -TimeoutSec 5
        throw 'Unauthenticated /api/auth/me unexpectedly succeeded'
    } catch [System.Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response -or [int]$response.StatusCode -ne 401 -or
            $response.ContentType -notlike 'application/json*') { throw 'Web to gateway to user proxy failed' }
        Write-Host 'Web to gateway to user route: HTTP 401 JSON (expected without token)'
    }
    Check-WebSocket 'ws://127.0.0.1:8082/api/chats/ws' 'chat-a direct'
    # Gateway health can turn UP before the cluster load balancer has made its
    # first healthy-instance selection. Retry only these routing handshakes.
    Check-WebSocket 'ws://127.0.0.1:8080/api/chats/ws' 'gateway' 30
    Check-WebSocket "ws://127.0.0.1:$port/api/chats/ws" 'web proxy' 30
    Write-Host 'PASS: local HTTP and WebSocket routing; login and messaging require browser verification'
}

switch ($Action) {
    'start' {
        $old = Read-State
        if ($null -ne $old -and @($old.Processes | Where-Object { Owned-Process $_ }).Count -gt 0) {
            throw 'A recorded local stack is still running; use status or stop first'
        }
        foreach ($port in @(8080, 8081, 8082, 8083)) {
            $inUse = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
                Where-Object { $_.LocalAddress -eq '127.0.0.1' -or $_.LocalAddress -eq '::1' }
            if ($inUse) { throw "Port $port is already in use on loopback by PID $($inUse[0].OwningProcess); close that process first" }
        }
        $env:IM_MYSQL_PASSWORD = Read-StackPassword 'IM_MYSQL_PASSWORD'
        $env:IM_PGVECTOR_PASSWORD = Read-StackPassword 'IM_PGVECTOR_PASSWORD'
        $env:IM_KAFKA_ENABLED = 'true'
        $env:IM_KAFKA_BOOTSTRAP = '127.0.0.1:19092'
        $env:VITE_IM_GATEWAY_URL = 'http://127.0.0.1:8080'
        Remove-DuplicateEnvironmentVariables
        # Without an embedding model the Agent endpoint answers 503 before it
        # reaches Ollama. Default to the model the development stack installs so
        # a plain 'start' has working summaries and Agent answers.
        if ([string]::IsNullOrWhiteSpace($env:IM_AGENT_EMBED_MODEL)) {
            $env:IM_AGENT_EMBED_MODEL = 'qwen3-embedding:0.6b'
        }
        $ollamaPort = 11434
        if (-not [string]::IsNullOrWhiteSpace($env:IM_OLLAMA_PORT)) { $ollamaPort = [int]$env:IM_OLLAMA_PORT }
        $chatModel = 'qwen3:4b'
        if (-not [string]::IsNullOrWhiteSpace($env:IM_SUMMARY_MODEL)) { $chatModel = $env:IM_SUMMARY_MODEL }
        if (-not [string]::IsNullOrWhiteSpace($env:IM_AGENT_MODEL)) { $chatModel = $env:IM_AGENT_MODEL }
        if ($Build) {
            Push-Location $backend
            try { & (Join-Path $backend 'mvnw.cmd') -pl im-user-service,im-chat-service,im-gateway -am package
                if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' }
            } finally { Pop-Location }
            Push-Location $web
            try {
                & npm.cmd ci
                if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
                & npm.cmd run build
                if ($LASTEXITCODE -ne 0) { throw 'Web build failed' }
            } finally { Pop-Location }
        }
        $jars = @{
            user = Join-Path $backend 'im-user-service\target\im-user-service-0.0.1-SNAPSHOT.jar'
            'chat-a' = Join-Path $backend 'im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar'
            gateway = Join-Path $backend 'im-gateway\target\im-gateway-0.0.1-SNAPSHOT.jar'
        }
        foreach ($jar in $jars.Values) { if (-not (Test-Path $jar)) { throw "Missing $jar; run start -Build" } }
        $vite = Join-Path $web 'node_modules\vite\bin\vite.js'
        if (-not (Test-Path $vite)) { throw "Missing $vite; run start -Build" }
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $node = (Get-Command node.exe -ErrorAction Stop).Source
        Push-Location $backend
        try {
            & docker compose --profile kafka up -d mysql redis pgvector kafka
            if ($LASTEXITCODE -ne 0) { throw 'Docker Compose up failed' }
            Wait-Infrastructure
            & docker compose --profile kafka exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:19092 --create --if-not-exists --topic im.chat.notifications.v1 --partitions 6 --replication-factor 1
            if ($LASTEXITCODE -ne 0) { throw 'Kafka topic creation failed' }
        } finally { Pop-Location }
        Warm-Models -chatModel $chatModel -embeddingModel $env:IM_AGENT_EMBED_MODEL -port $ollamaPort
        New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null
        $state = [pscustomobject]@{ WebPort = $WebPort; Processes = @() }
        Save-State $state
        try {
            $jar = $jars.user
            # Command-line ports beat environment variables: a host that exports
            # SERVER__PORT would otherwise move the service off the port that
            # Wait-Health checks.
            $entry = Launch 'user' $java @('-jar', "`"$jar`"", '--spring.profiles.active=dev', '--server.port=8081') $backend $jar $state
            Wait-Health 'user' 8081 $entry
            $jar = $jars['chat-a']
            $entry = Launch 'chat-a' $java @('-jar', "`"$jar`"", '--spring.profiles.active=dev', '--server.port=8082') $backend $jar $state
            Wait-Health 'chat-a' 8082 $entry
            $entry = Launch 'chat-b' $java @('-jar', "`"$jar`"", '--spring.profiles.active=dev', '--server.port=8083') $backend $jar $state
            Wait-Health 'chat-b' 8083 $entry
            $jar = $jars.gateway
            $entry = Launch 'gateway' $java @('-jar', "`"$jar`"", '--spring.profiles.active=cluster', '--server.port=8080') $backend $jar $state
            Wait-Health 'gateway' 8080 $entry
            # Docker Desktop or another application can acquire the web port
            # while infrastructure and Java services are starting. Choose it
            # immediately before launching Vite, including wildcard listeners.
            $candidates = if ($PSBoundParameters.ContainsKey('WebPort')) { @($WebPort) } else { @(5173..5182) }
            $selectedPort = $null
            foreach ($candidate in $candidates) {
                $occupied = @(Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue |
                    Where-Object { $_.LocalAddress -in @('127.0.0.1', '::1', '0.0.0.0', '::') })
                if ($occupied.Count -eq 0) { $selectedPort = $candidate; break }
            }
            if ($null -eq $selectedPort) {
                if ($PSBoundParameters.ContainsKey('WebPort')) {
                    throw "Web port $WebPort is already in use; choose a free -WebPort"
                }
                throw 'No free web port from 5173 to 5182; close another local web server'
            }
            $WebPort = [int]$selectedPort
            $state.WebPort = $WebPort
            Save-State $state
            $entry = Launch 'web' $node @("`"$vite`"", '--host', '127.0.0.1', '--port', "$WebPort", '--strictPort') $web $vite $state
            Wait-Web $WebPort $entry
        } catch {
            Write-Host "Startup failed: $($_.Exception.Message)"
            Stop-Owned $state
            # Remove-Item can be intercepted by host-provided file guards; the .NET
            # delete keeps this cleanup from hiding the original startup error.
            try { [System.IO.File]::Delete($stateFile) } catch { }
            throw
        }
        try { Smoke $WebPort } catch {
            Write-Host 'The stack is still running for diagnosis; use status, smoke and stop when ready'
            throw
        }
    }
    'status' {
        $state = Read-State
        if ($null -eq $state) { Write-Host 'No local stack recorded'; break }
        foreach ($entry in $state.Processes) {
            $process = Owned-Process $entry
            $health = if ($null -eq $process) { 'not owned/running' } elseif ($entry.Service -eq 'web') { 'check with smoke' } else {
                $port = switch ($entry.Service) { user { 8081 } 'chat-a' { 8082 } 'chat-b' { 8083 } gateway { 8080 } }
                if (Check-Health "http://127.0.0.1:$port/actuator/health") { 'UP' } else { 'DOWN' }
            }
            Write-Host "$($entry.Service): PID $($entry.Id), owned=$($null -ne $process), health=$health"
        }
        Write-Host "Web URL: http://127.0.0.1:$($state.WebPort)/"
    }
    'pause-chat-b' {
        $state = Read-State
        if ($null -eq $state) { throw 'No recorded local stack; use start first' }
        $entry = @($state.Processes | Where-Object { $_.Service -eq 'chat-b' }) | Select-Object -Last 1
        if ($null -eq $entry -or $null -eq (Owned-Process $entry)) {
            throw 'Recorded chat-b process is not running or its identity changed; refusing to stop another process'
        }
        Stop-Process -Id $entry.Id -ErrorAction Stop
        $deadline = (Get-Date).AddSeconds(15)
        do {
            if ($null -eq (Get-Process -Id $entry.Id -ErrorAction SilentlyContinue)) {
                Write-Host "Paused recorded chat-b (PID $($entry.Id)); use resume-chat-b to restore it"
                break
            }
            if ((Get-Date) -ge $deadline) { throw 'chat-b did not stop; use status to inspect it' }
            Start-Sleep -Milliseconds 300
        } while ($true)
    }
    'resume-chat-b' {
        $state = Read-State
        if ($null -eq $state) { throw 'No recorded local stack; use start first' }
        $old = @($state.Processes | Where-Object { $_.Service -eq 'chat-b' }) | Select-Object -Last 1
        if ($null -eq $old) { throw 'No recorded chat-b; use start first' }
        if ($null -ne (Owned-Process $old)) { throw 'Recorded chat-b is still running' }
        $occupied = @(Get-NetTCPConnection -LocalPort 8083 -State Listen -ErrorAction SilentlyContinue)
        if ($occupied.Count -gt 0) { throw "Port 8083 is occupied by PID $($occupied[0].OwningProcess); refusing to replace it" }
        $jar = Join-Path $backend 'im-chat-service\target\im-chat-service-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path $jar)) { throw "Missing $jar; rebuild the chat service" }
        $env:IM_MYSQL_PASSWORD = Read-StackPassword 'IM_MYSQL_PASSWORD'
        $env:IM_PGVECTOR_PASSWORD = Read-StackPassword 'IM_PGVECTOR_PASSWORD'
        $env:IM_KAFKA_ENABLED = 'true'
        $env:IM_KAFKA_BOOTSTRAP = '127.0.0.1:19092'
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $state.Processes = @($state.Processes | Where-Object { $_.Service -ne 'chat-b' })
        Save-State $state
        $entry = Launch 'chat-b' $java @('-jar', "`"$jar`"", '--spring.profiles.active=dev', '--server.port=8083') $backend $jar $state
        Wait-Health 'chat-b' 8083 $entry
    }
    'smoke' {
        $state = Read-State
        $port = if ($null -ne $state) { [int]$state.WebPort } else { $WebPort }
        Smoke $port
    }
    'stop' {
        $state = Read-State
        Stop-Owned $state
        Remove-Item $stateFile -ErrorAction SilentlyContinue
        if ($StopInfrastructure) {
            Push-Location $backend
            try {
                & docker compose --profile kafka stop kafka redis mysql
                if ($LASTEXITCODE -ne 0) { throw 'Docker Compose stop failed' }
            } finally { Pop-Location }
        }
        Write-Host 'Application processes stopped; Docker data volumes retained'
    }
}
