"""
Monitoring and metrics API routes
"""

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session
from typing import List, Dict, Any
from pydantic import BaseModel
from datetime import datetime, timedelta
import uuid

from models.database import get_db, VirtualMachine, BaremetalHost

router = APIRouter()

class VMMetricsResponse(BaseModel):
    vm_id: uuid.UUID
    vm_name: str
    cpu_usage_percent: float
    memory_usage_percent: float
    disk_usage_percent: float
    network_rx_bytes: int
    network_tx_bytes: int
    timestamp: datetime

class HostMetricsResponse(BaseModel):
    host_id: uuid.UUID
    hostname: str
    cpu_usage_percent: float
    memory_usage_percent: float
    disk_usage_percent: float
    temperature_celsius: float
    power_consumption_watts: float
    timestamp: datetime

class SystemMetricsResponse(BaseModel):
    total_vms: int
    running_vms: int
    stopped_vms: int
    provisioning_vms: int
    total_hosts: int
    active_hosts: int
    overall_cpu_utilization: float
    overall_memory_utilization: float
    overall_storage_utilization: float
    timestamp: datetime

@router.get("/vm-metrics/{vm_id}")
async def get_vm_metrics(
    vm_id: uuid.UUID,
    hours: int = 24,
    db: Session = Depends(get_db)
):
    """Get VM metrics for the last N hours"""
    
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    if not vm:
        return {"error": "VM not found"}
    
    # In a real implementation, this would query actual metrics from a time-series database
    # For now, return mock data
    mock_metrics = []
    start_time = datetime.utcnow() - timedelta(hours=hours)
    
    for i in range(hours * 4):  # 15-minute intervals
        timestamp = start_time + timedelta(minutes=i * 15)
        mock_metrics.append({
            "vm_id": vm_id,
            "vm_name": vm.name,
            "cpu_usage_percent": 25.0 + (i % 50),  # Mock CPU usage
            "memory_usage_percent": 40.0 + (i % 30),  # Mock memory usage
            "disk_usage_percent": 60.0 + (i % 20),  # Mock disk usage
            "network_rx_bytes": 1024 * 1024 * (i + 1),  # Mock network RX
            "network_tx_bytes": 512 * 1024 * (i + 1),  # Mock network TX
            "timestamp": timestamp.isoformat()
        })
    
    return mock_metrics

@router.get("/host-metrics/{host_id}")
async def get_host_metrics(
    host_id: uuid.UUID,
    hours: int = 24,
    db: Session = Depends(get_db)
):
    """Get host metrics for the last N hours"""
    
    host = db.query(BaremetalHost).filter(BaremetalHost.id == host_id).first()
    if not host:
        return {"error": "Host not found"}
    
    # In a real implementation, this would query actual metrics from a time-series database
    # For now, return mock data
    mock_metrics = []
    start_time = datetime.utcnow() - timedelta(hours=hours)
    
    for i in range(hours * 4):  # 15-minute intervals
        timestamp = start_time + timedelta(minutes=i * 15)
        mock_metrics.append({
            "host_id": host_id,
            "hostname": host.hostname,
            "cpu_usage_percent": 30.0 + (i % 40),  # Mock CPU usage
            "memory_usage_percent": 50.0 + (i % 25),  # Mock memory usage
            "disk_usage_percent": 70.0 + (i % 15),  # Mock disk usage
            "temperature_celsius": 45.0 + (i % 10),  # Mock temperature
            "power_consumption_watts": 200.0 + (i % 50),  # Mock power consumption
            "timestamp": timestamp.isoformat()
        })
    
    return mock_metrics

