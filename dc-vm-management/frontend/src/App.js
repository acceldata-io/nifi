import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import 'antd/dist/reset.css';
import './App.css';

import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import VMManagement from './pages/VMManagement';
import ResourceManagement from './pages/ResourceManagement';
import Monitoring from './pages/Monitoring';
import Login from './pages/Login';

function App() {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#1890ff',
          borderRadius: 6,
        },
      }}
    >
      <Router>
        <div className="App">
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route path="/" element={<Layout />}>
              <Route index element={<Dashboard />} />
              <Route path="vms" element={<VMManagement />} />
              <Route path="resources" element={<ResourceManagement />} />
              <Route path="monitoring" element={<Monitoring />} />
            </Route>
          </Routes>
        </div>
      </Router>
    </ConfigProvider>
  );
}

export default App;