import React, { useState, useEffect } from 'react';
import { Card, Row, Col, Statistic, Table, Tag, Progress } from 'antd';
import { 
  CloudServerOutlined, 
  DatabaseOutlined, 
  MonitorOutlined,
  ThunderboltOutlined 
} from '@ant-design/icons';
import axios from 'axios';

const Dashboard = () => {
  const [stats, setStats] = useState({});
  const [recentVMs, setRecentVMs] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchDashboardData();
  }, []);

  const fetchDashboardData = async () => {
    try {
      const [statsRes, vmsRes] = await Promise.all([
        axios.get('/api/v1/monitoring/system-metrics'),
        axios.get('/api/v1/vms?limit=5')
      ]);
      
      setStats(statsRes.data);
      setRecentVMs(vmsRes.data);
    } catch (error) {
      console.error('Failed to fetch dashboard data:', error);
    } finally {
      setLoading(false);
    }
  };

  const vmColumns = [
    {
      title: 'Name',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: 'OS',
      key: 'os',
      render: (_, record) => `${record.os_type} ${record.os_version}`,
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status) => {
        const colors = {
          running: 'green',
          stopped: 'orange',
          provisioning: 'blue',
          terminating: 'red',
        };
        return <Tag color={colors[status]}>{status}</Tag>;
      },
    },
    {
      title: 'Created',
      dataIndex: 'created_at',
      key: 'created_at',
      render: (date) => new Date(date).toLocaleDateString(),
    },
  ];

  return (
    <div>
      <h1>Dashboard</h1>
      
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="Total VMs"
              value={stats.total_vms || 0}
              prefix={<CloudServerOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Running VMs"
              value={stats.running_vms || 0}
              prefix={<ThunderboltOutlined />}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Total Hosts"
              value={stats.total_hosts || 0}
              prefix={<DatabaseOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="Active Hosts"
              value={stats.active_hosts || 0}
              prefix={<MonitorOutlined />}
              valueStyle={{ color: '#1890ff' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={8}>
          <Card title="CPU Utilization">
            <Progress
              type="circle"
              percent={stats.overall_cpu_utilization || 0}
              format={(percent) => `${percent}%`}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="Memory Utilization">
            <Progress
              type="circle"
              percent={stats.overall_memory_utilization || 0}
              format={(percent) => `${percent}%`}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card title="Storage Utilization">
            <Progress
              type="circle"
              percent={stats.overall_storage_utilization || 0}
              format={(percent) => `${percent}%`}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={24}>
          <Card title="Recent Virtual Machines" loading={loading}>
            <Table
              columns={vmColumns}
              dataSource={recentVMs}
              rowKey="id"
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;