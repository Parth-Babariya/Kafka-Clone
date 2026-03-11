# Producer CLI Test Script
# Tests all producer CLI functionality

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Producer CLI Test Suite" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$cliJar = "../dmq-client\target\mycli.jar"
$testsPassed = 0
$testsFailed = 0

# Check if JAR exists
if (-not (Test-Path $cliJar)) {
    Write-Host "ERROR: CLI JAR not found at $cliJar" -ForegroundColor Red
    Write-Host "Run: mvn clean install -DskipTests -pl dmq-client -am" -ForegroundColor Yellow
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

# Helper function to run CLI command
function Test-CliCommand {
    param(
        [string]$TestName,
        [string[]]$Arguments,
        [bool]$ShouldSucceed = $true
    )
    
    Write-Host "[$TestName]" -ForegroundColor Yellow -NoNewline
    Write-Host " Running: java -jar $cliJar $($Arguments -join ' ')" -ForegroundColor Gray
    
    $output = & java -jar $cliJar @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    
    if ($ShouldSucceed) {
        if ($exitCode -eq 0) {
            Write-Host "  [PASS]" -ForegroundColor Green
            $script:testsPassed++
            return $true
        } else {
            Write-Host "  [FAIL] (exit code: $exitCode)" -ForegroundColor Red
            Write-Host "  Output: $output" -ForegroundColor Red
            $script:testsFailed++
            return $false
        }
    } else {
        if ($exitCode -ne 0) {
            Write-Host "  [PASS] (expected failure)" -ForegroundColor Green
            $script:testsPassed++
            return $true
        } else {
            Write-Host "  [FAIL] (should have failed but succeeded)" -ForegroundColor Red
            $script:testsFailed++
            return $false
        }
    }
}

# Test 1: CLI Help
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 1: CLI Help and Info" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Test-CliCommand "Help Command" @("--help")
Test-CliCommand "Version Command" @("--version")
Test-CliCommand "No Arguments" @() -ShouldSucceed $false

Write-Host ""

# Test 2: Topic Creation
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 2: Topic Creation" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Generate unique topic name
$timestamp = (Get-Date).ToString("MMddHHmmss")
$testTopic = "cli-test-topic-$timestamp-p5-rf2"

Write-Host "[Info] Creating test topic: $testTopic" -ForegroundColor Yellow

# Create topic first
$createOutput = & java -jar $cliJar create-topic --name $testTopic --partitions 5 --replication-factor 2 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Topic created successfully" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "[ERROR] Topic creation failed: $createOutput" -ForegroundColor Red
    Write-Host "Continuing with tests assuming topic exists..." -ForegroundColor Yellow
    $testsFailed++
}

Write-Host ""

# Test 3: Message Production
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 3: Message Production" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Basic produce
Test-CliCommand "Produce Simple Message" @("produce", "--topic", $testTopic, "--key", "key1", "--value", "Hello from CLI!")

# Produce to specific partition
Test-CliCommand "Produce to Partition 0" @("produce", "--topic", $testTopic, "--key", "key2", "--value", "Message for partition 0", "--partition", "0")

# Produce with different acks
Test-CliCommand "Produce with acks=0 fire-and-forget" @("produce", "--topic", $testTopic, "--key", "key3", "--value", "Fire and forget", "--acks", "0")
Test-CliCommand "Produce with acks=1 leader only" @("produce", "--topic", $testTopic, "--key", "key4", "--value", "Leader ack", "--acks", "1")
Test-CliCommand "Produce with acks=-1 all replicas" @("produce", "--topic", $testTopic, "--key", "key5", "--value", "All replicas ack", "--acks", "-1")

# Produce with special characters
Test-CliCommand "Produce with special chars" @("produce", "--topic", $testTopic, "--key", "special-key", "--value", "Special chars test")

# Produce long message
$longValue = "A" * 500
Test-CliCommand "Produce long message 500 chars" @("produce", "--topic", $testTopic, "--key", "long", "--value", $longValue)

# Produce with JSON-like value
$jsonValue = '{\"name\":\"John\",\"age\":30,\"city\":\"NYC\"}'
Test-CliCommand "Produce JSON-like value" @("produce", "--topic", $testTopic, "--key", "json-key", "--value", $jsonValue)

# Negative tests
Test-CliCommand "Produce without topic" @("produce", "--key", "key", "--value", "value") -ShouldSucceed $false
Test-CliCommand "Produce without key is optional" @("produce", "--topic", $testTopic, "--value", "value") -ShouldSucceed $true
Test-CliCommand "Produce without value" @("produce", "--topic", $testTopic, "--key", "key") -ShouldSucceed $false

Write-Host ""

