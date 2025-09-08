import React, { useState, useEffect } from 'react';
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  Select,
  InputNumber,
  Space,
  Tag,
  Popconfirm,
  message,
  Card,
  Row,
  Col,
  Statistic,
} from 'antd';
import {
  PlusOutlined,
  PlayCircleOutlined,
  PauseCircleOutlined,
  DeleteOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import axios from 'axios';

const VMManagement = () => {
  const [vms, setVms] = useState([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();

  const osOptions = [
    { value: 'ubuntu', label: 'Ubuntu' },
    { value: 'centos', label: 'CentOS' },
    { value: 'rhel', label: 'Red Hat Enterprise Linux' },
    { value: 'windows', label: 'Windows Server' },
  ];

  const osVersionOptions = {
    ubuntu: [
      { value: '20.04', label: '20.04 LTS' },
      { value: '22.04', label: '22.04 LTS' },
    ],
    centos: [
      { value: '7', label: 'CentOS 7' },
      { value: '8', label: 'CentOS 8' },
    ],
    rhel: [
      { value: '8', label: 'RHEL 8' },
      { value: '9', label: 'RHEL 9' },
    ],
    windows: [
      { value: '2019', label: 'Windows Server 2019' },
      { value: '2022', label: 'Windows Server 2022' },
    ],
  };

  useEffect(() => {
    fetchVMs();
  }, []);

  const fetchVMs = async () => {
    setLoading(true);
    try {
      const response = await axios.get('/api/v1/vms');
      setVms(response.data);
    } catch (error) {
      message.error('Failed to fetch VMs');
    } finally {
      setLoading(false);
    }
  };

  const handleCreateVM = async (values) => {
    try {
      await axios.post('/api/v1/vms', values);
      message.success('VM creation initiated');
      setModalVisible(false);
      form.resetFields();
      fetchVMs();
    } catch (error) {
      message.error('Failed to create VM');
    }
  };

  const handleStartVM = async (vmId) => {
    try {
      await axios.post(`/api/v1/vms/${vmId}/start`);
      message.success('VM started');
      fetchVMs();
    } catch (error) {
      message.error('Failed to start VM');
    }
  };

  const handleStopVM = async (vmId) => {
    try {
      await axios.post(`/api/v1/vms/${vmId}/stop`);
      message.success('VM stopped');
      fetchVMs();
    } catch (error) {
      message.error('Failed to stop VM');
    }
  };

  const handleDeleteVM = async (vmId) => {
    try {
      await axios.delete(`/api/v1/vms/${vmId}`);
      message.success('VM termination initiated');
      fetchVMs();
    } catch (error) {
      message.error('Failed to delete VM');
    }
  };

  const getStatusColor = (status) => {
    const colors = {
      running: 'green',
      stopped: 'orange',
      provisioning: 'blue',
      terminating: 'red',
      terminated: 'default',
    };
    return colors[status] || 'default';
  };

  const columns = [
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
      title: 'IP Address',
      dataIndex: 'ip_address',
      key: 'ip_address',
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
      render: (date) => new Date(date).toLocaleString(),
    },
    {
      title: 'Actions',
      key: 'actions',
      render: (_, record) => (
        <Space>
          {record.status === 'stopped' && (
            <Button
              type="primary"
              size="small"
              icon={<PlayCircleOutlined />}
              onClick={() => handleStartVM(record.id)}
            >
              Start
            </Button>
          )}
          {record.status === 'running' && (
            <Button
              size="small"
              icon={<PauseCircleOutlined />}
              onClick={() => handleStopVM(record.id)}
            >
              Stop
            </Button>
          )}
          <Popconfirm
            title="Are you sure you want to terminate this VM?"
            onConfirm={() => handleDeleteVM(record.id)}
            okText="Yes"
            cancelText="No"
          >
            <Button
              danger
              size="small"
              icon={<DeleteOutlined />}
            >
              Delete
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const vmStats = {
    total: vms.length,
    running: vms.filter(vm => vm.status === 'running').length,
    stopped: vms.filter(vm => vm.status === 'stopped').length,
    provisioning: vms.filter(vm => vm.status === 'provisioning').length,
  };

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 24 }}>
        <Col span={6}>
          <Card>
            <Statistic title="Total VMs" value={vmStats.total} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Running" value={vmStats.running} valueStyle={{ color: '#3f8600' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Stopped" value={vmStats.stopped} valueStyle={{ color: '#cf1322' }} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="Provisioning" value={vmStats.provisioning} valueStyle={{ color: '#1890ff' }} />
          </Card>
        </Col>
      </Row>

      <Card
        title="Virtual Machines"
        extra={
          <Space>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setModalVisible(true)}
            >
              Create VM
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={fetchVMs}
            >
              Refresh
            </Button>
          </Space>
        }
      >
        <Table
          columns={columns}
          dataSource={vms}
          rowKey="id"
          loading={loading}
          pagination={{ pageSize: 10 }}
        />
      </Card>

      <Modal
        title="Create Virtual Machine"
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        footer={null}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreateVM}
        >
          <Form.Item
            name="name"
            label="VM Name"
            rules={[{ required: true, message: 'Please enter VM name' }]}
          >
            <Input placeholder="Enter VM name" />
          </Form.Item>

          <Form.Item
            name="os_type"
            label="Operating System"
            rules={[{ required: true, message: 'Please select OS' }]}
          >
            <Select
              placeholder="Select OS"
              options={osOptions}
              onChange={(value) => {
                form.setFieldsValue({ os_version: undefined });
              }}
            />
          </Form.Item>

          <Form.Item
            name="os_version"
            label="OS Version"
            rules={[{ required: true, message: 'Please select OS version' }]}
          >
            <Select
              placeholder="Select OS version"
              options={osVersionOptions[form.getFieldValue('os_type')] || []}
            />
          </Form.Item>

          <Row gutter={16}>
            <Col span={8}>
              <Form.Item
                name="cpu_cores"
                label="CPU Cores"
                rules={[{ required: true, message: 'Please enter CPU cores' }]}
              >
                <InputNumber min={1} max={32} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="memory_gb"
                label="Memory (GB)"
                rules={[{ required: true, message: 'Please enter memory' }]}
              >
                <InputNumber min={1} max={256} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item
                name="storage_gb"
                label="Storage (GB)"
                rules={[{ required: true, message: 'Please enter storage' }]}
              >
                <InputNumber min={10} max={2048} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item>
            <Space>
              <Button type="primary" htmlType="submit">
                Create VM
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

export default VMManagement;