# Role-Based Access Control (RBAC) Documentation

## Overview

DistributedMQ implements **JWT-based Role-Based Access Control (RBAC)** to secure all API endpoints. Users must authenticate to receive a JWT token containing their roles, which is then validated for every subsequent request.

## Roles

The system supports three primary roles:

### 1. **ADMIN** Role
Full administrative access to all system operations.

### 2. **PRODUCER** Role
Permissions for producing/publishing messages to topics.

### 3. **CONSUMER** Role
Permissions for consuming/reading messages from topics.

---

## Predefined Users

Default users configured in `application.yml`:

| Username | Password | Roles | Description |
|----------|----------|-------|-------------|
| `admin` | `admin123` | ADMIN | Full system administrator |
| `producer1` | `prod123` | PRODUCER | Producer-only access |
| `consumer1` | `cons123` | CONSUMER | Consumer-only access |
| `app1` | `app123` | PRODUCER, CONSUMER | Application with both produce and consume rights |

---

## Authentication Flow

### 1. Login
```bash
POST /api/v1/auth/login
{
  "username": "admin",
  "password": "admin123"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 900,
  "username": "admin"
}
```

### 2. Token Storage
The JWT token is saved to `~/.dmq/token.properties` and automatically included in subsequent CLI commands.

### 3. Token Refresh
```bash
POST /api/v1/auth/refresh
Authorization: Bearer <existing-token>
```

Returns a new token with extended expiration (15 minutes from refresh time).

---

## Permission Matrix

### Metadata Service Endpoints

| Endpoint | HTTP Method | ADMIN | PRODUCER | CONSUMER | Public |
|----------|-------------|-------|----------|----------|--------|
| `/api/v1/auth/login` | POST | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/auth/refresh` | POST | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/metadata/controller` | GET | ✓ | ✓ | ✓ | Internal |
| `/api/v1/metadata/topics` (create) | POST | ✓ | ✗ | ✗ | ✗ |
| `/api/v1/metadata/topics` (list) | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/metadata/topics/{name}` (describe) | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/metadata/topics/{name}` (delete) | DELETE | ✓ | ✗ | ✗ | ✗ |
| `/api/v1/metadata/brokers` (register) | POST | ✓ | ✗ | ✗ | Internal |
| `/api/v1/metadata/brokers` (list) | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/metadata/brokers/{id}` | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/metadata/cluster` | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/metadata/consumer-groups` (list) | GET | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/metadata/consumer-groups` (find-or-create) | POST | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/metadata/consumer-groups/{id}` | GET | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/metadata/consumer-groups/{id}` (delete) | DELETE | ✓ | ✗ | ✓ | Internal |
| `/api/v1/metadata/heartbeat/*` | POST | ✓ | ✓ | ✓ | Internal |

### Storage Service Endpoints

