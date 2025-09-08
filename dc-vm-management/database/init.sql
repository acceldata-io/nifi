-- Initialize database with default data

-- Create default admin user
INSERT INTO users (username, email, hashed_password, is_active, is_admin)
VALUES (
    'admin',
    'admin@dc-vm-management.local',
    '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewdBPj4J/8K8K8K8', -- password: admin123
    true,
    true
) ON CONFLICT (username) DO NOTHING;

-- Create default VM templates
INSERT INTO vm_templates (name, os_type, os_version, cpu_cores, memory_gb, storage_gb, image_url)
VALUES 
    ('Ubuntu 20.04 Small', 'ubuntu', '20.04', 2, 4, 20, 'https://releases.ubuntu.com/20.04/ubuntu-20.04.6-server-amd64.iso'),
    ('Ubuntu 22.04 Small', 'ubuntu', '22.04', 2, 4, 20, 'https://releases.ubuntu.com/22.04/ubuntu-22.04.3-server-amd64.iso'),
    ('CentOS 7 Small', 'centos', '7', 2, 4, 20, 'https://mirror.centos.org/centos/7/isos/x86_64/CentOS-7-x86_64-Minimal-2009.iso'),
    ('CentOS 8 Small', 'centos', '8', 2, 4, 20, 'https://mirror.centos.org/centos/8/isos/x86_64/CentOS-8.5.2111-x86_64-minimal.iso'),
    ('Ubuntu 20.04 Medium', 'ubuntu', '20.04', 4, 8, 50, 'https://releases.ubuntu.com/20.04/ubuntu-20.04.6-server-amd64.iso'),
    ('Ubuntu 22.04 Medium', 'ubuntu', '22.04', 4, 8, 50, 'https://releases.ubuntu.com/22.04/ubuntu-22.04.3-server-amd64.iso'),
    ('CentOS 7 Medium', 'centos', '7', 4, 8, 50, 'https://mirror.centos.org/centos/7/isos/x86_64/CentOS-7-x86_64-Minimal-2009.iso'),
    ('CentOS 8 Medium', 'centos', '8', 4, 8, 50, 'https://mirror.centos.org/centos/8/isos/x86_64/CentOS-8.5.2111-x86_64-minimal.iso'),
    ('Ubuntu 20.04 Large', 'ubuntu', '20.04', 8, 16, 100, 'https://releases.ubuntu.com/20.04/ubuntu-20.04.6-server-amd64.iso'),
    ('Ubuntu 22.04 Large', 'ubuntu', '22.04', 8, 16, 100, 'https://releases.ubuntu.com/22.04/ubuntu-22.04.3-server-amd64.iso')
ON CONFLICT (name) DO NOTHING;

-- Create default resource pool
INSERT INTO resource_pools (name, description, cpu_cores_total, memory_gb_total, storage_gb_total)
VALUES (
    'Default Pool',
    'Default resource pool for all baremetal hosts',
    0,
    0,
    0
) ON CONFLICT (name) DO NOTHING;