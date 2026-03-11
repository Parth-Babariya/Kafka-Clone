# DMQ Storage Service - Test Documentation

## Test Guide
📖 **[Storage Services Test Guide](resources/storage-services-test-guide.md)**

This comprehensive guide covers:
- Service startup instructions
- All API endpoint testing (Producer, Consumer, Admin)
- Request/response formats with examples
- Test automation scripts (PowerShell & Bash)
- Error scenarios and troubleshooting
- Performance benchmarking
- Current implementation status

## Quick Start

1. **Start Service:**
   ```bash
   cd dmq-storage-service
   mvn spring-boot:run
   ```

2. **Test Producer:**
   ```bash
   curl -X POST http://localhost:8082/api/v1/storage/messages \
     -H "Content-Type: application/json" \
     -d '{"topic":"test","partition":0,"messages":[{"value":"dGVzdA=="}]}'
   ```

3. **Check High Water Mark:**
   ```bash
   curl http://localhost:8082/api/v1/storage/partitions/test/0/high-water-mark
   ```

## Test Coverage
- ✅ Message Production (single & batch)
- ✅ Offset Management
- ✅ High Water Mark
- ✅ Request Validation
- ✅ Error Handling
- ❌ Consumer Reading (TODO)
- ❌ Network Replication (TODO)

---
*See the full test guide for detailed instructions and examples.*