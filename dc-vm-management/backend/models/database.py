"""
Database models and configuration
"""

from sqlalchemy import create_engine, Column, Integer, String, DateTime, Boolean, Float, Text, ForeignKey
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, relationship
from sqlalchemy.dialects.postgresql import UUID
import uuid
from datetime import datetime
import os

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://user:password@localhost/dc_vm_management")

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

class BaremetalHost(Base):
    """Baremetal host information"""
    __tablename__ = "baremetal_hosts"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    hostname = Column(String(255), unique=True, nullable=False)
    ip_address = Column(String(45), nullable=False)  # IPv4/IPv6
    cpu_cores = Column(Integer, nullable=False)
    memory_gb = Column(Integer, nullable=False)
    storage_gb = Column(Integer, nullable=False)
    status = Column(String(50), default="active")  # active, maintenance, offline
    hypervisor_type = Column(String(50), default="kvm")  # kvm, xen, vmware
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # Relationships
    vms = relationship("VirtualMachine", back_populates="host")

class VirtualMachine(Base):
    """Virtual machine information"""
    __tablename__ = "virtual_machines"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), unique=True, nullable=False)
    host_id = Column(UUID(as_uuid=True), ForeignKey("baremetal_hosts.id"), nullable=False)
    os_type = Column(String(100), nullable=False)  # ubuntu, centos, rhel, windows
    os_version = Column(String(100), nullable=False)
    cpu_cores = Column(Integer, nullable=False)
    memory_gb = Column(Integer, nullable=False)
    storage_gb = Column(Integer, nullable=False)
    ip_address = Column(String(45))
    status = Column(String(50), default="provisioning")  # provisioning, running, stopped, terminated
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    # Relationships
    host = relationship("BaremetalHost", back_populates="vms")

class ResourcePool(Base):
    """Resource pool configuration"""
    __tablename__ = "resource_pools"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), unique=True, nullable=False)
    description = Column(Text)
    cpu_cores_total = Column(Integer, nullable=False)
    memory_gb_total = Column(Integer, nullable=False)
    storage_gb_total = Column(Integer, nullable=False)
    cpu_cores_used = Column(Integer, default=0)
    memory_gb_used = Column(Integer, default=0)
    storage_gb_used = Column(Integer, default=0)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)

class VMTemplate(Base):
    """VM template definitions"""
    __tablename__ = "vm_templates"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    name = Column(String(255), unique=True, nullable=False)
    os_type = Column(String(100), nullable=False)
    os_version = Column(String(100), nullable=False)
    cpu_cores = Column(Integer, nullable=False)
    memory_gb = Column(Integer, nullable=False)
    storage_gb = Column(Integer, nullable=False)
    image_url = Column(String(500))
    cloud_init_config = Column(Text)
    created_at = Column(DateTime, default=datetime.utcnow)

class User(Base):
    """User authentication"""
    __tablename__ = "users"
    
    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username = Column(String(100), unique=True, nullable=False)
    email = Column(String(255), unique=True, nullable=False)
    hashed_password = Column(String(255), nullable=False)
    is_active = Column(Boolean, default=True)
    is_admin = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.utcnow)

async def init_db():
    """Initialize database tables"""
    Base.metadata.create_all(bind=engine)

def get_db():
    """Database dependency"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()