| Endpoint | HTTP Method | ADMIN | PRODUCER | CONSUMER | Public |
|----------|-------------|-------|----------|----------|--------|
| `/api/v1/storage/produce` | POST | ✓ | ✓ | ✗ | ✗ |
| `/api/v1/storage/consume` | POST | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/storage/health` | GET | ✓ | ✓ | ✓ | ✓ |
| `/api/v1/storage/info` | GET | ✓ | ✓ | ✓ | ✗ |
| `/api/v1/storage/metadata` | POST | ✓ | ✓ | ✓ | Internal |
| `/api/v1/storage/replication/*` | POST | ✓ | ✓ | ✓ | Internal |
| `/api/v1/consumer-groups/join` | POST | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/consumer-groups/heartbeat` | POST | ✓ | ✗ | ✓ | ✗ |
| `/api/v1/consumer-groups/{id}` | GET | ✓ | ✗ | ✓ | ✗ |

**Legend:**
- ✓ = Allowed
- ✗ = Denied (HTTP 403 Forbidden)
- Public = No authentication required
- Internal = Service-to-service communication (no JWT required)

---

## Role Capabilities

### ADMIN Role Capabilities

**Full Access to:**
- ✅ Create topics
- ✅ Delete topics
- ✅ List topics and brokers
- ✅ Describe topics and brokers
- ✅ Register brokers (internal)
- ✅ Produce messages to any topic
- ✅ Consume messages from any topic
- ✅ Manage consumer groups (list, describe, delete)
- ✅ View cluster metadata
- ✅ Access all read endpoints

**Use Cases:**
- System administration
- Infrastructure management
- Monitoring and troubleshooting
- Emergency operations

### PRODUCER Role Capabilities

**Allowed:**
- ✅ Produce messages to topics
- ✅ List topics
- ✅ Describe topics
- ✅ List brokers
- ✅ View cluster metadata (read-only)

**Denied:**
- ❌ Create or delete topics
- ❌ Consume messages
- ❌ Manage consumer groups
- ❌ Register brokers

**Use Cases:**
- Application data producers
- Event publishers
- Log shippers
- Message senders

### CONSUMER Role Capabilities

**Allowed:**
- ✅ Consume messages from topics
- ✅ Join consumer groups
- ✅ Send consumer heartbeats
- ✅ List and describe consumer groups
- ✅ List topics
- ✅ Describe topics
- ✅ List brokers
- ✅ View cluster metadata (read-only)

**Denied:**
- ❌ Create or delete topics
- ❌ Produce messages
- ❌ Register brokers

**Use Cases:**
- Application data consumers
- Event processors
- Log aggregators
- Message receivers

---

## CLI Commands by Role

### ADMIN Users Can Execute:

```bash
# Authentication
mycli login --username admin --password admin123
mycli logout
mycli refresh-token

# Topic Management (Full)
mycli create-topic --name orders --partitions 3 --replication-factor 2
mycli list-topics
mycli describe-topic --name orders
mycli delete-topic --name orders

# Cluster Management
mycli get-leader
mycli list-brokers

# Producer Operations
mycli produce --topic orders --key order-1 --value "data"

# Consumer Operations
mycli consume --topic orders --partition 0
mycli consume-group --topic orders --app-id processor
mycli list-groups
mycli describe-group --topic orders --app-id processor
```

### PRODUCER Users Can Execute:

```bash
# Authentication
mycli login --username producer1 --password prod123
mycli logout
mycli refresh-token

# Topic Management (Read-Only)
mycli list-topics
mycli describe-topic --name orders

# Cluster Management (Read-Only)
mycli get-leader
mycli list-brokers

# Producer Operations
mycli produce --topic orders --key order-1 --value "data"

# CANNOT Execute:
# mycli create-topic    ❌ HTTP 403
# mycli delete-topic    ❌ HTTP 403
# mycli consume         ❌ HTTP 403
# mycli consume-group   ❌ HTTP 403
# mycli list-groups     ❌ HTTP 403
```

### CONSUMER Users Can Execute:

```bash
# Authentication
mycli login --username consumer1 --password cons123
mycli logout
mycli refresh-token

# Topic Management (Read-Only)
mycli list-topics
mycli describe-topic --name orders

# Cluster Management (Read-Only)
mycli get-leader
mycli list-brokers

# Consumer Operations
mycli consume --topic orders --partition 0
mycli consume-group --topic orders --app-id processor
mycli list-groups
mycli describe-group --topic orders --app-id processor

# CANNOT Execute:
# mycli create-topic    ❌ HTTP 403
# mycli delete-topic    ❌ HTTP 403
# mycli produce         ❌ HTTP 403
```

---

## HTTP Response Codes

### Authentication & Authorization Responses

| Code | Status | Meaning | Solution |
|------|--------|---------|----------|
| 200 | OK | Request successful | - |
| 401 | Unauthorized | Missing or invalid JWT token | Login: `mycli login --username <user>` |
| 403 | Forbidden | Valid token but insufficient role | Use account with appropriate role |
| 503 | Service Unavailable | Not the Raft leader | Request will auto-retry with leader |

### Example Error Responses

**401 Unauthorized:**
```json
{
  "error": "Authentication required. Please login first: mycli login --username <user>"
}
```

**403 Forbidden:**
```json
{
  "error": "Admin role required to delete topics"
}
```

---

## Internal Endpoints (No JWT Required)

These endpoints are used for **service-to-service communication** and do not require JWT authentication:

### Metadata Service Internal
- `POST /api/v1/raft/*` - Raft consensus protocol
- `POST /api/v1/metadata/heartbeat/*` - Broker heartbeats
- `GET /api/v1/metadata/controller` - Controller discovery

### Storage Service Internal
- `POST /api/v1/storage/replication/*` - Replication between brokers
- `POST /api/v1/storage/metadata` - Metadata push from controller
- `POST /api/v1/isr/*` - In-Sync Replica management

---

## JWT Token Details

### Token Structure

```json
{
  "sub": "admin",
  "roles": ["ADMIN"],
  "iss": "DMQ-Metadata-Service",
  "aud": "DMQ-Services",
  "iat": 1700000000,
  "exp": 1700000900
}
```

### Token Properties

| Property | Description | Example |
|----------|-------------|---------|
| `sub` | Username (subject) | `"admin"` |
| `roles` | Array of user roles | `["ADMIN"]` |
| `iss` | Token issuer | `"DMQ-Metadata-Service"` |
| `aud` | Intended audience | `"DMQ-Services"` |
| `iat` | Issued at timestamp | `1700000000` |
| `exp` | Expiration timestamp | `1700000900` |

### Token Expiration

- **Default Expiry:** 900 seconds (15 minutes)
- **Configurable:** Set in `config/services.json` under `jwt.access-token-expiry-seconds`
- **Refresh:** Use `mycli refresh-token` to extend expiration without re-login
- **Grace Period:** Expired tokens can be refreshed within a short grace period

---

## Security Configuration

### JWT Secret

Located in `config/services.json`:

```json
{
  "jwt": {
    "secret": "dmq-secret-key-256-bits-for-hs256-signature-algorithm-change-in-production-please",
    "algorithm": "HS256",
    "access-token-expiry-seconds": 900
  }
}
```

**⚠️ IMPORTANT:** Change the secret in production! The secret must be:
- At least 32 characters
- Shared across all services (metadata and storage)
- Kept confidential

### Adding New Users

Edit `dmq-metadata-service/src/main/resources/application.yml`:

```yaml
dmq:
  security:
    jwt:
      issuer: "DMQ-Metadata-Service"
      audience: "DMQ-Services"
    users:
      - username: newuser
        password: newpass123
        roles:
          - PRODUCER
          - CONSUMER
```

Restart metadata service for changes to take effect.

---

## Best Practices

### 1. **Principle of Least Privilege**
Grant users only the minimum roles needed for their tasks.

### 2. **Use Application Accounts**
For applications, use dedicated accounts like `app1` with specific role combinations.

### 3. **Rotate Credentials**
Periodically change passwords and JWT secrets in production.

### 4. **Token Refresh**
Use `mycli refresh-token` for long-running operations instead of storing passwords.

### 5. **Monitor Access**
Check logs for authentication failures and unauthorized access attempts:
```bash
grep "JWT validation failed" logs/metadata-service.log
grep "lacks.*role" logs/storage-service.log
```

### 6. **Secure Token Storage**
The CLI stores tokens in `~/.dmq/token.properties` with appropriate file permissions.

---

## Troubleshooting

### "Authentication required" Error

**Problem:** HTTP 401 response

**Solutions:**
1. Login first: `mycli login --username <user> --password <pass>`
2. Check if token expired: `cat ~/.dmq/token.properties`
3. Refresh token: `mycli refresh-token`

### "Admin role required" Error

**Problem:** HTTP 403 response

**Solutions:**
1. Check your roles: `mycli login --username <user>` (shows roles on login)
2. Use admin account for topic creation/deletion
3. Request role addition from system administrator

### "Not the controller leader" Error

**Problem:** HTTP 503 response

**Solutions:**
- CLI automatically retries with the correct leader
- No action needed, request will succeed on retry

### Token Refresh Fails

**Problem:** "Token expired" even after refresh attempt

**Solutions:**
1. Grace period exceeded - login again: `mycli login --username <user>`
2. Check if services.json secret matches across all services

---

## CLI Help

For detailed command help:

```bash
mycli help
mycli login --help
mycli create-topic --help
mycli delete-topic --help
```

---

## Summary

| Feature | Details |
|---------|---------|
| **Authentication** | JWT token-based |
| **Roles** | ADMIN, PRODUCER, CONSUMER |
| **Token Expiry** | 900 seconds (15 minutes) |
| **Token Storage** | `~/.dmq/token.properties` |
| **Authorization** | Role-based access control on all endpoints |
| **Encryption** | HMAC-SHA256 (HS256) |
| **Refresh** | Supported with grace period |

---

**Document Version:** 1.0  
**Last Updated:** November 23, 2025  
**Related Documentation:**
- [Authentication Guide](AUTH_GUIDE.md)
- [CLI Guide](CLI-GUIDE.md)
- [API Reference](API_REFERENCE.md)
