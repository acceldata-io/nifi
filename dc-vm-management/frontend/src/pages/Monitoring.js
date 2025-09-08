import React, { useState, useEffect } from 'react';
import { Card, Row, Col, Select, DatePicker, Button, Table, Tag } from 'antd';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { ReloadOutlined } from '@ant-design/icons';
import axios from 'axios';
import moment from 'moment';

const { RangePicker } = DatePicker;

const Monitoring = () => {
  const [vmMetrics, setVmMetrics] = useState([]);
  const [hostMetrics, setHostMetrics] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [selectedVM, setSelectedVM] = useState(null);
  const [selectedHost, setSelectedHost] = useState(null);
  const [dateRange, setDateRange] = useState([
    moment().subtract(24, 'hours'),
    moment()
  ]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchAlerts();
  }, []);

  const fetchAlerts = async () => {
    try {
      const response = await axios.get('/api/v1/monitoring/alerts');
      setAlerts(response.data);
    } catch (error) {
      console.error('Failed to fetch alerts:', error);
    }
  };

  const fetchVMMetrics = async () => {
    if (!selectedVM) return;
    
    setLoading(true);
    try {
      const response = await axios.get(`/api/v1/monitoring/vm-metrics/${selectedVM}`, {
        params: {
          hours: 24
        }
      });
      setVmMetrics(response.data);
    } catch (error) {
      console.error('Failed to fetch VM metrics:', error);
    } finally {
      setLoading(false);
    }
  };

  const fetchHostMetrics = async () => {
    if (!selectedHost) return;
    
    setLoading(true);
    try {
      const response = await axios.get(`/api/v1/monitoring/host-metrics/${selectedHost}`, {
        params: {
          hours: 24
        }
      });
      setHostMetrics(response.data);
    } catch (error) {
      console.error('Failed to fetch host metrics:', error);
    } finally {
      setLoading(false);
    }
  };

  const getAlertColor = (type) => {
    const colors = {
      warning: 'orange',
      error: 'red',
      info: 'blue',
    };
    return colors[type] || 'default';
  };

  const alertColumns = [
    {
      title: 'Type',
      dataIndex: 'type',
      key: 'type',
      render: (type) => <Tag color={getAlertColor(type)}>{type}</Tag>,
    },
    {
      title: 'Message',
      dataIndex: 'message',
      key: 'message',
    },
    {
      title: 'Timestamp',
      dataIndex: 'timestamp',
      key: 'timestamp',
      render: (timestamp) => moment(timestamp).format('YYYY-MM-DD HH:mm:ss'),
    },
  ];

  return (
    <div>
      <h1>Monitoring</h1>
      
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={24}>
          <Card title="System Alerts">
            <Table
              columns={alertColumns}
              dataSource={alerts}
              rowKey="timestamp"
              pagination={false}
              size="small"
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={12}>
          <Card title="VM Metrics">
            <div style={{ marginBottom: 16 }}>
              <Select
                placeholder="Select VM"
                style={{ width: 200, marginRight: 8 }}
                onChange={setSelectedVM}
                value={selectedVM}
              >
                {/* VM options would be populated from API */}
              </Select>
              <Button 
                type="primary" 
                icon={<ReloadOutlined />}
                onClick={fetchVMMetrics}
                loading={loading}
              >
                Load Metrics
              </Button>
            </div>
            <ResponsiveContainer width="100%" height={300}>
              <LineChart data={vmMetrics}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="timestamp" />
                <YAxis />
                <Tooltip />
                <Line type="monotone" dataKey="cpu_usage_percent" stroke="#8884d8" />
                <Line type="monotone" dataKey="memory_usage_percent" stroke="#82ca9d" />
              </LineChart>
            </ResponsiveContainer>
          </Card>
        </Col>
        <Col span={12}>
          <Card title="Host Metrics">
            <div style={{ marginBottom: 16 }}>
              <Select
                placeholder="Select Host"
                style={{ width: 200, marginRight: 8 }}
                onChange={setSelectedHost}
                value={selectedHost}
              >
                {/* Host options would be populated from API */}
              </Select>
              <Button 
                type="primary" 
                icon={<ReloadOutlined />}
                onClick={fetchHostMetrics}
                loading={loading}
              >
                Load Metrics
              </Button>
            </div>
            <ResponsiveContainer width="100%" height={300}>
              <LineChart data={hostMetrics}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="timestamp" />
                <YAxis />
                <Tooltip />
                <Line type="monotone" dataKey="cpu_usage_percent" stroke="#8884d8" />
                <Line type="monotone" dataKey="memory_usage_percent" stroke="#82ca9d" />
                <Line type="monotone" dataKey="temperature_celsius" stroke="#ffc658" />
              </LineChart>
            </ResponsiveContainer>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Monitoring;