# Deployment Guide

## Prerequisites

### System Requirements

- **Operating System**: Linux (Ubuntu 20.04+ recommended)
- **CPU**: Minimum 4 cores, 8 cores recommended
- **Memory**: Minimum 8GB RAM, 16GB recommended
- **Storage**: Minimum 100GB free space
- **Network**: Internet connection for downloading images

### Software Requirements

- Docker 20.10+
- Docker Compose 2.0+
- Python 3.9+ (for development)
- Node.js 16+ (for frontend development)
- Git

### Hypervisor Requirements

For each baremetal host you want to manage:

- **KVM/QEMU** with libvirt
- **SSH access** with root privileges
- **Network connectivity** from the management server
- **Sufficient resources** for VM provisioning

## Installation

### 1. Clone the Repository

```bash
git clone <your-repo-url>
cd dc-vm-management
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env with your configuration
```

**Important Configuration:**

```bash
# Change these default values!
SECRET_KEY=your-secure-secret-key-here
POSTGRES_PASSWORD=your-secure-database-password
JWT_SECRET_KEY=your-jwt-secret-key-here

# Configure your network
FRONTEND_URL=http://your-server-ip:3000
API_HOST=0.0.0.0
```

### 3. Run Setup Script

```bash
chmod +x scripts/setup.sh
./scripts/setup.sh
```

The setup script will:
- Create necessary directories
- Build Docker images
- Start all services
- Initialize the database
- Set up default admin user

### 4. Verify Installation

Check that all services are running:

```bash
docker-compose ps
```

You should see all services in "Up" status.

## Accessing the Application

- **Web GUI**: http://your-server-ip:3000
- **API Documentation**: http://your-server-ip:8000/docs
- **Grafana**: http://your-server-ip:3001 (admin/admin)
- **Prometheus**: http://your-server-ip:9090

## Adding Baremetal Hosts

### 1. Prepare Your Hosts

On each baremetal host, install and configure KVM:

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install qemu-kvm libvirt-daemon-system libvirt-clients bridge-utils
sudo usermod -a -G libvirt $USER

# CentOS/RHEL
sudo yum install qemu-kvm libvirt libvirt-python libguestfs-tools
sudo systemctl enable libvirtd
sudo systemctl start libvirtd
```

### 2. Configure SSH Access

Set up SSH key authentication for root access:

```bash
# Generate SSH key (on management server)
ssh-keygen -t rsa -b 4096 -f ~/.ssh/dc_vm_management

# Copy key to baremetal hosts
ssh-copy-id -i ~/.ssh/dc_vm_management.pub root@baremetal-host-ip
```

### 3. Add Hosts via Web Interface

1. Login to the web interface
2. Navigate to "Resources" → "Add Host"
3. Enter host details:
   - Hostname
   - IP Address
   - CPU Cores
   - Memory (GB)
   - Storage (GB)
   - Hypervisor Type (KVM)

## Production Deployment

### 1. Security Considerations

- **Change default passwords** immediately
- **Use HTTPS** for production (configure reverse proxy)
- **Restrict network access** to management interfaces
- **Regular security updates** for all components
- **Backup database** regularly

### 2. Scaling Considerations

- **Database**: Use external PostgreSQL cluster for high availability
- **Redis**: Use external Redis cluster for session storage
- **Load Balancing**: Deploy multiple API instances behind load balancer
- **Monitoring**: Set up external monitoring and alerting

### 3. Backup Strategy

```bash
# Database backup
docker-compose exec postgres pg_dump -U dc_user dc_vm_management > backup.sql

# Configuration backup
tar -czf config-backup.tar.gz .env docker-compose.yml config/

# VM images backup (if stored locally)
rsync -av /var/lib/libvirt/images/ /backup/vm-images/
```

### 4. High Availability Setup

For production environments, consider:

- **Database clustering** (PostgreSQL streaming replication)
- **Redis clustering** for session management
- **Load balancer** for API endpoints
- **Shared storage** for VM images
- **Automated failover** mechanisms

## Troubleshooting

### Common Issues

#### Services Won't Start

```bash
# Check logs
docker-compose logs backend
docker-compose logs frontend
docker-compose logs postgres

# Check resource usage
docker stats
```

#### Database Connection Issues

```bash
# Test database connection
docker-compose exec backend python -c "from models.database import engine; print(engine.url)"

# Reset database
docker-compose down
docker volume rm dc-vm-management_postgres_data
docker-compose up -d postgres
```

#### VM Provisioning Fails

1. Check SSH connectivity to baremetal hosts
2. Verify libvirt is running on hosts
3. Check available resources on hosts
4. Review Ansible playbook logs

#### Frontend Won't Load

1. Check if backend API is accessible
2. Verify CORS configuration
3. Check browser console for errors
4. Ensure all environment variables are set

### Log Locations

- **Application logs**: `docker-compose logs [service-name]`
- **Database logs**: `docker-compose logs postgres`
- **System logs**: `/var/log/syslog` (on host system)

### Performance Tuning

#### Database Optimization

```sql
-- Increase connection pool
ALTER SYSTEM SET max_connections = 200;

-- Optimize for VM workloads
ALTER SYSTEM SET shared_buffers = '256MB';
ALTER SYSTEM SET effective_cache_size = '1GB';
```

#### Resource Limits

Update `docker-compose.yml` to set resource limits:

```yaml
services:
  backend:
    deploy:
      resources:
        limits:
          memory: 2G
          cpus: '1.0'
```

## Maintenance

### Regular Tasks

- **Monitor resource usage** and scale as needed
- **Update system packages** and Docker images
- **Review security logs** and access patterns
- **Backup configuration** and database regularly
- **Clean up old VM images** and logs

### Updates

```bash
# Pull latest changes
git pull origin main

# Rebuild and restart services
docker-compose down
docker-compose build
docker-compose up -d

# Run database migrations (if any)
docker-compose exec backend python -m alembic upgrade head
```