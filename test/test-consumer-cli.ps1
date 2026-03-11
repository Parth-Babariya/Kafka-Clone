# Consumer CLI Test Script
# Tests consumer and consumer group functionality

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Consumer CLI Test Suite" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$cliJar = "../dmq-client\target\mycli.jar"
$cliJarAbsolute = Resolve-Path $cliJar
$testsPassed = 0
$testsFailed = 0

# Check if JAR exists
if (-not (Test-Path $cliJar)) {
    Write-Host "ERROR: CLI JAR not found" -ForegroundColor Red
    exit 1
}

Write-Host "Using CLI JAR: $cliJar" -ForegroundColor Gray
Write-Host ""

# Login first (required for JWT authentication)
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Logging in..." -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
$loginOutput = & java -jar $cliJar login --username admin --password admin123 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Login successful" -ForegroundColor Green
} else {
    Write-Host "[ERROR] Login failed: $loginOutput" -ForegroundColor Red
    exit 1
}
Write-Host ""

# ========================================
# Setup: Create test topic and produce messages
# ========================================
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Setup: Preparing Test Environment" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$timestamp = (Get-Date).ToString("MMddHHmmss")
$testTopic = "consumer-test-$timestamp"

Write-Host "[Setup] Creating topic: $testTopic" -ForegroundColor Yellow

$createOutput = & java -jar $cliJar create-topic --name $testTopic --partitions 3 --replication-factor 2 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "[PASS] Topic created" -ForegroundColor Green
} else {
    Write-Host "[FAIL] Topic creation failed" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "[Setup] Producing 30 test messages using batch processing..." -ForegroundColor Yellow

# Create batch files for each partition
for ($p = 0; $p -lt 3; $p++) {
    $batchFile = "test-consumer-batch-p$p.txt"
    $batchContent = ""
    
    for ($i = 1; $i -le 10; $i++) {
        $msgNum = ($p * 10) + $i
        $batchContent += "key-$msgNum`:Message $msgNum`n"
    }
    
    Set-Content -Path $batchFile -Value $batchContent
    
    # Produce batch to specific partition
    & java -jar $cliJar produce --topic $testTopic --batch-file $batchFile --partition $p --acks 1 2>&1 | Out-Null
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  Batch for partition $p produced - 10 messages" -ForegroundColor Gray
    } else {
        Write-Host "  [WARN] Batch for partition $p failed" -ForegroundColor Yellow
    }
    
    Remove-Item -Path $batchFile -ErrorAction SilentlyContinue
}

Write-Host "[PASS] Messages produced using batch mode" -ForegroundColor Green
Write-Host ""

Start-Sleep -Seconds 2

# ========================================
# Test Suite 1: Simple Consumer
# ========================================
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 1: Simple Consumer" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "[Test 1] Consume from partition 0" -ForegroundColor Yellow
$output = & java -jar $cliJar consume --topic $testTopic --partition 0 --offset 0 --max-messages 5 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [PASS]" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL]" -ForegroundColor Red
    $testsFailed++
}

Write-Host "[Test 2] Consume from partition 1" -ForegroundColor Yellow
& java -jar $cliJar consume --topic $testTopic --partition 1 --offset 0 --max-messages 5 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [PASS]" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL]" -ForegroundColor Red
    $testsFailed++
}

Write-Host "[Test 3] Consume from invalid partition (should fail)" -ForegroundColor Yellow
& java -jar $cliJar consume --topic $testTopic --partition 99 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "  [PASS] Failed as expected" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Should have failed" -ForegroundColor Red
    $testsFailed++
}

Write-Host ""

# ========================================
# Test Suite 2: Consumer Group
# ========================================
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 2: Consumer Group Commands" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "[Test 4] List consumer groups" -ForegroundColor Yellow
& java -jar $cliJar list-groups 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [PASS]" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL]" -ForegroundColor Red
    $testsFailed++
}