@router.get("/system-metrics")
async def get_system_metrics(db: Session = Depends(get_db)):
    """Get overall system metrics"""
    
    # Get VM counts by status
    total_vms = db.query(VirtualMachine).count()
    running_vms = db.query(VirtualMachine).filter(VirtualMachine.status == "running").count()
    stopped_vms = db.query(VirtualMachine).filter(VirtualMachine.status == "stopped").count()
    provisioning_vms = db.query(VirtualMachine).filter(VirtualMachine.status == "provisioning").count()
    
    # Get host counts
    total_hosts = db.query(BaremetalHost).count()
    active_hosts = db.query(BaremetalHost).filter(BaremetalHost.status == "active").count()
    
    # Calculate resource utilization
    active_hosts_data = db.query(BaremetalHost).filter(BaremetalHost.status == "active").all()
    running_vms_data = db.query(VirtualMachine).filter(VirtualMachine.status == "running").all()
    
    total_cpu = sum(host.cpu_cores for host in active_hosts_data)
    total_memory = sum(host.memory_gb for host in active_hosts_data)
    total_storage = sum(host.storage_gb for host in active_hosts_data)
    
    used_cpu = sum(vm.cpu_cores for vm in running_vms_data)
    used_memory = sum(vm.memory_gb for vm in running_vms_data)
    used_storage = sum(vm.storage_gb for vm in running_vms_data)
    
    cpu_utilization = (used_cpu / total_cpu * 100) if total_cpu > 0 else 0
    memory_utilization = (used_memory / total_memory * 100) if total_memory > 0 else 0
    storage_utilization = (used_storage / total_storage * 100) if total_storage > 0 else 0
    
    return SystemMetricsResponse(
        total_vms=total_vms,
        running_vms=running_vms,
        stopped_vms=stopped_vms,
        provisioning_vms=provisioning_vms,
        total_hosts=total_hosts,
        active_hosts=active_hosts,
        overall_cpu_utilization=round(cpu_utilization, 2),
        overall_memory_utilization=round(memory_utilization, 2),
        overall_storage_utilization=round(storage_utilization, 2),
        timestamp=datetime.utcnow()
    )

@router.get("/alerts")
async def get_alerts(db: Session = Depends(get_db)):
    """Get system alerts and warnings"""
    
    alerts = []
    
    # Check for high resource utilization
    active_hosts = db.query(BaremetalHost).filter(BaremetalHost.status == "active").all()
    running_vms = db.query(VirtualMachine).filter(VirtualMachine.status == "running").all()
    
    if active_hosts:
        total_cpu = sum(host.cpu_cores for host in active_hosts)
        total_memory = sum(host.memory_gb for host in active_hosts)
        total_storage = sum(host.storage_gb for host in active_hosts)
        
        used_cpu = sum(vm.cpu_cores for vm in running_vms)
        used_memory = sum(vm.memory_gb for vm in running_vms)
        used_storage = sum(vm.storage_gb for vm in running_vms)
        
        cpu_util = (used_cpu / total_cpu * 100) if total_cpu > 0 else 0
        memory_util = (used_memory / total_memory * 100) if total_memory > 0 else 0
        storage_util = (used_storage / total_storage * 100) if total_storage > 0 else 0
        
        if cpu_util > 90:
            alerts.append({
                "type": "warning",
                "message": f"High CPU utilization: {cpu_util:.1f}%",
                "timestamp": datetime.utcnow().isoformat()
            })
        
        if memory_util > 90:
            alerts.append({
                "type": "warning", 
                "message": f"High memory utilization: {memory_util:.1f}%",
                "timestamp": datetime.utcnow().isoformat()
            })
        
        if storage_util > 90:
            alerts.append({
                "type": "warning",
                "message": f"High storage utilization: {storage_util:.1f}%",
                "timestamp": datetime.utcnow().isoformat()
            })
    
    # Check for offline hosts
    offline_hosts = db.query(BaremetalHost).filter(BaremetalHost.status == "offline").count()
    if offline_hosts > 0:
        alerts.append({
            "type": "error",
            "message": f"{offline_hosts} host(s) are offline",
            "timestamp": datetime.utcnow().isoformat()
        })
    
    return alerts