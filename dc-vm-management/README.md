# Data Center VM Management System

A comprehensive solution for automated VM provisioning, resource consolidation, and management across baremetal infrastructure.

## 🚀 Features

- **Automated VM Provisioning**: Deploy VMs with customizable OS, CPU, memory, and storage
- **Resource Consolidation**: Pool and manage baremetal resources efficiently  
- **Multi-OS Support**: Ubuntu, CentOS, RHEL, Windows Server, and more
- **Modern Web GUI**: React-based responsive interface for VM management
- **REST API**: Full programmatic access to all functionality
- **Real-time Monitoring**: Resource utilization tracking with Prometheus/Grafana
- **Background Processing**: Celery-based task queue for async operations
- **Multi-tenant Support**: User authentication and role-based access control

## 🏗️ Architecture

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Web Frontend  │    │   REST API      │    │   PostgreSQL    │
│   (React/Vue)   │◄──►│   (FastAPI)     │◄──►│   Database      │
└─────────────────┘    └─────────────────┘    └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │ Resource Manager│
                       │ (Libvirt/KVM)   │
                       └─────────────────┘
                                │
                                ▼
                       ┌─────────────────┐
                       │ Baremetal Pool  │
                       │ (Physical Hosts)│
                       └─────────────────┘
```

## 🚀 Quick Start

### Prerequisites
- Docker 20.10+
- Docker Compose 2.0+
- Linux host with KVM support

### Installation

1. **Clone the repository**
   ```bash
   git clone <your-repo-url>
   cd dc-vm-management
   ```

2. **Configure environment**
   ```bash
   cp .env.example .env
   # Edit .env with your configuration
   ```

3. **Run setup script**
   ```bash
   chmod +x scripts/setup.sh
   ./scripts/setup.sh
   ```

4. **Access the application**
   - Web GUI: http://localhost:3000
   - API Documentation: http://localhost:8000/docs
   - Grafana: http://localhost:3001 (admin/admin)

**Default credentials**: admin / admin123

## 📁 Project Structure

```
dc-vm-management/
├── backend/                 # FastAPI backend
│   ├── api/                # API routes
│   ├── models/             # Database models
│   ├── services/           # Business logic
│   └── main.py             # Application entry point
├── frontend/               # React frontend
│   ├── src/               # Source code
│   └── public/            # Static assets
├── automation/            # VM provisioning scripts
│   ├── ansible/           # Ansible playbooks
│   └── scripts/           # Python automation scripts
├── config/                # Configuration files
├── database/              # Database initialization
├── docs/                  # Documentation
└── scripts/               # Setup and utility scripts
```

## 🔧 Components

- **Backend API**: FastAPI-based REST API with JWT authentication
- **Frontend GUI**: Modern React interface with Ant Design components
- **Resource Manager**: Libvirt/KVM integration for VM lifecycle management
- **Database**: PostgreSQL with SQLAlchemy ORM
- **Automation**: Ansible playbooks for OS provisioning
- **Monitoring**: Prometheus metrics collection and Grafana visualization
- **Task Queue**: Celery with Redis for background processing

## 📚 Documentation

- [API Documentation](docs/API.md) - Complete API reference
- [Deployment Guide](docs/DEPLOYMENT.md) - Production deployment instructions
- [User Guide](docs/USER_GUIDE.md) - End-user documentation

## 🛠️ Development

### Backend Development
```bash
cd backend
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

### Frontend Development
```bash
cd frontend
npm install
npm start
```

## 🔒 Security

- JWT-based authentication
- Password hashing with bcrypt
- CORS protection
- Input validation and sanitization
- SQL injection prevention

## 📊 Monitoring

- Real-time resource utilization tracking
- System alerts and notifications
- Performance metrics collection
- Grafana dashboards for visualization

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details

## 🆘 Support

- Check the [documentation](docs/) for detailed guides
- Review [troubleshooting](docs/DEPLOYMENT.md#troubleshooting) section
- Open an issue for bug reports or feature requests