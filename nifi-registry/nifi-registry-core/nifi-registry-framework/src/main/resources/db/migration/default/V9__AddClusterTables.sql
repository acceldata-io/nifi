-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Cache version table: one row per authorization domain, incremented on every write by any cluster node.
-- Nodes poll this table to detect out-of-date in-memory caches and trigger a reload.

CREATE TABLE CACHE_VERSION (
    CACHE_DOMAIN VARCHAR(50) NOT NULL,
    VERSION      BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT PK__CACHE_VERSION PRIMARY KEY (CACHE_DOMAIN)
);

INSERT INTO CACHE_VERSION (CACHE_DOMAIN, VERSION) VALUES ('ACCESS_POLICIES', 0);
INSERT INTO CACHE_VERSION (CACHE_DOMAIN, VERSION) VALUES ('USER_GROUPS', 0);

-- Cluster node registration table: tracks live nodes for operational visibility.
-- Updated by a heartbeat thread on each node; no quorum or eviction is performed in this phase.

CREATE TABLE CLUSTER_NODE (
    NODE_ID        VARCHAR(100) NOT NULL,
    HOSTNAME       VARCHAR(255) NOT NULL,
    LAST_HEARTBEAT TIMESTAMP   NOT NULL,
    CONSTRAINT PK__CLUSTER_NODE PRIMARY KEY (NODE_ID)
);
