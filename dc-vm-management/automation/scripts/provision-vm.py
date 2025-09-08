#!/usr/bin/env python3
"""
VM Provisioning Script
"""

import argparse
import asyncio
import subprocess
import os
import sys
from typing import Dict, Any

class VMProvisioner:
    """Handles VM provisioning automation"""
    
    def __init__(self):
        self.os_images = {
            'ubuntu': {
                '20.04': 'https://releases.ubuntu.com/20.04/ubuntu-20.04.6-server-amd64.iso',
                '22.04': 'https://releases.ubuntu.com/22.04/ubuntu-22.04.3-server-amd64.iso'
            },
            'centos': {
                '7': 'https://mirror.centos.org/centos/7/isos/x86_64/CentOS-7-x86_64-Minimal-2009.iso',
                '8': 'https://mirror.centos.org/centos/8/isos/x86_64/CentOS-8.5.2111-x86_64-minimal.iso'
            },
            'rhel': {
                '8': 'https://access.redhat.com/downloads/content/479/ver=/rhel---8/8.5/x86_64/product-software'
            }
        }
    
    async def provision_vm(
        self,
        vm_name: str,
        target_host: str,
        os_type: str,
        os_version: str,
        cpu_cores: int,
        memory_gb: int,
        storage_gb: int
    ) -> bool:
        """Provision a new VM"""
        try:
            print(f"Provisioning VM: {vm_name}")
            print(f"Target Host: {target_host}")
            print(f"OS: {os_type} {os_version}")
            print(f"Resources: {cpu_cores} CPU, {memory_gb}GB RAM, {storage_gb}GB Storage")
            
            # Run Ansible playbook
            cmd = [
                'ansible-playbook',
                'automation/ansible/vm-provisioning.yml',
                '-i', f'{target_host},',
                '-e', f'vm_name={vm_name}',
                '-e', f'vm_cpu={cpu_cores}',
                '-e', f'vm_memory={memory_gb}',
                '-e', f'vm_disk={storage_gb}',
                '-e', f'vm_os={os_type}',
                '-e', f'vm_os_version={os_version}'
            ]
            
            result = subprocess.run(cmd, capture_output=True, text=True)
            
            if result.returncode == 0:
                print(f"VM {vm_name} provisioned successfully!")
                return True
            else:
                print(f"Error provisioning VM: {result.stderr}")
                return False
                
        except Exception as e:
            print(f"Exception during VM provisioning: {e}")
            return False
    
    async def terminate_vm(self, vm_name: str, target_host: str) -> bool:
        """Terminate a VM"""
        try:
            print(f"Terminating VM: {vm_name}")
            
            cmd = [
                'ansible',
                target_host,
                '-m', 'command',
                '-a', f'virsh destroy {vm_name}'
            ]
            
            result = subprocess.run(cmd, capture_output=True, text=True)
            
            if result.returncode == 0:
                # Undefine VM
                cmd = [
                    'ansible',
                    target_host,
                    '-m', 'command',
                    '-a', f'virsh undefine {vm_name}'
                ]
                
                subprocess.run(cmd, capture_output=True, text=True)
                
                # Remove disk image
                cmd = [
                    'ansible',
                    target_host,
                    '-m', 'file',
                    '-a', f'path=/var/lib/libvirt/images/{vm_name}.qcow2 state=absent'
                ]
                
                subprocess.run(cmd, capture_output=True, text=True)
                
                print(f"VM {vm_name} terminated successfully!")
                return True
            else:
                print(f"Error terminating VM: {result.stderr}")
                return False
                
        except Exception as e:
            print(f"Exception during VM termination: {e}")
            return False

async def main():
    parser = argparse.ArgumentParser(description='VM Provisioning Script')
    parser.add_argument('action', choices=['provision', 'terminate'], help='Action to perform')
    parser.add_argument('--vm-name', required=True, help='VM name')
    parser.add_argument('--target-host', required=True, help='Target host IP')
    parser.add_argument('--os-type', help='OS type (ubuntu, centos, rhel)')
    parser.add_argument('--os-version', help='OS version')
    parser.add_argument('--cpu-cores', type=int, help='Number of CPU cores')
    parser.add_argument('--memory-gb', type=int, help='Memory in GB')
    parser.add_argument('--storage-gb', type=int, help='Storage in GB')
    
    args = parser.parse_args()
    
    provisioner = VMProvisioner()
    
    if args.action == 'provision':
        if not all([args.os_type, args.os_version, args.cpu_cores, args.memory_gb, args.storage_gb]):
            print("Error: All VM parameters required for provisioning")
            sys.exit(1)
        
        success = await provisioner.provision_vm(
            args.vm_name,
            args.target_host,
            args.os_type,
            args.os_version,
            args.cpu_cores,
            args.memory_gb,
            args.storage_gb
        )
    else:  # terminate
        success = await provisioner.terminate_vm(args.vm_name, args.target_host)
    
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    asyncio.run(main())