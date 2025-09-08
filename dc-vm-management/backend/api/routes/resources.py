"""
Resource management API routes
"""

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime
import uuid

from models.database import get_db, BaremetalHost, ResourcePool, VirtualMachine

router = APIRouter()

class BaremetalHostCreateRequest(BaseModel):
    hostname: str
    ip_address: str
    cpu_cores: int
    memory_gb: int
    storage_gb: int
    hypervisor_type: str = "kvm"

class BaremetalHostResponse(BaseModel):
    id: uuid.UUID
    hostname: str
    ip_address: str
    cpu_cores: int
    memory_gb: int
    storage_gb: int
    status: str
    hypervisor_type: str
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True

class ResourcePoolResponse(BaseModel):
    id: uuid.UUID
    name: str
    description: Optional[str]
    cpu_cores_total: int
    memory_gb_total: int
    storage_gb_total: int
    cpu_cores_used: int
    memory_gb_used: int
    storage_gb_used: int
    cpu_cores_available: int
    memory_gb_available: int
    storage_gb_available: int
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True

class ResourceStatsResponse(BaseModel):
    total_hosts: int
    active_hosts: int
    total_cpu_cores: int
    total_memory_gb: int
    total_storage_gb: int
    used_cpu_cores: int
    used_memory_gb: int
    used_storage_gb: int
    utilization_percentage: float

@router.get("/hosts", response_model=List[BaremetalHostResponse])
async def list_baremetal_hosts(
    skip: int = 0,
    limit: int = 100,
    status_filter: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """List all baremetal hosts"""
    query = db.query(BaremetalHost)
    
    if status_filter:
        query = query.filter(BaremetalHost.status == status_filter)
    
    hosts = query.offset(skip).limit(limit).all()
    return hosts

@router.post("/hosts", response_model=BaremetalHostResponse, status_code=status.HTTP_201_CREATED)
async def add_baremetal_host(
    host_request: BaremetalHostCreateRequest,
    db: Session = Depends(get_db)
):
    """Add a new baremetal host to the pool"""
    
    # Check if hostname already exists
    existing_host = db.query(BaremetalHost).filter(
        BaremetalHost.hostname == host_request.hostname
    ).first()
    
    if existing_host:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Host with this hostname already exists"
        )
    
    # Check if IP address already exists
    existing_ip = db.query(BaremetalHost).filter(
        BaremetalHost.ip_address == host_request.ip_address
    ).first()
    
    if existing_ip:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Host with this IP address already exists"
        )
    
    host = BaremetalHost(
        hostname=host_request.hostname,
        ip_address=host_request.ip_address,
        cpu_cores=host_request.cpu_cores,
        memory_gb=host_request.memory_gb,
        storage_gb=host_request.storage_gb,
        hypervisor_type=host_request.hypervisor_type,
        status="active"
    )
    
    db.add(host)
    db.commit()
    db.refresh(host)
    
    return host

@router.get("/hosts/{host_id}", response_model=BaremetalHostResponse)
async def get_baremetal_host(host_id: uuid.UUID, db: Session = Depends(get_db)):
    """Get baremetal host details"""
    host = db.query(BaremetalHost).filter(BaremetalHost.id == host_id).first()
    
    if not host:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Baremetal host not found"
        )
    
    return host

@router.delete("/hosts/{host_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_baremetal_host(host_id: uuid.UUID, db: Session = Depends(get_db)):
    """Remove baremetal host from pool"""
    host = db.query(BaremetalHost).filter(BaremetalHost.id == host_id).first()
    
    if not host:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Baremetal host not found"
        )
    
    # Check if host has running VMs
    running_vms = db.query(VirtualMachine).filter(
        VirtualMachine.host_id == host_id,
        VirtualMachine.status.in_(["running", "provisioning"])
    ).count()
    
    if running_vms > 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot remove host with {running_vms} running VMs"
        )
    
    db.delete(host)
    db.commit()

@router.get("/pools", response_model=List[ResourcePoolResponse])
async def list_resource_pools(db: Session = Depends(get_db)):
    """List all resource pools"""
    pools = db.query(ResourcePool).all()
    
    # Calculate available resources
    result = []
    for pool in pools:
        pool_dict = pool.__dict__.copy()
        pool_dict["cpu_cores_available"] = pool.cpu_cores_total - pool.cpu_cores_used
        pool_dict["memory_gb_available"] = pool.memory_gb_total - pool.memory_gb_used
        pool_dict["storage_gb_available"] = pool.storage_gb_total - pool.storage_gb_used
        result.append(pool_dict)
    
    return result

@router.get("/stats", response_model=ResourceStatsResponse)
async def get_resource_stats(db: Session = Depends(get_db)):
    """Get overall resource statistics"""
    
    # Get total hosts
    total_hosts = db.query(BaremetalHost).count()
    active_hosts = db.query(BaremetalHost).filter(BaremetalHost.status == "active").count()
    
    # Calculate total resources
    hosts = db.query(BaremetalHost).filter(BaremetalHost.status == "active").all()
    total_cpu_cores = sum(host.cpu_cores for host in hosts)
    total_memory_gb = sum(host.memory_gb for host in hosts)
    total_storage_gb = sum(host.storage_gb for host in hosts)
    
    # Calculate used resources
    vms = db.query(VirtualMachine).filter(
        VirtualMachine.status.in_(["running", "provisioning"])
    ).all()
    used_cpu_cores = sum(vm.cpu_cores for vm in vms)
    used_memory_gb = sum(vm.memory_gb for vm in vms)
    used_storage_gb = sum(vm.storage_gb for vm in vms)
    
    # Calculate utilization percentage
    total_resources = total_cpu_cores + total_memory_gb + total_storage_gb
    used_resources = used_cpu_cores + used_memory_gb + used_storage_gb
    utilization_percentage = (used_resources / total_resources * 100) if total_resources > 0 else 0
    
    return ResourceStatsResponse(
        total_hosts=total_hosts,
        active_hosts=active_hosts,
        total_cpu_cores=total_cpu_cores,
        total_memory_gb=total_memory_gb,
        total_storage_gb=total_storage_gb,
        used_cpu_cores=used_cpu_cores,
        used_memory_gb=used_memory_gb,
        used_storage_gb=used_storage_gb,
        utilization_percentage=round(utilization_percentage, 2)
    )