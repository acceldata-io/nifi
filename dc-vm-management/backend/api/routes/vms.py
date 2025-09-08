"""
Virtual Machine management API routes
"""

from fastapi import APIRouter, Depends, HTTPException, status, BackgroundTasks
from sqlalchemy.orm import Session
from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime
import uuid

from models.database import get_db, VirtualMachine, BaremetalHost, VMTemplate
from services.vm_manager import VMManager
from services.resource_manager import ResourceManager

router = APIRouter()

class VMCreateRequest(BaseModel):
    name: str
    os_type: str
    os_version: str
    cpu_cores: int
    memory_gb: int
    storage_gb: int
    template_id: Optional[uuid.UUID] = None

class VMResponse(BaseModel):
    id: uuid.UUID
    name: str
    host_id: uuid.UUID
    os_type: str
    os_version: str
    cpu_cores: int
    memory_gb: int
    storage_gb: int
    ip_address: Optional[str]
    status: str
    created_at: datetime
    updated_at: datetime
    
    class Config:
        from_attributes = True

class VMUpdateRequest(BaseModel):
    cpu_cores: Optional[int] = None
    memory_gb: Optional[int] = None
    storage_gb: Optional[int] = None

@router.get("/", response_model=List[VMResponse])
async def list_vms(
    skip: int = 0,
    limit: int = 100,
    status_filter: Optional[str] = None,
    db: Session = Depends(get_db)
):
    """List all virtual machines"""
    query = db.query(VirtualMachine)
    
    if status_filter:
        query = query.filter(VirtualMachine.status == status_filter)
    
    vms = query.offset(skip).limit(limit).all()
    return vms

@router.post("/", response_model=VMResponse, status_code=status.HTTP_201_CREATED)
async def create_vm(
    vm_request: VMCreateRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db)
):
    """Create a new virtual machine"""
    
    # Check if VM name already exists
    existing_vm = db.query(VirtualMachine).filter(VirtualMachine.name == vm_request.name).first()
    if existing_vm:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="VM with this name already exists"
        )
    
    # Find available host
    available_host = db.query(BaremetalHost).filter(
        BaremetalHost.status == "active"
    ).first()
    
    if not available_host:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="No available baremetal hosts"
        )
    
    # Create VM record
    vm = VirtualMachine(
        name=vm_request.name,
        host_id=available_host.id,
        os_type=vm_request.os_type,
        os_version=vm_request.os_version,
        cpu_cores=vm_request.cpu_cores,
        memory_gb=vm_request.memory_gb,
        storage_gb=vm_request.storage_gb,
        status="provisioning"
    )
    
    db.add(vm)
    db.commit()
    db.refresh(vm)
    
    # Start VM provisioning in background
    background_tasks.add_task(provision_vm_task, vm.id, vm_request)
    
    return vm

@router.get("/{vm_id}", response_model=VMResponse)
async def get_vm(vm_id: uuid.UUID, db: Session = Depends(get_db)):
    """Get virtual machine details"""
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    
    if not vm:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Virtual machine not found"
        )
    
    return vm

@router.put("/{vm_id}", response_model=VMResponse)
async def update_vm(
    vm_id: uuid.UUID,
    vm_update: VMUpdateRequest,
    db: Session = Depends(get_db)
):
    """Update virtual machine resources"""
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    
    if not vm:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Virtual machine not found"
        )
    
    if vm.status not in ["running", "stopped"]:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot update VM in current status"
        )
    
    # Update resources
    if vm_update.cpu_cores is not None:
        vm.cpu_cores = vm_update.cpu_cores
    if vm_update.memory_gb is not None:
        vm.memory_gb = vm_update.memory_gb
    if vm_update.storage_gb is not None:
        vm.storage_gb = vm_update.storage_gb
    
    vm.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(vm)
    
    return vm

@router.delete("/{vm_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_vm(
    vm_id: uuid.UUID,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db)
):
    """Terminate virtual machine"""
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    
    if not vm:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Virtual machine not found"
        )
    
    # Start VM termination in background
    background_tasks.add_task(terminate_vm_task, vm_id)
    
    # Update status immediately
    vm.status = "terminating"
    db.commit()

@router.post("/{vm_id}/start", response_model=VMResponse)
async def start_vm(vm_id: uuid.UUID, db: Session = Depends(get_db)):
    """Start virtual machine"""
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    
    if not vm:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Virtual machine not found"
        )
    
    if vm.status != "stopped":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="VM must be stopped to start"
        )
    
    vm.status = "running"
    vm.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(vm)
    
    return vm

@router.post("/{vm_id}/stop", response_model=VMResponse)
async def stop_vm(vm_id: uuid.UUID, db: Session = Depends(get_db)):
    """Stop virtual machine"""
    vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
    
    if not vm:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Virtual machine not found"
        )
    
    if vm.status != "running":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="VM must be running to stop"
        )
    
    vm.status = "stopped"
    vm.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(vm)
    
    return vm

async def provision_vm_task(vm_id: uuid.UUID, vm_request: VMCreateRequest):
    """Background task for VM provisioning"""
    # This would integrate with the actual VM provisioning logic
    # For now, just simulate the process
    pass

async def terminate_vm_task(vm_id: uuid.UUID):
    """Background task for VM termination"""
    # This would integrate with the actual VM termination logic
    # For now, just simulate the process
    pass