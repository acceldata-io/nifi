# User Guide

## Getting Started

### Login

1. Open your web browser and navigate to the DC VM Management interface
2. Use the default credentials:
   - **Username**: `admin`
   - **Password**: `admin123`
3. Click "Login" to access the dashboard

**Important**: Change the default password immediately after first login!

## Dashboard Overview

The dashboard provides a high-level view of your data center infrastructure:

- **Total VMs**: Number of virtual machines in the system
- **Running VMs**: Currently active virtual machines
- **Total Hosts**: Number of baremetal hosts in the resource pool
- **Active Hosts**: Currently available baremetal hosts
- **Resource Utilization**: CPU, Memory, and Storage usage percentages

## Managing Virtual Machines

### Creating a Virtual Machine

1. Navigate to **VM Management** from the sidebar
2. Click **"Create VM"** button
3. Fill in the VM details:
   - **VM Name**: Unique identifier for your VM
   - **Operating System**: Choose from Ubuntu, CentOS, RHEL, or Windows
   - **OS Version**: Select the specific version
   - **CPU Cores**: Number of virtual CPU cores (1-32)
   - **Memory**: RAM allocation in GB (1-256)
   - **Storage**: Disk space in GB (10-2048)
4. Click **"Create VM"** to start provisioning

### VM Operations

#### Starting a VM
- Find the VM in the list
- Click the **"Start"** button (green play icon)
- The VM status will change to "running"

#### Stopping a VM
- Find the running VM in the list
- Click the **"Stop"** button (orange pause icon)
- The VM status will change to "stopped"

#### Terminating a VM
- Find the VM you want to remove
- Click the **"Delete"** button (red trash icon)
- Confirm the deletion in the popup dialog
- The VM will be permanently removed

### VM Status Indicators

- **🟢 Running**: VM is active and accessible
- **🟠 Stopped**: VM is shut down but not deleted
- **🔵 Provisioning**: VM is being created
- **🔴 Terminating**: VM is being deleted

## Managing Resources

### Adding Baremetal Hosts

1. Navigate to **Resources** from the sidebar
2. Click **"Add Host"** button
3. Enter host information:
   - **Hostname**: Descriptive name for the host
   - **IP Address**: Network address of the host
   - **CPU Cores**: Total CPU cores available
   - **Memory**: Total RAM in GB
   - **Storage**: Total disk space in GB
   - **Hypervisor Type**: KVM, Xen, or VMware
4. Click **"Add Host"** to register the host

### Resource Monitoring

The Resources page shows:
- **Total Hosts**: Number of registered baremetal hosts
- **Active Hosts**: Currently available hosts
- **Resource Utilization**: Overall usage statistics
- **Individual Host Status**: Status of each baremetal host

### Host Status Indicators

- **🟢 Active**: Host is available for VM provisioning
- **🟠 Maintenance**: Host is in maintenance mode
- **🔴 Offline**: Host is not responding

## Monitoring and Alerts

### System Monitoring

Navigate to **Monitoring** to view:
- **System Alerts**: Warnings and error messages
- **VM Metrics**: CPU, memory, and network usage graphs
- **Host Metrics**: Physical host performance data
- **Resource Utilization**: Real-time usage statistics

### Understanding Alerts

- **🟠 Warning**: Resource usage is high but not critical
- **🔴 Error**: Critical issues requiring immediate attention
- **🔵 Info**: Informational messages about system status

### Metrics and Graphs

- **CPU Usage**: Percentage of CPU utilization over time
- **Memory Usage**: RAM consumption patterns
- **Storage Usage**: Disk space utilization
- **Network Traffic**: Data transfer rates

## Best Practices

### VM Management

1. **Resource Planning**: Allocate resources based on actual needs
2. **Naming Convention**: Use descriptive names for easy identification
3. **Regular Cleanup**: Remove unused VMs to free up resources
4. **Backup Strategy**: Implement regular backups for important VMs

### Resource Optimization

1. **Monitor Utilization**: Regularly check resource usage patterns
2. **Load Balancing**: Distribute VMs across available hosts
3. **Capacity Planning**: Plan for future resource requirements
4. **Performance Tuning**: Optimize VM configurations for better performance

### Security

1. **Access Control**: Use strong passwords and limit user access
2. **Network Security**: Implement proper firewall rules
3. **Regular Updates**: Keep all components updated
4. **Audit Logs**: Monitor system access and changes

## Troubleshooting

### Common Issues

#### VM Won't Start
- Check if the host has sufficient resources
- Verify the VM configuration is valid
- Check host connectivity and status

#### High Resource Usage
- Review running VMs and their resource allocation
- Consider migrating VMs to less utilized hosts
- Scale up your baremetal infrastructure

#### Connection Issues
- Verify network connectivity between components
- Check firewall settings
- Ensure all services are running properly

### Getting Help

1. **Check Logs**: Review system logs for error messages
2. **Monitor Alerts**: Look for system-generated alerts
3. **Resource Status**: Verify all hosts are online and available
4. **Documentation**: Refer to API documentation for technical details

## Advanced Features

### API Access

The system provides a REST API for programmatic access:
- **API Documentation**: Available at `/docs` endpoint
- **Authentication**: Use JWT tokens for API access
- **Rate Limiting**: 1000 requests per hour per user

### Integration

- **Webhook Support**: Configure webhooks for event notifications
- **External Monitoring**: Integrate with Prometheus/Grafana
- **Automation**: Use Ansible playbooks for custom provisioning

### Customization

- **VM Templates**: Create custom OS templates
- **Resource Pools**: Organize hosts into logical groups
- **User Roles**: Implement role-based access control