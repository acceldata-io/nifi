# DC VM Management API Documentation

## Overview

The DC VM Management API provides RESTful endpoints for managing virtual machines, baremetal resources, and monitoring across your data center infrastructure.

## Base URL

```
http://localhost:8000/api/v1
```

## Authentication

The API uses JWT (JSON Web Tokens) for authentication. Include the token in the Authorization header:

```
Authorization: Bearer <your-jwt-token>
```

## Endpoints

### Authentication

#### POST /auth/login
Login and get access token.

**Request Body:**
```json
{
  "username": "admin",
  "password": "admin123"
}
```

**Response:**
```json
{
  "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9...",
  "token_type": "bearer"
}
```

#### POST /auth/register
Register a new user.

**Request Body:**
```json
{
  "username": "newuser",
  "email": "user@example.com",
  "password": "password123"
}
```

### Virtual Machines

#### GET /vms
List all virtual machines.

**Query Parameters:**
- `skip` (int): Number of records to skip (default: 0)
- `limit` (int): Maximum number of records to return (default: 100)
- `status_filter` (string): Filter by VM status

**Response:**
```json
[
  {
    "id": "uuid",
    "name": "vm-001",
    "host_id": "uuid",
    "os_type": "ubuntu",
    "os_version": "22.04",
    "cpu_cores": 2,
    "memory_gb": 4,
    "storage_gb": 20,
    "ip_address": "192.168.1.100",
    "status": "running",
    "created_at": "2024-01-01T00:00:00Z",
    "updated_at": "2024-01-01T00:00:00Z"
  }
]
```

#### POST /vms
Create a new virtual machine.

**Request Body:**
```json
{
  "name": "vm-001",
  "os_type": "ubuntu",
  "os_version": "22.04",
  "cpu_cores": 2,
  "memory_gb": 4,
  "storage_gb": 20
}
```

#### GET /vms/{vm_id}
Get virtual machine details.

#### PUT /vms/{vm_id}
Update virtual machine resources.

**Request Body:**
```json
{
  "cpu_cores": 4,
  "memory_gb": 8,
  "storage_gb": 50
}
```

#### DELETE /vms/{vm_id}
Terminate virtual machine.

#### POST /vms/{vm_id}/start
Start virtual machine.

#### POST /vms/{vm_id}/stop
Stop virtual machine.

### Resources

#### GET /resources/hosts
List all baremetal hosts.

#### POST /resources/hosts
Add a new baremetal host.

**Request Body:**
```json
{
  "hostname": "host-001",
  "ip_address": "192.168.1.10",
  "cpu_cores": 16,
  "memory_gb": 64,
  "storage_gb": 1000,
  "hypervisor_type": "kvm"
}
```

#### GET /resources/hosts/{host_id}
Get baremetal host details.

#### DELETE /resources/hosts/{host_id}
Remove baremetal host from pool.

#### GET /resources/stats
Get overall resource statistics.

**Response:**
```json
{
  "total_hosts": 5,
  "active_hosts": 4,
  "total_cpu_cores": 80,
  "total_memory_gb": 320,
  "total_storage_gb": 5000,
  "used_cpu_cores": 24,
  "used_memory_gb": 96,
  "used_storage_gb": 1200,
  "utilization_percentage": 25.5
}
```

### Monitoring

#### GET /monitoring/system-metrics
Get overall system metrics.

#### GET /monitoring/vm-metrics/{vm_id}
Get VM metrics for the last N hours.

**Query Parameters:**
- `hours` (int): Number of hours to retrieve metrics for (default: 24)

#### GET /monitoring/host-metrics/{host_id}
Get host metrics for the last N hours.

#### GET /monitoring/alerts
Get system alerts and warnings.

## Error Responses

All error responses follow this format:

```json
{
  "detail": "Error message"
}
```

**HTTP Status Codes:**
- `200` - Success
- `201` - Created
- `400` - Bad Request
- `401` - Unauthorized
- `404` - Not Found
- `500` - Internal Server Error

## Rate Limiting

API requests are rate limited to 1000 requests per hour per user.

## WebSocket Events

The API supports WebSocket connections for real-time updates:

- `vm_status_changed` - VM status updated
- `host_status_changed` - Host status updated
- `resource_alert` - Resource utilization alert