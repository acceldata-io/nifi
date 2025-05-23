/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Configuration;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.EndPoint;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SniEndPoint;
import com.datastax.driver.core.SimpleStatement;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.datastax.driver.core.exceptions.ReadTimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.MockProcessContext;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class QueryCassandraTest {

    private TestRunner testRunner;
    private MockQueryCassandra processor;

    @BeforeEach
    public void setUp() throws Exception {
        processor = new MockQueryCassandra();
        testRunner = TestRunners.newTestRunner(processor);
    }

    @Test
    public void testProcessorConfigValid() {
        testRunner.setProperty(AbstractCassandraProcessor.CONSISTENCY_LEVEL, "ONE");
        testRunner.setProperty(AbstractCassandraProcessor.CONTACT_POINTS, "localhost:9042");
        testRunner.assertNotValid();
        testRunner.setProperty(QueryCassandra.CQL_SELECT_QUERY, "select * from test");
        testRunner.assertValid();
        testRunner.setProperty(AbstractCassandraProcessor.PASSWORD, "password");
        testRunner.assertNotValid();
        testRunner.setProperty(AbstractCassandraProcessor.USERNAME, "username");
        testRunner.assertValid();

        testRunner.setProperty(QueryCassandra.TIMESTAMP_FORMAT_PATTERN, "invalid format");
        testRunner.assertNotValid();
        testRunner.setProperty(QueryCassandra.TIMESTAMP_FORMAT_PATTERN, "yyyy-MM-dd HH:mm:ss.SSSZ");
        testRunner.assertValid();
    }

    @Test
    public void testProcessorELConfigValid() {
        testRunner.setProperty(AbstractCassandraProcessor.CONSISTENCY_LEVEL, "ONE");
        testRunner.setProperty(AbstractCassandraProcessor.CONTACT_POINTS, "${hosts}");
        testRunner.setProperty(QueryCassandra.CQL_SELECT_QUERY, "${query}");
        testRunner.setProperty(AbstractCassandraProcessor.PASSWORD, "${pass}");
        testRunner.setProperty(AbstractCassandraProcessor.USERNAME, "${user}");
        testRunner.assertValid();
    }

    @Test
    public void testProcessorNoInputFlowFileAndExceptions() {
        setUpStandardProcessorConfig();

        // Test no input flowfile
        testRunner.setIncomingConnection(false);
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_SUCCESS, 1);
        testRunner.clearTransferState();

        // Test exceptions
        processor.setExceptionToThrow(new NoHostAvailableException(new HashMap<EndPoint, Throwable>()));
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_RETRY, 1);
        testRunner.clearTransferState();

        processor.setExceptionToThrow(
                new ReadTimeoutException(new SniEndPoint(new InetSocketAddress("localhost", 9042), ""), ConsistencyLevel.ANY, 0, 1, false));
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_RETRY, 1);
        testRunner.clearTransferState();

        processor.setExceptionToThrow(
                new InvalidQueryException(new SniEndPoint(new InetSocketAddress("localhost", 9042), ""), "invalid query"));
        testRunner.run(1, true, true);
        // No files transferred to failure if there was no incoming connection
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_FAILURE, 0);
        testRunner.clearTransferState();

        processor.setExceptionToThrow(new ProcessException());
        testRunner.run(1, true, true);
        // No files transferred to failure if there was no incoming connection
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_FAILURE, 0);
        testRunner.clearTransferState();
        processor.setExceptionToThrow(null);

    }

    @Test
    public void testProcessorJsonOutput() {
        setUpStandardProcessorConfig();
        testRunner.setIncomingConnection(false);

        // Test JSON output
        testRunner.setProperty(QueryCassandra.OUTPUT_FORMAT, QueryCassandra.JSON_FORMAT);
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_SUCCESS, 1);
        List<MockFlowFile> files = testRunner.getFlowFilesForRelationship(QueryCassandra.REL_SUCCESS);
        assertNotNull(files);
        assertEquals(1, files.size(), "One file should be transferred to success");
        assertEquals("{\"results\":[{\"user_id\":\"user1\",\"first_name\":\"Joe\",\"last_name\":\"Smith\","
                        + "\"emails\":[\"jsmith@notareal.com\"],\"top_places\":[\"New York, NY\",\"Santa Clara, CA\"],"
                        + "\"todo\":{\"2016-01-03 05:00:00+0000\":\"Set my alarm \\\"for\\\" a month from now\"},"
                        + "\"registered\":\"false\",\"scale\":1.0,\"metric\":2.0},"
                        + "{\"user_id\":\"user2\",\"first_name\":\"Mary\",\"last_name\":\"Jones\","
                        + "\"emails\":[\"mjones@notareal.com\"],\"top_places\":[\"Orlando, FL\"],"
                        + "\"todo\":{\"2016-02-03 05:00:00+0000\":\"Get milk and bread\"},"
                        + "\"registered\":\"true\",\"scale\":3.0,\"metric\":4.0}]}",
                new String(files.get(0).toByteArray()));
    }

    @Test
    public void testProcessorJsonOutputFragmentAttributes() {
        processor = new MockQueryCassandraTwoRounds();
        testRunner = TestRunners.newTestRunner(processor);
        setUpStandardProcessorConfig();
        testRunner.setIncomingConnection(false);
        testRunner.setProperty(QueryCassandra.MAX_ROWS_PER_FLOW_FILE, "1");

        // Test JSON output
        testRunner.setProperty(QueryCassandra.OUTPUT_FORMAT, QueryCassandra.JSON_FORMAT);
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_SUCCESS, 2);
        List<MockFlowFile> files = testRunner.getFlowFilesForRelationship(QueryCassandra.REL_SUCCESS);
        assertNotNull(files);
        assertEquals(2, files.size(), "Two files should be transferred to success");
        String indexIdentifier = null;
        for (int i = 0; i < files.size(); i++) {
            MockFlowFile flowFile = files.get(i);
            flowFile.assertAttributeEquals(QueryCassandra.FRAGMENT_INDEX, String.valueOf(i));
            if (indexIdentifier == null) {
                indexIdentifier = flowFile.getAttribute(QueryCassandra.FRAGMENT_ID);
            } else {
                flowFile.assertAttributeEquals(QueryCassandra.FRAGMENT_ID, indexIdentifier);
            }
            flowFile.assertAttributeEquals(QueryCassandra.FRAGMENT_COUNT, String.valueOf(files.size()));
        }
    }

    @Test
    public void testProcessorELConfigJsonOutput() {
        setUpStandardProcessorConfig();
        testRunner.setProperty(AbstractCassandraProcessor.CONTACT_POINTS, "${hosts}");
        testRunner.setProperty(QueryCassandra.CQL_SELECT_QUERY, "${query}");
        testRunner.setProperty(AbstractCassandraProcessor.PASSWORD, "${pass}");
        testRunner.setProperty(AbstractCassandraProcessor.USERNAME, "${user}");
        testRunner.setProperty(AbstractCassandraProcessor.CHARSET, "${charset}");
        testRunner.setProperty(QueryCassandra.QUERY_TIMEOUT, "${timeout}");
        testRunner.setProperty(QueryCassandra.FETCH_SIZE, "${fetch}");
        testRunner.setProperty(QueryCassandra.MAX_ROWS_PER_FLOW_FILE, "${max-rows-per-flow}");
        testRunner.setIncomingConnection(false);
        testRunner.assertValid();

        testRunner.setVariable("hosts", "localhost:9042");
        testRunner.setVariable("user", "username");
        testRunner.setVariable("pass", "password");
        testRunner.setVariable("charset", "UTF-8");
        testRunner.setVariable("timeout", "30 sec");
        testRunner.setVariable("fetch", "0");
        testRunner.setVariable("max-rows-per-flow", "0");

        // Test JSON output
        testRunner.setProperty(QueryCassandra.OUTPUT_FORMAT, QueryCassandra.JSON_FORMAT);
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_SUCCESS, 1);
        List<MockFlowFile> files = testRunner.getFlowFilesForRelationship(QueryCassandra.REL_SUCCESS);
        assertNotNull(files);
        assertEquals(1, files.size(), "One file should be transferred to success");
        assertEquals("{\"results\":[{\"user_id\":\"user1\",\"first_name\":\"Joe\",\"last_name\":\"Smith\","
                        + "\"emails\":[\"jsmith@notareal.com\"],\"top_places\":[\"New York, NY\",\"Santa Clara, CA\"],"
                        + "\"todo\":{\"2016-01-03 05:00:00+0000\":\"Set my alarm \\\"for\\\" a month from now\"},"
                        + "\"registered\":\"false\",\"scale\":1.0,\"metric\":2.0},"
                        + "{\"user_id\":\"user2\",\"first_name\":\"Mary\",\"last_name\":\"Jones\","
                        + "\"emails\":[\"mjones@notareal.com\"],\"top_places\":[\"Orlando, FL\"],"
                        + "\"todo\":{\"2016-02-03 05:00:00+0000\":\"Get milk and bread\"},"
                        + "\"registered\":\"true\",\"scale\":3.0,\"metric\":4.0}]}",
                new String(files.get(0).toByteArray()));
    }

    @Test
    public void testProcessorJsonOutputWithQueryTimeout() {
        setUpStandardProcessorConfig();
        testRunner.setProperty(QueryCassandra.QUERY_TIMEOUT, "5 sec");
        testRunner.setIncomingConnection(false);

        // Test JSON output
        testRunner.setProperty(QueryCassandra.OUTPUT_FORMAT, QueryCassandra.JSON_FORMAT);
        testRunner.run(1, true, true);
        testRunner.assertAllFlowFilesTransferred(QueryCassandra.REL_SUCCESS, 1);
        List<MockFlowFile> files = testRunner.getFlowFilesForRelationship(QueryCassandra.REL_SUCCESS);
        assertNotNull(files);
        assertEquals(1, files.size(), "One file should be transferred to success");
    }

    @Test
    public void testProcessorEmptyFlowFile() {
        setUpStandardProcessorConfig();

        // Run with empty flowfile
        testRunner.setIncomingConnection(true);
        processor.setExceptionToThrow(null);
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_SUCCESS, 1);
        testRunner.clearTransferState();
    }

    @Test
    public void testProcessorEmptyFlowFileMaxRowsPerFlowFileEqOne() {

        processor = new MockQueryCassandraTwoRounds();
        testRunner = TestRunners.newTestRunner(processor);

        setUpStandardProcessorConfig();

        testRunner.setIncomingConnection(true);
        testRunner.setProperty(QueryCassandra.MAX_ROWS_PER_FLOW_FILE, "1");
        processor.setExceptionToThrow(null);
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_SUCCESS, 2);
        testRunner.clearTransferState();
    }


    @Test
    public void testProcessorEmptyFlowFileAndNoHostAvailableException() {
        setUpStandardProcessorConfig();

        // Test exceptions
        processor.setExceptionToThrow(new NoHostAvailableException(new HashMap<EndPoint, Throwable>()));
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_RETRY, 1);
        testRunner.clearTransferState();
    }

    @Test
    public void testProcessorEmptyFlowFileAndInetSocketAddressConsistencyLevelANY() {
        setUpStandardProcessorConfig();

        processor.setExceptionToThrow(
                new ReadTimeoutException(new SniEndPoint(new InetSocketAddress("localhost", 9042), ""), ConsistencyLevel.ANY, 0, 1, false));
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_RETRY, 1);
        testRunner.clearTransferState();
    }

    @Test
    public void testProcessorEmptyFlowFileAndInetSocketAddressDefault() {
        setUpStandardProcessorConfig();

        processor.setExceptionToThrow(
                new InvalidQueryException(new SniEndPoint(new InetSocketAddress("localhost", 9042), ""), "invalid query"));
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_FAILURE, 1);
        testRunner.clearTransferState();
    }

    @Test
    public void testProcessorEmptyFlowFileAndExceptionsProcessException() {
        setUpStandardProcessorConfig();

        processor.setExceptionToThrow(new ProcessException());
        testRunner.enqueue("".getBytes());
        testRunner.run(1, true, true);
        testRunner.assertTransferCount(QueryCassandra.REL_FAILURE, 1);
    }

    // --

    @Test
    public void testCreateSchemaOneColumn() throws Exception {
        ResultSet rs = CassandraQueryTestUtil.createMockResultSetOneColumn();
        Schema schema = QueryCassandra.createSchema(rs);
        assertNotNull(schema);
        assertEquals(schema.getName(), "users");
    }

    @Test
    public void testCreateSchema() throws Exception {
        ResultSet rs = CassandraQueryTestUtil.createMockResultSet(true);
        Schema schema = QueryCassandra.createSchema(rs);
        assertNotNull(schema);
        assertEquals(Schema.Type.RECORD, schema.getType());

        // Check record fields, starting with user_id
        Schema.Field field = schema.getField("user_id");
        assertNotNull(field);
        Schema fieldSchema = field.schema();
        Schema.Type type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, fieldSchema.getTypes().get(1).getType());

        field = schema.getField("first_name");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, fieldSchema.getTypes().get(1).getType());

        field = schema.getField("last_name");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, fieldSchema.getTypes().get(1).getType());

        field = schema.getField("emails");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        // Should be a union of null and array
        assertEquals(Schema.Type.UNION, type);
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.ARRAY, fieldSchema.getTypes().get(1).getType());
        Schema arraySchema = fieldSchema.getTypes().get(1);
        // Assert individual array element types are unions of null and String
        Schema elementSchema = arraySchema.getElementType();
        assertEquals(Schema.Type.UNION, elementSchema.getType());
        assertEquals(Schema.Type.NULL, elementSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, elementSchema.getTypes().get(1).getType());

        field = schema.getField("top_places");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        // Should be a union of null and array
        assertEquals(Schema.Type.UNION, type);
        assertEquals(Schema.Type.ARRAY, fieldSchema.getTypes().get(1).getType());
        arraySchema = fieldSchema.getTypes().get(1);
        // Assert individual array element types are unions of null and String
        elementSchema = arraySchema.getElementType();
        assertEquals(Schema.Type.UNION, elementSchema.getType());
        assertEquals(Schema.Type.NULL, elementSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, elementSchema.getTypes().get(1).getType());

        field = schema.getField("todo");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        // Should be a union of null and map
        assertEquals(Schema.Type.UNION, type);
        assertEquals(Schema.Type.MAP, fieldSchema.getTypes().get(1).getType());
        Schema mapSchema = fieldSchema.getTypes().get(1);
        // Assert individual map value types are unions of null and String
        Schema valueSchema = mapSchema.getValueType();
        assertEquals(Schema.Type.NULL, valueSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.STRING, valueSchema.getTypes().get(1).getType());

        field = schema.getField("registered");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.BOOLEAN, fieldSchema.getTypes().get(1).getType());

        field = schema.getField("scale");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.FLOAT, fieldSchema.getTypes().get(1).getType());

        field = schema.getField("metric");
        assertNotNull(field);
        fieldSchema = field.schema();
        type = fieldSchema.getType();
        assertEquals(Schema.Type.UNION, type);
        // Assert individual union types, first is null
        assertEquals(Schema.Type.NULL, fieldSchema.getTypes().get(0).getType());
        assertEquals(Schema.Type.DOUBLE, fieldSchema.getTypes().get(1).getType());
    }

    @Test
    public void testConvertToAvroStream() throws Exception {
        processor = new MockQueryCassandraTwoRounds();
        testRunner = TestRunners.newTestRunner(processor);
        setUpStandardProcessorConfig();
        ResultSet rs = CassandraQueryTestUtil.createMockResultSet(false);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long numberOfRows = QueryCassandra.convertToAvroStream(rs, 0, baos, 0, null);
        assertEquals(2, numberOfRows);
    }

    @Test
    public void testConvertToJSONStream() throws Exception {
        setUpStandardProcessorConfig();
        ResultSet rs = CassandraQueryTestUtil.createMockResultSet();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long numberOfRows = QueryCassandra.convertToJsonStream(rs, 0, baos, StandardCharsets.UTF_8,
                0, null);
        assertEquals(2, numberOfRows);
    }

    @Test
    public void testDefaultDateFormatInConvertToJSONStream() throws Exception {
        ResultSet rs = CassandraQueryTestUtil.createMockDateResultSet();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        DateFormat df = new SimpleDateFormat(QueryCassandra.TIMESTAMP_FORMAT_PATTERN.getDefaultValue());
        df.setTimeZone(TimeZone.getTimeZone("UTC"));

        long numberOfRows = QueryCassandra.convertToJsonStream(Optional.of(testRunner.getProcessContext()), rs, 0, baos,
            StandardCharsets.UTF_8, 0, null);
        assertEquals(1, numberOfRows);

        Map<String, List<Map<String, String>>> map = new ObjectMapper().readValue(baos.toByteArray(), HashMap.class);
        String date = map.get("results").get(0).get("date");
        assertEquals(df.format(CassandraQueryTestUtil.TEST_DATE), date);
    }

    @Test
    public void testCustomDateFormatInConvertToJSONStream() throws Exception {
        MockProcessContext context = (MockProcessContext) testRunner.getProcessContext();
        ResultSet rs = CassandraQueryTestUtil.createMockDateResultSet();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        final String customDateFormat = "yyyy-MM-dd HH:mm:ss.SSSZ";
        context.setProperty(QueryCassandra.TIMESTAMP_FORMAT_PATTERN, customDateFormat);
        DateFormat df = new SimpleDateFormat(customDateFormat);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));

        long numberOfRows = QueryCassandra.convertToJsonStream(Optional.of(context), rs, 0, baos, StandardCharsets.UTF_8, 0, null);
        assertEquals(1, numberOfRows);

        Map<String, List<Map<String, String>>> map = new ObjectMapper().readValue(baos.toByteArray(), HashMap.class);
        String date = map.get("results").get(0).get("date");
        assertEquals(df.format(CassandraQueryTestUtil.TEST_DATE), date);
    }

    private void setUpStandardProcessorConfig() {
        testRunner.setProperty(AbstractCassandraProcessor.CONSISTENCY_LEVEL, "ONE");
        testRunner.setProperty(AbstractCassandraProcessor.CONTACT_POINTS, "localhost:9042");
        testRunner.setProperty(QueryCassandra.CQL_SELECT_QUERY, "select * from test");
        testRunner.setProperty(AbstractCassandraProcessor.PASSWORD, "password");
        testRunner.setProperty(AbstractCassandraProcessor.USERNAME, "username");
        testRunner.setProperty(QueryCassandra.MAX_ROWS_PER_FLOW_FILE, "0");
    }

    /**
     * Provides a stubbed processor instance for testing
     */
    private static class MockQueryCassandra extends QueryCassandra {

        private Exception exceptionToThrow = null;

        @Override
        protected Cluster createCluster(List<InetSocketAddress> contactPoints, SSLContext sslContext,
                                        String username, String password, String compressionType) {
            Cluster mockCluster = mock(Cluster.class);
            try {
                Metadata mockMetadata = mock(Metadata.class);
                when(mockMetadata.getClusterName()).thenReturn("cluster1");
                when(mockCluster.getMetadata()).thenReturn(mockMetadata);
                Session mockSession = mock(Session.class);
                when(mockCluster.connect()).thenReturn(mockSession);
                when(mockCluster.connect(anyString())).thenReturn(mockSession);
                Configuration config = Configuration.builder().build();
                when(mockCluster.getConfiguration()).thenReturn(config);
                ResultSetFuture future = mock(ResultSetFuture.class);
                ResultSet rs = CassandraQueryTestUtil.createMockResultSet(false);
                when(future.getUninterruptibly()).thenReturn(rs);

                try {
                    doReturn(rs).when(future).getUninterruptibly(anyLong(), any(TimeUnit.class));
                } catch (TimeoutException te) {
                    throw new IllegalArgumentException("Mocked cluster doesn't time out");
                }

                if (exceptionToThrow != null) {
                    when(mockSession.execute(anyString(), any(), any())).thenThrow(exceptionToThrow);
                    when(mockSession.execute(anyString())).thenThrow(exceptionToThrow);
                } else {
                    when(mockSession.execute(anyString(),any(), any())).thenReturn(rs);
                    when(mockSession.execute(anyString())).thenReturn(rs);
                    when(mockSession.execute(any(SimpleStatement.class))).thenReturn(rs);
                }
            } catch (Exception e) {
                fail(e.getMessage());
            }
            return mockCluster;
        }

        public void setExceptionToThrow(Exception e) {
            this.exceptionToThrow = e;
        }
    }

    private static class MockQueryCassandraTwoRounds extends MockQueryCassandra {

        private Exception exceptionToThrow = null;

        @Override
        protected Cluster createCluster(List<InetSocketAddress> contactPoints, SSLContext sslContext,
                                        String username, String password, String compressionType) {
            Cluster mockCluster = mock(Cluster.class);
            try {
                Metadata mockMetadata = mock(Metadata.class);
                when(mockMetadata.getClusterName()).thenReturn("cluster1");
                when(mockCluster.getMetadata()).thenReturn(mockMetadata);
                Session mockSession = mock(Session.class);
                when(mockCluster.connect()).thenReturn(mockSession);
                when(mockCluster.connect(anyString())).thenReturn(mockSession);
                Configuration config = Configuration.builder().build();
                when(mockCluster.getConfiguration()).thenReturn(config);
                ResultSetFuture future = mock(ResultSetFuture.class);
                ResultSet rs = CassandraQueryTestUtil.createMockResultSet(true);
                when(future.getUninterruptibly()).thenReturn(rs);

                try {
                    doReturn(rs).when(future).getUninterruptibly(anyLong(), any(TimeUnit.class));
                } catch (TimeoutException te) {
                    throw new IllegalArgumentException("Mocked cluster doesn't time out");
                }

                if (exceptionToThrow != null) {
                    when(mockSession.execute(anyString(), any(), any())).thenThrow(exceptionToThrow);
                    when(mockSession.execute(anyString())).thenThrow(exceptionToThrow);
                } else {
                    when(mockSession.execute(anyString(),any(), any())).thenReturn(rs);
                    when(mockSession.execute(anyString())).thenReturn(rs);
                    when(mockSession.execute(any(SimpleStatement.class))).thenReturn(rs);
                }
            } catch (Exception e) {
                fail(e.getMessage());
            }
            return mockCluster;
        }

        public void setExceptionToThrow(Exception e) {
            this.exceptionToThrow = e;
        }
    }

}