# Test 4: Edge Cases
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 4: Edge Cases" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Empty strings
Test-CliCommand "Produce with empty key" @("produce", "--topic", $testTopic, "--key", "", "--value", "value with empty key")

# Unicode characters
Test-CliCommand "Produce with Unicode" @("produce", "--topic", $testTopic, "--key", "unicode", "--value", "Hello ?????? ???? caf??")

# Numbers as strings
Test-CliCommand "Produce with numeric values" @("produce", "--topic", $testTopic, "--key", "123", "--value", "456.789")

# Whitespace
Test-CliCommand "Produce with whitespace value" @("produce", "--topic", $testTopic, "--key", "spaces", "--value", "  leading and trailing spaces  ")

# Non-existent topic (should fail if topic does not exist)
Test-CliCommand "Produce to non-existent topic" @("produce", "--topic", "non-existent-topic-xyz", "--key", "key", "--value", "value") -ShouldSucceed $false

Write-Host ""

# Test 5: Batch Production
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 5: Batch Production File-Based" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# Create a batch file for testing
$batchFile = "test-batch-temp.txt"
Write-Host "[Setup] Creating batch file with 10 messages..." -ForegroundColor Yellow

$batchContent = @"
# Batch test messages
batch-key-1:Batch message 1 with key
batch-key-2:Batch message 2 with key
batch-key-3:Batch message 3 with key
Batch message 4 without key
Batch message 5 without key
order-100:Order 100 pending
order-101:Order 101 confirmed
Standalone message 8
user-500:USER_LOGIN_EVENT
admin-001:ADMIN_ACTION_COMPLETED
"@

Set-Content -Path $batchFile -Value $batchContent
Write-Host '  Created batch file: ' -NoNewline -ForegroundColor Gray
Write-Host $batchFile -ForegroundColor Gray

# Test batch produce with acks=-1
Test-CliCommand "Batch Produce acks=-1" @("produce", "--topic", $testTopic, "--batch-file", $batchFile, "--acks", "-1")

# Test batch produce with acks=1
Test-CliCommand "Batch Produce acks=1" @("produce", "--topic", $testTopic, "--batch-file", $batchFile, "--acks", "1")

# Test batch produce to specific partition
Test-CliCommand "Batch Produce to partition 0" @("produce", "--topic", $testTopic, "--batch-file", $batchFile, "--partition", "0")

# Test batch with non-existent file
Test-CliCommand "Batch with non-existent file" @("produce", "--topic", $testTopic, "--batch-file", "non-existent-file.txt") -ShouldSucceed $false

# Cleanup
Remove-Item -Path $batchFile -ErrorAction SilentlyContinue

Write-Host "[Cleanup] Removed temporary batch file" -ForegroundColor Gray

Write-Host ""

# Test 6: Performance Test (Optional)
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Suite 6: Performance Test 50 messages" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$startTime = Get-Date
$perfSuccess = 0
$perfFailed = 0

for ($i = 1; $i -le 50; $i++) {
    $result = & java -jar $cliJar produce --topic $testTopic --key "perf-$i" --value "Performance test message $i" 2>&1
    if ($LASTEXITCODE -eq 0) {
        $perfSuccess++
    } else {
        $perfFailed++
    }
    
    # Progress indicator
    if ($i % 10 -eq 0) {
        Write-Host "  Progress: $i/50 messages sent" -ForegroundColor Gray
    }
}

$endTime = Get-Date
$duration = ($endTime - $startTime).TotalSeconds
$throughput = [math]::Round(50 / $duration, 2)

Write-Host "  Results:" -ForegroundColor Cyan
Write-Host "    Success: $perfSuccess" -ForegroundColor Green
Write-Host "    Failed:  $perfFailed" -ForegroundColor Red
Write-Host "    Duration: $([math]::Round($duration, 2))s" -ForegroundColor Cyan
Write-Host "    Throughput: $throughput messages/sec" -ForegroundColor Cyan

if ($perfFailed -eq 0) {
    Write-Host "  [PASS]" -ForegroundColor Green
    $testsPassed++
} else {
    Write-Host "  [FAIL]" -ForegroundColor Red
    $testsFailed++
}

Write-Host ""

# Summary
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Total Tests: $($testsPassed + $testsFailed)" -ForegroundColor White
Write-Host "Passed:      $testsPassed" -ForegroundColor Green
Write-Host "Failed:      $testsFailed" -ForegroundColor Red

if ($testsFailed -eq 0) {
    Write-Host ""
    Write-Host "ALL TESTS PASSED!" -ForegroundColor Green
    Write-Host ""
    exit 0
} else {
    Write-Host ""
    Write-Host "SOME TESTS FAILED" -ForegroundColor Red
    Write-Host ""
    exit 1
}
