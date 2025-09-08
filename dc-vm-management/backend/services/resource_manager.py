"""
Resource management service for baremetal infrastructure
"""

import asyncio
import subprocess
import os
from typing import List, Dict, Any, Optional
from datetime import datetime
import uuid
import psutil
import requests
from sqlalchemy.orm import Session

from models.database import BaremetalHost, ResourcePool

class ResourceManager:
    """Manages baremetal resources and consolidation"""
    
    def __init__(self):
        self.monitoring_interval = 60  # seconds
        self.monitoring_task = None
    
    async def start_monitoring(self):
        """Start resource monitoring"""
        if not self.monitoring_task:
            self.monitoring_task = asyncio.create_task(self._monitor_resources())
    
    async def stop_monitoring(self):
        """Stop resource monitoring"""
        if self.monitoring_task:
            self.monitoring_task.cancel()
            try:
                await self.monitoring_task
            except asyncio.CancelledError:
                pass
            self.monitoring_task = None
    
    async def discover_hosts(self, network_range: str) -> List[Dict[str, Any]]:
        """Discover available hosts in network range"""
        discovered_hosts = []
        
        try:
            # Use nmap to discover hosts
            result = subprocess.run(
                ['nmap', '-sn', network_range],
                capture_output=True,
                text=True,
                timeout=30
            )
            
            if result.returncode == 0:
                lines = result.stdout.split('\n')
                for line in lines:
                    if 'Nmap scan report for' in line:
                        ip = line.split()[-1].strip('()')
                        hostname = line.split()[4] if len(line.split()) > 4 else ip
                        
                        # Check if host has hypervisor capabilities
                        if await self._check_hypervisor_capabilities(ip):
                            host_info = await self._get_host_info(ip, hostname)
                            if host_info:
                                discovered_hosts.append(host_info)
            
        except Exception as e:
            print(f"Error discovering hosts: {e}")
        
        return discovered_hosts
    
    async def add_host_to_pool(
        self,
        host_info: Dict[str, Any],
        db: Session
    ) -> Optional[uuid.UUID]:
        """Add a discovered host to the resource pool"""
        try:
            # Check if host already exists
            existing_host = db.query(BaremetalHost).filter(
                BaremetalHost.hostname == host_info['hostname']
            ).first()
            
            if existing_host:
                return existing_host.id
            
            # Create new host
            host = BaremetalHost(
                hostname=host_info['hostname'],
                ip_address=host_info['ip_address'],
                cpu_cores=host_info['cpu_cores'],
                memory_gb=host_info['memory_gb'],
                storage_gb=host_info['storage_gb'],
                hypervisor_type=host_info.get('hypervisor_type', 'kvm'),
                status='active'
            )
            
            db.add(host)
            db.commit()
            db.refresh(host)
            
            return host.id
            
        except Exception as e:
            print(f"Error adding host to pool: {e}")
            return None
    
    async def get_host_resources(self, host_id: uuid.UUID, db: Session) -> Optional[Dict[str, Any]]:
        """Get current resource utilization for a host"""
        try:
            host = db.query(BaremetalHost).filter(BaremetalHost.id == host_id).first()
            if not host:
                return None
            
            # Get real-time resource usage
            usage = await self._get_host_resource_usage(host.ip_address)
            if not usage:
                return None
            
            return {
                'host_id': host_id,
                'hostname': host.hostname,
                'cpu_cores_total': host.cpu_cores,
                'memory_gb_total': host.memory_gb,
                'storage_gb_total': host.storage_gb,
                'cpu_cores_used': usage['cpu_cores_used'],
                'memory_gb_used': usage['memory_gb_used'],
                'storage_gb_used': usage['storage_gb_used'],
                'cpu_usage_percent': usage['cpu_usage_percent'],
                'memory_usage_percent': usage['memory_usage_percent'],
                'storage_usage_percent': usage['storage_usage_percent'],
                'timestamp': datetime.utcnow()
            }
            
        except Exception as e:
            print(f"Error getting host resources: {e}")
            return None
    
    async def consolidate_resources(self, db: Session) -> Dict[str, Any]:
        """Consolidate and optimize resource allocation"""
        try:
            # Get all active hosts
            hosts = db.query(BaremetalHost).filter(BaremetalHost.status == 'active').all()
            
            consolidation_report = {
                'total_hosts': len(hosts),
                'optimization_opportunities': [],
                'recommendations': []
            }
            
            for host in hosts:
                usage = await self._get_host_resource_usage(host.ip_address)
                if usage:
                    # Check for underutilized hosts
                    if usage['cpu_usage_percent'] < 30 and usage['memory_usage_percent'] < 30:
                        consolidation_report['optimization_opportunities'].append({
                            'host_id': host.id,
                            'hostname': host.hostname,
                            'issue': 'underutilized',
                            'cpu_usage': usage['cpu_usage_percent'],
                            'memory_usage': usage['memory_usage_percent']
                        })
                    
                    # Check for overutilized hosts
                    elif usage['cpu_usage_percent'] > 90 or usage['memory_usage_percent'] > 90:
                        consolidation_report['optimization_opportunities'].append({
                            'host_id': host.id,
                            'hostname': host.hostname,
                            'issue': 'overutilized',
                            'cpu_usage': usage['cpu_usage_percent'],
                            'memory_usage': usage['memory_usage_percent']
                        })
            
            # Generate recommendations
            if consolidation_report['optimization_opportunities']:
                consolidation_report['recommendations'].append(
                    "Consider migrating VMs from overutilized hosts to underutilized ones"
                )
                consolidation_report['recommendations'].append(
                    "Implement load balancing across the host pool"
                )
            
            return consolidation_report
            
        except Exception as e:
            print(f"Error consolidating resources: {e}")
            return {}
    
    async def _monitor_resources(self):
        """Background task to monitor resource usage"""
        while True:
            try:
                # This would update resource usage in the database
                # and trigger alerts if thresholds are exceeded
                await asyncio.sleep(self.monitoring_interval)
            except asyncio.CancelledError:
                break
            except Exception as e:
                print(f"Error in resource monitoring: {e}")
                await asyncio.sleep(self.monitoring_interval)
    
    async def _check_hypervisor_capabilities(self, ip: str) -> bool:
        """Check if host has hypervisor capabilities"""
        try:
            # Check for common hypervisor services
            services = ['libvirt', 'qemu', 'kvm']
            
            for service in services:
                result = subprocess.run(
                    ['ssh', f'root@{ip}', f'systemctl is-active {service}'],
                    capture_output=True,
                    text=True,
                    timeout=10
                )
                if result.returncode == 0 and 'active' in result.stdout:
                    return True
            
            return False
            
        except Exception:
            return False
    
    async def _get_host_info(self, ip: str, hostname: str) -> Optional[Dict[str, Any]]:
        """Get detailed host information"""
        try:
            # Get system information via SSH
            commands = {
                'cpu_cores': "nproc",
                'memory_gb': "free -g | awk '/^Mem:/{print $2}'",
                'storage_gb': "df -BG / | awk 'NR==2{print $2}' | sed 's/G//'",
                'hypervisor_type': "systemctl is-active kvm && echo 'kvm' || echo 'unknown'"
            }
            
            host_info = {
                'hostname': hostname,
                'ip_address': ip,
                'cpu_cores': 0,
                'memory_gb': 0,
                'storage_gb': 0,
                'hypervisor_type': 'unknown'
            }
            
            for key, command in commands.items():
                try:
                    result = subprocess.run(
                        ['ssh', f'root@{ip}', command],
                        capture_output=True,
                        text=True,
                        timeout=10
                    )
                    
                    if result.returncode == 0:
                        value = result.stdout.strip()
                        if key in ['cpu_cores', 'memory_gb', 'storage_gb']:
                            host_info[key] = int(value) if value.isdigit() else 0
                        else:
                            host_info[key] = value
                
                except Exception:
                    continue
            
            # Only return if we got valid information
            if host_info['cpu_cores'] > 0 and host_info['memory_gb'] > 0:
                return host_info
            
            return None
            
        except Exception as e:
            print(f"Error getting host info for {ip}: {e}")
            return None
    
    async def _get_host_resource_usage(self, ip: str) -> Optional[Dict[str, Any]]:
        """Get current resource usage for a host"""
        try:
            # Get resource usage via SSH
            commands = {
                'cpu_usage': "top -bn1 | grep 'Cpu(s)' | awk '{print $2}' | sed 's/%us,//'",
                'memory_usage': "free | awk '/Mem:/ {printf \"%.1f\", $3/$2 * 100.0}'",
                'storage_usage': "df / | awk 'NR==2{print $5}' | sed 's/%//'"
            }
            
            usage = {}
            
            for key, command in commands.items():
                try:
                    result = subprocess.run(
                        ['ssh', f'root@{ip}', command],
                        capture_output=True,
                        text=True,
                        timeout=10
                    )
                    
                    if result.returncode == 0:
                        value = result.stdout.strip()
                        usage[key] = float(value) if value.replace('.', '').isdigit() else 0.0
                
                except Exception:
                    usage[key] = 0.0
            
            # Calculate used resources (simplified)
            usage['cpu_cores_used'] = int(usage.get('cpu_usage', 0) / 100 * 8)  # Assume 8 cores
            usage['memory_gb_used'] = int(usage.get('memory_usage', 0) / 100 * 16)  # Assume 16GB
            usage['storage_gb_used'] = int(usage.get('storage_usage', 0) / 100 * 100)  # Assume 100GB
            
            usage['cpu_usage_percent'] = usage.get('cpu_usage', 0)
            usage['memory_usage_percent'] = usage.get('memory_usage', 0)
            usage['storage_usage_percent'] = usage.get('storage_usage', 0)
            
            return usage
            
        except Exception as e:
            print(f"Error getting resource usage for {ip}: {e}")
            return None
    
    async def cleanup(self):
        """Cleanup resources"""
        await self.stop_monitoring()