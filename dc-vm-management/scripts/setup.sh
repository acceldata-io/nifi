#!/bin/bash

# DC VM Management System Setup Script

set -e

echo "🚀 Setting up DC VM Management System..."

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if Docker Compose is installed
if ! command -v docker-compose &> /dev/null; then
    echo "❌ Docker Compose is not installed. Please install Docker Compose first."
    exit 1
fi

# Create .env file if it doesn't exist
if [ ! -f .env ]; then
    echo "📝 Creating .env file from template..."
    cp .env.example .env
    echo "⚠️  Please edit .env file with your configuration before continuing."
    echo "   Especially change the SECRET_KEY and database passwords!"
    read -p "Press Enter to continue after editing .env file..."
fi

# Create necessary directories
echo "📁 Creating necessary directories..."
mkdir -p data/postgres
mkdir -p data/grafana
mkdir -p data/prometheus
mkdir -p logs

# Set permissions
echo "🔐 Setting permissions..."
chmod +x automation/scripts/*.py
chmod +x automation/ansible/*.yml

# Build and start services
echo "🔨 Building and starting services..."
docker-compose build

echo "🚀 Starting services..."
docker-compose up -d

# Wait for services to be ready
echo "⏳ Waiting for services to be ready..."
sleep 30

# Check if services are running
echo "🔍 Checking service status..."
docker-compose ps

# Run database migrations
echo "🗄️  Running database migrations..."
docker-compose exec backend python -c "from models.database import init_db; import asyncio; asyncio.run(init_db())"

echo "✅ Setup complete!"
echo ""
echo "🌐 Access the application:"
echo "   Web GUI: http://localhost:3000"
echo "   API Docs: http://localhost:8000/docs"
echo "   Grafana: http://localhost:3001 (admin/admin)"
echo "   Prometheus: http://localhost:9090"
echo ""
echo "👤 Default admin credentials:"
echo "   Username: admin"
echo "   Password: admin123"
echo ""
echo "📚 Next steps:"
echo "   1. Add your baremetal hosts to the resource pool"
echo "   2. Configure SSH access to your hypervisor hosts"
echo "   3. Start creating VMs!"
echo ""
echo "🔧 To stop the system: docker-compose down"
echo "📊 To view logs: docker-compose logs -f"