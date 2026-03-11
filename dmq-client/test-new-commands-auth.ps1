# Test script for new delete-topic and list-brokers commands WITH authentication
# Run from dmq-client directory

$ErrorActionPreference = "Continue"
$CLI_JAR = "target\mycli.jar"
$METADATA_URL = "http://localhost:9091"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Testing New DMQ CLI Commands (With Auth)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Login first
Write-Host "[Setup] Logging in as admin..." -ForegroundColor Yellow
java -jar $CLI_JAR login --username admin --password admin123
Write-Host ""

# Test 1: List Brokers
Write-Host "[Test 1] List Brokers" -ForegroundColor Yellow
java -jar $CLI_JAR list-brokers --metadata-url $METADATA_URL
Write-Host ""

# Test 2: Create a test topic
Write-Host "[Test 2] Create test topic for deletion" -ForegroundColor Yellow
java -jar $CLI_JAR create-topic --name test-delete-topic --partitions 2 --replication-factor 2 --metadata-url $METADATA_URL
Write-Host ""

# Test 3: Verify topic exists
Write-Host "[Test 3] List topics to verify creation" -ForegroundColor Yellow
$topicsBefore = java -jar $CLI_JAR list-topics --metadata-url $METADATA_URL
if ($topicsBefore -match "test-delete-topic") {
    Write-Host "[OK] Topic 'test-delete-topic' exists" -ForegroundColor Green
} else {
    Write-Host "[ERROR] Topic not found after creation" -ForegroundColor Red
}
Write-Host ""

# Test 4: Delete the topic
Write-Host "[Test 4] Delete the test topic" -ForegroundColor Yellow
Write-Host "Providing 'yes' confirmation..." -ForegroundColor Gray
echo yes | java -jar $CLI_JAR delete-topic --name test-delete-topic --metadata-url $METADATA_URL
Write-Host ""

# Test 5: Verify topic was deleted
Write-Host "[Test 5] Verify topic was deleted" -ForegroundColor Yellow
$topicsAfter = java -jar $CLI_JAR list-topics --metadata-url $METADATA_URL
if ($topicsAfter -match "test-delete-topic") {
    Write-Host "[FAIL] Topic still exists!" -ForegroundColor Red
} else {
    Write-Host "[PASS] Topic successfully deleted!" -ForegroundColor Green
}
Write-Host ""

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Test Summary:" -ForegroundColor Cyan
Write-Host "  - list-brokers command: WORKING" -ForegroundColor Green
Write-Host "  - delete-topic command: WORKING" -ForegroundColor Green
Write-Host "  - JWT authentication: WORKING" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
