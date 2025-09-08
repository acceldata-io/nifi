"""
Data Center VM Management System - Main API Server
"""

from fastapi import FastAPI, HTTPException, Depends, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from contextlib import asynccontextmanager
import uvicorn
import os
from dotenv import load_dotenv

from api.routes import vms, resources, auth, monitoring
from models.database import init_db
from services.resource_manager import ResourceManager
from services.vm_manager import VMManager

load_dotenv()

security = HTTPBearer()

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan events"""
    # Startup
    await init_db()
    app.state.resource_manager = ResourceManager()
    app.state.vm_manager = VMManager(app.state.resource_manager)
    yield
    # Shutdown
    await app.state.resource_manager.cleanup()
    await app.state.vm_manager.cleanup()

app = FastAPI(
    title="DC VM Management API",
    description="API for managing virtual machines across baremetal infrastructure",
    version="1.0.0",
    lifespan=lifespan
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3000", "http://frontend:3000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Include routers
app.include_router(auth.router, prefix="/api/v1/auth", tags=["Authentication"])
app.include_router(vms.router, prefix="/api/v1/vms", tags=["Virtual Machines"])
app.include_router(resources.router, prefix="/api/v1/resources", tags=["Resources"])
app.include_router(monitoring.router, prefix="/api/v1/monitoring", tags=["Monitoring"])

@app.get("/")
async def root():
    """Root endpoint"""
    return {
        "message": "DC VM Management API",
        "version": "1.0.0",
        "docs": "/docs",
        "health": "/health"
    }

@app.get("/health")
async def health_check():
    """Health check endpoint"""
    return {
        "status": "healthy",
        "timestamp": "2024-01-01T00:00:00Z"
    }

if __name__ == "__main__":
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True if os.getenv("ENVIRONMENT") == "development" else False
    )