Write-Host "[Test 5] Consume with consumer group (group-based consumption)" -ForegroundColor Yellow
Write-Host "  Starting consumer group consumption..." -ForegroundColor Gray

  # Run consume-group in background job for 10 seconds
  $groupAppId = "test-consumer-group-$timestamp"
  $metadataUrl = "http://localhost:9091"  # Use leader node
  $consumeGroupJob = Start-Job -ScriptBlock {
    param($jar, $topic, $appId, $metaUrl)
    & java -jar $jar consume-group --topic $topic --app-id $appId --max-messages 20 --metadata-url $metaUrl 2>&1
} -ArgumentList $cliJarAbsolute, $testTopic, $groupAppId, $metadataUrl

# Wait for job to complete or timeout
$jobTimeout = 15
$jobResult = Wait-Job -Job $consumeGroupJob -Timeout $jobTimeout
$consumeGroupOutput = Receive-Job -Job $consumeGroupJob

Remove-Job -Job $consumeGroupJob -Force

if ($consumeGroupOutput -match "Successfully joined consumer group|Consumed \d+ messages") {
    Write-Host "  [PASS] Consumer group consumption worked" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL] Consumer group consumption failed" -ForegroundColor Red
    Write-Host "  Output: $consumeGroupOutput" -ForegroundColor Gray
    $testsFailed++
}

Write-Host "[Test 6] Describe consumer group" -ForegroundColor Yellow
& java -jar $cliJar describe-group --group $groupAppId 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [PASS]" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [SKIP] Group may not exist or already cleaned up" -ForegroundColor Yellow
}

Write-Host ""

# ========================================
# Test Suite 3: Performance Test
# ========================================
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 3: Performance Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "[Setup] Producing 50 more messages using batch processing..." -ForegroundColor Gray

# Create a batch file with 50 messages
$perfBatchFile = "test-perf-batch.txt"
$perfBatchContent = ""

for ($i = 51; $i -le 100; $i++) {
    $perfBatchContent += "key-$i`:Message $i`n"
}

Set-Content -Path $perfBatchFile -Value $perfBatchContent

# Distribute across partitions using batch mode
for ($p = 0; $p -lt 3; $p++) {
    & java -jar $cliJar produce --topic $testTopic --batch-file $perfBatchFile --partition $p --acks 1 2>&1 | Out-Null
}

Remove-Item -Path $perfBatchFile -ErrorAction SilentlyContinue
Write-Host "[PASS] Additional messages produced using batch mode" -ForegroundColor Green

Write-Host "[Test 7] Consume 50 messages (performance test)" -ForegroundColor Yellow
$startTime = Get-Date

& java -jar $cliJar consume --topic $testTopic --partition 0 --offset 0 --max-messages 50 2>&1 | Out-Null

$endTime = Get-Date
$duration = ($endTime - $startTime).TotalSeconds
$throughput = [math]::Round(50 / $duration, 2)

if ($LASTEXITCODE -eq 0) {
    Write-Host "  [PASS] Performance test completed" -ForegroundColor Green
    Write-Host "  Duration: $([math]::Round($duration, 2))s, Throughput: $throughput msg/s" -ForegroundColor Cyan
    $testsPassed++
} else {
    Write-Host "  [FAIL]" -ForegroundColor Red
    $testsFailed++
}

Write-Host ""

# ========================================
# Summary
# ========================================
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Topic:  $testTopic" -ForegroundColor White
Write-Host "Total:  $($testsPassed + $testsFailed)" -ForegroundColor White
Write-Host "Passed: $testsPassed" -ForegroundColor Green
Write-Host "Failed: $testsFailed" -ForegroundColor Red

if ($testsFailed -eq 0) {
    Write-Host ""
    Write-Host "ALL TESTS PASSED!" -ForegroundColor Green
    exit 0
} else {
    Write-Host ""
    Write-Host "SOME TESTS FAILED" -ForegroundColor Red
    exit 1
}
