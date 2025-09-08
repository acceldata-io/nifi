import React, { useState, useEffect } from 'react';
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Popconfirm,
  message,
  Card,
  Row,
  Col,
  Statistic,
} from 'antd';
import { PlusOutlined, ReloadOutlined, DeleteOutlined } from '@ant-design/icons';
import axios from 'axios';

const ResourceManagement = () => {
  const [hosts, setHosts] = useState([]);
  const [stats, setStats] = useState({});
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    setLoading(true);
    try {
      const [hostsRes, statsRes] = await Promise.all([
        axios.get('/api/v1/resources/hosts'),
        axios.get('/api/v1/resources/stats')
      ]);
      setHosts(hostsRes.data);
      setStats(statsRes.data);
    } catch (error) {
      message.error('Failed to fetch resource data');
    } finally {
      setLoading(false);
    }
  };

  const handleAddHost = async (values) => {
    try {
      await axios.post('/api/v1/resources/hosts', values);
      message.success('Host added successfully');
      setModalVisible(false);
      form.resetFields();
      fetchData();
    } catch (error) {
      message.error('Failed to add host');
    }
  };

  const handleRemoveHost = async (hostId) => {
    try {
      await axios.delete(`/api/v1/resources/hosts/${hostId}`);
      message.success('Host removed successfully');
      fetchData();
    } catch (error) {
      message.error('Failed to remove host');
    }
  };

  const getStatusColor = (status) => {
    const colors = {
      active: 'green',
      maintenance: 'orange',
      offline: 'red',
    };
    return colors[status] || 'default';
  };

  const columns = [
    {
      title: 'Hostname',
      dataIndex: 'hostname',
      key: 'hostname',
    },
    {
      title: 'IP Address',
      dataIndex: 'ip_address',
      key: 'ip_address',
    },
    {
      title: 'Resources',
      key: 'resources',
      render: (_, record) => (
        <div>
          <div>CPU: {record.cpu_cores} cores</div>
          <div>RAM: {record.memory_gb} GB</div>
          <div>Storage: {record.storage_gb} GB</div>
        </div>
      ),
    },
    {
      title: 'Hypervisor',
      dataIndex: 'hypervisor_type',
      key: 'hypervisor_type',
    },
    {
      title: 'Status',
      dataIndex: 'status',
      key: 'status',
      render: (status) => <Tag color={getStatusColor(status)}>{status}</Tag>,
    },
    {
      title: 'Created',
      dataIndex: 'created_at',
      key: 'created_at',
      render: (date) => new Date(date).toLocaleDateString(),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_, record) => (
        <Popconfirm
          title="Are you sure you want to remove this host?"
          onConfirm={() => handleRemoveHost(record.id)}
          okText="Yes"
          cancelText="No"
        >
          <Button danger size="small" icon={<DeleteOutlined />}>
            Remove
          </Button>
        </Popconfirm>
      ),
    },
  ];

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title="Total Hosts" value={stats.total_hosts || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Active Hosts" value={stats.active_hosts || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Total CPU Cores" value={stats.total_cpu_cores || 0} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Total Memory (GB)" value={stats.total_memory_gb || 0} />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={8}>
          <Card>
            <Statistic title="Used CPU Cores" value={stats.used_cpu_cores || 0} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="Used Memory (GB)" value={stats.used_memory_gb || 0} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic 
              title="Utilization" 
              value={stats.utilization_percentage || 0} 
              suffix="%" 
            />
          </Card>
        </Col>
      </Row>

      <Card
        title="Baremetal Hosts"
        extra={
          <Space>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setModalVisible(true)}
            >
              Add Host
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchData}
            >
              Refresh
            </Button>
          </Space>
        }
      >
        <Table
          columns={columns}
          dataSource={hosts}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 10 }}
        />
      </Card>

      <Modal
        title="Add Baremetal Host"
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleAddHost}
        >
          <Form.Item
            name="hostname"
            label="Hostname"
            rules={[{ required: true, message: 'Please enter hostname' }]}
          >
            <Input placeholder="Enter hostname" />
          </Form.Item>

          <Form.Item
            name="ip_address"
            label="IP Address"
            rules={[
              { required: true, message: 'Please enter IP address' },
              { pattern: /^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/, message: 'Invalid IP address' }
            ]}
          >
            <Input placeholder="Enter IP address" />
          </Form.Item>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="cpu_cores"
                label="CPU Cores"
                rules={[{ required: true, message: 'Please enter CPU cores' }]}
              >
                <InputNumber min={1} max={128} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="memory_gb"
                label="Memory (GB)"
                rules={[{ required: true, message: 'Please enter memory' }]}
              >
                <InputNumber min={1} max={1024} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="storage_gb"
                label="Storage (GB)"
                rules={[{ required: true, message: 'Please enter storage' }]}
              >
                <InputNumber min={1} max={10000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item
            name="hypervisor_type"
            label="Hypervisor Type"
            rules={[{ required: true, message: 'Please select hypervisor type' }]}
          >
            <Select placeholder="Select hypervisor type">
              <Select.Option value="kvm">KVM</Select.Option>
              <Select.Option value="xen">Xen</Select.Option>
              <Select.Option value="vmware">VMware</Select.Option>
            </Select>
          </Form.Item>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                Add Host
              </Button>
              <Button onClick={() => setModalVisible(false)}>
                Cancel
              </Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ResourceManagement;