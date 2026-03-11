# Test script for batch produce functionality
# Prerequisites: All services should be running (metadata, storage brokers)

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Testing Batch Produce Functionality" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$mycli = "java -jar ../dmq-client\target\mycli.jar"

# Login first (required for JWT authentication)
Write-Host "Logging in..." -ForegroundColor Magenta
Invoke-Expression "$mycli login --username admin --password admin123" | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "[OK] Login successful" -ForegroundColor Green
} else {
    Write-Host "[ERROR] Login failed" -ForegroundColor Red
    exit 1
}
Write-Host ""

# Setup: Create topic with replication factor 2 for proper acks=-1 testing
Write-Host "Setup: Creating topic with replication factor 2" -ForegroundColor Magenta
Write-Host "Command: $mycli create-topic --name test-batch-rf2 --partitions 3 --replication-factor 2" -ForegroundColor Gray
Invoke-Expression "$mycli create-topic --name test-batch-rf2 --partitions 3 --replication-factor 2"
Write-Host ""
Write-Host "========================================" -ForegroundColor Gray
Write-Host ""

# Test 1: Help command to show new batch option
Write-Host "Test 1: Show help with batch option" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --help" -ForegroundColor Gray
Invoke-Expression "$mycli produce --help"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 2: Single message with acks=-1 all ISR replicas
Write-Host "Test 2: Single message produce with acks=-1 and replication" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --value 'Single message test'" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --value 'Single message test'"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 3: Single message with key and acks=-1
Write-Host "Test 3: Single message with key using acks=-1 with replication" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --key user-001 --value 'Message with key'" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --key user-001 --value 'Message with key'"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 4: Batch produce from file with acks=-1
Write-Host "Test 4: Batch produce from file using acks=-1 with replication" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 5: Batch produce with specific partition and acks=-1
Write-Host "Test 5: Batch produce to specific partition using acks=-1" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --partition 0" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --partition 0"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 6: Batch produce with acks=1 leader only
Write-Host "Test 6: Batch produce with acks=1 for leader only" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --acks 1" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --acks 1"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 7: Batch produce with acks=0 no acknowledgment
Write-Host "Test 7: Batch produce with acks=0 fire-and-forget mode" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --acks 0" -ForegroundColor Gray
Invoke-Expression "$mycli produce --topic test-batch-rf2 --batch-file batch-messages.txt --acks 0"
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 8: Error handling - both value and batch-file
Write-Host "Test 8: Error handling - both value and batch-file should fail" -ForegroundColor Yellow
Write-Host "Command: $mycli produce --topic test-batch-rf2 --value 'test' --batch-file batch-messages.txt" -ForegroundColor Gray
try {
    Invoke-Expression "$mycli produce --topic test-batch-rf2 --value 'test' --batch-file batch-messages.txt" 2>&1
} catch {
    Write-Host "Expected error: $_" -ForegroundColor Red
}
Write-Host ""
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host ""

# Test 9: Consume to verify batch was written
Write-Host "Test 9: Consume messages to verify batch from partition 0" -ForegroundColor Yellow
Write-Host "Command: $mycli consume --topic test-batch-rf2 --partition 0 --offset 0 --max-messages 20" -ForegroundColor Gray
Invoke-Expression "$mycli consume --topic test-batch-rf2 --partition 0 --offset 0 --max-messages 20"
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Batch Produce Tests Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
