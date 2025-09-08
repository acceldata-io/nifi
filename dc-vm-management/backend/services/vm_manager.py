"""
Virtual Machine management service
"""

import asyncio
import subprocess
import os
from typing import Optional, Dict, Any
from datetime import datetime
import uuid
import libvirt
from sqlalchemy.orm import Session

from models.database import VirtualMachine, BaremetalHost

class VMManager:
    """Manages virtual machine lifecycle operations"""
    
    def __init__(self, resource_manager):
        self.resource_manager = resource_manager
        self.libvirt_connections = {}
    
    async def provision_vm(
        self,
        vm_id: uuid.UUID,
        vm_config: Dict[str, Any],
        db: Session
    ) -> bool:
        """Provision a new virtual machine"""
        try:
            vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
            if not vm:
                return False
            
            host = db.query(BaremetalHost).filter(BaremetalHost.id == vm.host_id).first()
            if not host:
                return False
            
            # Connect to hypervisor
            conn = await self._get_libvirt_connection(host.ip_address)
            if not conn:
                return False
            
            # Create VM domain
            domain_xml = self._generate_domain_xml(vm, vm_config)
            domain = conn.createXML(domain_xml, 0)
            
            if domain:
                # Update VM status
                vm.status = "running"
                vm.updated_at = datetime.utcnow()
                db.commit()
                
                # Get VM IP address
                vm_ip = await self._get_vm_ip_address(domain)
                if vm_ip:
                    vm.ip_address = vm_ip
                    db.commit()
                
                return True
            
            return False
            
        except Exception as e:
            print(f"Error provisioning VM {vm_id}: {e}")
            return False
    
    async def terminate_vm(
        self,
        vm_id: uuid.UUID,
        db: Session
    ) -> bool:
        """Terminate a virtual machine"""
        try:
            vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
            if not vm:
                return False
            
            host = db.query(BaremetalHost).filter(BaremetalHost.id == vm.host_id).first()
            if not host:
                return False
            
            # Connect to hypervisor
            conn = await self._get_libvirt_connection(host.ip_address)
            if not conn:
                return False
            
            # Find and destroy domain
            domain = conn.lookupByName(vm.name)
            if domain:
                if domain.isActive():
                    domain.destroy()
                domain.undefine()
            
            # Update VM status
            vm.status = "terminated"
            vm.updated_at = datetime.utcnow()
            db.commit()
            
            return True
            
        except Exception as e:
            print(f"Error terminating VM {vm_id}: {e}")
            return False
    
    async def start_vm(
        self,
        vm_id: uuid.UUID,
        db: Session
    ) -> bool:
        """Start a virtual machine"""
        try:
            vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
            if not vm:
                return False
            
            host = db.query(BaremetalHost).filter(BaremetalHost.id == vm.host_id).first()
            if not host:
                return False
            
            # Connect to hypervisor
            conn = await self._get_libvirt_connection(host.ip_address)
            if not conn:
                return False
            
            # Find and start domain
            domain = conn.lookupByName(vm.name)
            if domain and not domain.isActive():
                domain.create()
                
                # Update VM status
                vm.status = "running"
                vm.updated_at = datetime.utcnow()
                db.commit()
                
                return True
            
            return False
            
        except Exception as e:
            print(f"Error starting VM {vm_id}: {e}")
            return False
    
    async def stop_vm(
        self,
        vm_id: uuid.UUID,
        db: Session
    ) -> bool:
        """Stop a virtual machine"""
        try:
            vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
            if not vm:
                return False
            
            host = db.query(BaremetalHost).filter(BaremetalHost.id == vm.host_id).first()
            if not host:
                return False
            
            # Connect to hypervisor
            conn = await self._get_libvirt_connection(host.ip_address)
            if not conn:
                return False
            
            # Find and stop domain
            domain = conn.lookupByName(vm.name)
            if domain and domain.isActive():
                domain.shutdown()
                
                # Update VM status
                vm.status = "stopped"
                vm.updated_at = datetime.utcnow()
                db.commit()
                
                return True
            
            return False
            
        except Exception as e:
            print(f"Error stopping VM {vm_id}: {e}")
            return False
    
    async def get_vm_status(
        self,
        vm_id: uuid.UUID,
        db: Session
    ) -> Optional[str]:
        """Get current VM status from hypervisor"""
        try:
            vm = db.query(VirtualMachine).filter(VirtualMachine.id == vm_id).first()
            if not vm:
                return None
            
            host = db.query(BaremetalHost).filter(BaremetalHost.id == vm.host_id).first()
            if not host:
                return None
            
            # Connect to hypervisor
            conn = await self._get_libvirt_connection(host.ip_address)
            if not conn:
                return None
            
            # Get domain status
            domain = conn.lookupByName(vm.name)
            if domain:
                state, reason = domain.state()
                if state == libvirt.VIR_DOMAIN_RUNNING:
                    return "running"
                elif state == libvirt.VIR_DOMAIN_SHUTOFF:
                    return "stopped"
                elif state == libvirt.VIR_DOMAIN_PAUSED:
                    return "paused"
                else:
                    return "unknown"
            
            return None
            
        except Exception as e:
            print(f"Error getting VM status {vm_id}: {e}")
            return None
    
    async def _get_libvirt_connection(self, host_ip: str) -> Optional[libvirt.virConnect]:
        """Get libvirt connection to host"""
        try:
            if host_ip in self.libvirt_connections:
                return self.libvirt_connections[host_ip]
            
            # Connect to remote hypervisor
            uri = f"qemu+ssh://root@{host_ip}/system"
            conn = libvirt.open(uri)
            
            if conn:
                self.libvirt_connections[host_ip] = conn
                return conn
            
            return None
            
        except Exception as e:
            print(f"Error connecting to hypervisor {host_ip}: {e}")
            return None
    
    def _generate_domain_xml(self, vm: VirtualMachine, config: Dict[str, Any]) -> str:
        """Generate libvirt domain XML"""
        xml_template = f"""
        <domain type='kvm'>
            <name>{vm.name}</name>
            <memory unit='GB'>{vm.memory_gb}</memory>
            <vcpu placement='static'>{vm.cpu_cores}</vcpu>
            <os>
                <type arch='x86_64' machine='pc'>hvm</type>
                <boot dev='hd'/>
            </os>
            <features>
                <acpi/>
                <apic/>
                <pae/>
            </features>
            <cpu mode='host-passthrough'/>
            <clock offset='utc'/>
            <on_poweroff>destroy</on_poweroff>
            <on_reboot>restart</on_reboot>
            <on_crash>destroy</on_crash>
            <devices>
                <emulator>/usr/bin/kvm</emulator>
                <disk type='file' device='disk'>
                    <driver name='qemu' type='qcow2'/>
                    <source file='/var/lib/libvirt/images/{vm.name}.qcow2'/>
                    <target dev='vda' bus='virtio'/>
                </disk>
                <interface type='network'>
                    <source network='default'/>
                    <model type='virtio'/>
                </interface>
                <console type='pty'>
                    <target type='serial' port='0'/>
                </console>
                <graphics type='vnc' port='-1' autoport='yes'/>
            </devices>
        </domain>
        """
        return xml_template.strip()
    
    async def _get_vm_ip_address(self, domain) -> Optional[str]:
        """Get VM IP address from domain"""
        try:
            # Wait for VM to boot and get IP
            await asyncio.sleep(30)  # Wait for boot
            
            # Get network interfaces
            ifaces = domain.interfaceAddresses(libvirt.VIR_DOMAIN_INTERFACE_ADDRESSES_SRC_LEASE)
            
            for iface_name, iface_data in ifaces.items():
                if iface_data['addrs']:
                    for addr in iface_data['addrs']:
                        if addr['type'] == libvirt.VIR_IP_ADDR_TYPE_IPV4:
                            return addr['addr']
            
            return None
            
        except Exception as e:
            print(f"Error getting VM IP address: {e}")
            return None
    
    async def cleanup(self):
        """Cleanup connections"""
        for conn in self.libvirt_connections.values():
            try:
                conn.close()
            except:
                pass
        self.libvirt_connections.clear()