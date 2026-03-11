#!/usr/bin/env pwsh
# Test script for Consumer Groups API endpoints

$ErrorActionPreference = "Continue"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Consumer Groups API Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Login first (required for JWT authentication)
Write-Host "Logging in..." -ForegroundColor Yellow
$loginOutput = java -jar ../dmq-client\target\mycli.jar login --username admin --password admin123 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host " Login successful" -ForegroundColor Green
} else {
    Write-Host " Login failed" -ForegroundColor Red
    exit 1
}
Write-Host ""

# Read JWT token from saved file
$tokenFile = "$env:USERPROFILE\.dmq\token.properties"
if (-not (Test-Path $tokenFile)) {
    Write-Host "[ERROR] Token file not found. Please login first." -ForegroundColor Red
    exit 1
}

$tokenContent = Get-Content $tokenFile | Where-Object { $_ -match '^token=' }
$jwtToken = $tokenContent -replace '^token=', ''
$headers = @{
    "Authorization" = "Bearer $jwtToken"
    "Content-Type" = "application/json"
}

# Wait for service to be ready
Write-Host "Checking if metadata service is running..." -ForegroundColor Yellow
Start-Sleep -Seconds 2

try {
    $controller = Invoke-RestMethod -Uri "http://localhost:9091/api/v1/metadata/controller" -Method GET -Headers $headers -TimeoutSec 5
    Write-Host "[PASS] Connected to controller" -ForegroundColor Green
    Write-Host ""
} catch {
    Write-Host "[FAIL] Cannot connect to metadata service on port 9091" -ForegroundColor Red
    Write-Host "Please start metadata service first:" -ForegroundColor Yellow
    Write-Host "  cd dmq-metadata-service" -ForegroundColor Gray
    Write-Host "  java -jar target\dmq-metadata-service-1.0.0-SNAPSHOT.jar --server.port=9091 --spring.profiles.active=node1" -ForegroundColor Gray
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 1: List Consumer Groups (empty)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
try {
    $groups = Invoke-RestMethod -Uri "http://localhost:9091/api/v1/metadata/consumer-groups" -Method GET -Headers $headers
    Write-Host "[PASS] List groups endpoint works" -ForegroundColor Green
    Write-Host "Found $($groups.Count) groups" -ForegroundColor Gray
    Write-Host ""
} catch {
    Write-Host "[FAIL] List groups endpoint failed" -ForegroundColor Red
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 2: Create Consumer Group" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$createRequest = @{
    topic = "test-topic"
    appId = "test-app"
} | ConvertTo-Json

try {
    $created = Invoke-RestMethod -Uri "http://localhost:9091/api/v1/metadata/consumer-groups/find-or-create" -Method POST -Body $createRequest -Headers $headers
    Write-Host "[PASS] Consumer group created" -ForegroundColor Green
    Write-Host "Group ID: $($created.groupId)" -ForegroundColor Gray
    Write-Host "Topic: $($created.topic)" -ForegroundColor Gray
    Write-Host "Leader Broker: $($created.groupLeaderBrokerId)" -ForegroundColor Gray
    Write-Host ""
    
    $testGroupId = $created.groupId
} catch {
    Write-Host "[FAIL] Failed to create consumer group" -ForegroundColor Red
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host ""
    exit 1
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 3: List Consumer Groups (with data)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
try {
    $groups = Invoke-RestMethod -Uri "http://localhost:9091/api/v1/metadata/consumer-groups" -Method GET -Headers $headers
    Write-Host "[PASS] List groups endpoint works" -ForegroundColor Green
    Write-Host "Found $($groups.Count) group(s)" -ForegroundColor Gray
    foreach ($group in $groups) {
        Write-Host "  - $($group.groupId) (topic: $($group.topic))" -ForegroundColor Gray
    }
    Write-Host ""
} catch {
    Write-Host "[FAIL] List groups endpoint failed" -ForegroundColor Red
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 4: Describe Consumer Group" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
try {
    $group = Invoke-RestMethod -Uri "http://localhost:9091/api/v1/metadata/consumer-groups/$testGroupId" -Method GET -Headers $headers
    Write-Host "[PASS] Describe group endpoint works" -ForegroundColor Green
    Write-Host "Group ID: $($group.groupId)" -ForegroundColor Gray
    Write-Host "Topic: $($group.topic)" -ForegroundColor Gray
    Write-Host "App ID: $($group.appId)" -ForegroundColor Gray
    Write-Host "Leader Broker: $($group.groupLeaderBrokerId)" -ForegroundColor Gray
    Write-Host "Leader URL: $($group.groupLeaderUrl)" -ForegroundColor Gray
    Write-Host ""
} catch {
    Write-Host "[FAIL] Describe group endpoint failed" -ForegroundColor Red
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 5: CLI list-groups" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$output = java -jar dmq-client\target\mycli.jar list-groups 2>&1
$exitCode = $LASTEXITCODE
if ($exitCode -eq 0) {
    Write-Host "[PASS] CLI list-groups works" -ForegroundColor Green
} else {
    Write-Host "[FAIL] CLI list-groups failed" -ForegroundColor Red
}
Write-Host $output
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test 6: CLI describe-group" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$output = java -jar dmq-client\target\mycli.jar describe-group --topic test-topic --app-id test-app 2>&1
$exitCode = $LASTEXITCODE
if ($exitCode -eq 0) {
    Write-Host "[PASS] CLI describe-group works" -ForegroundColor Green
} else {
    Write-Host "[FAIL] CLI describe-group failed" -ForegroundColor Red
}
Write-Host $output
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  All Tests Completed!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
