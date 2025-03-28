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
package org.apache.nifi.processors.slack;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PutSlackTest {

    private TestRunner testRunner;
    public static final String WEBHOOK_TEST_TEXT = "Hello From Apache NiFi";

    private MockWebServer mockWebServer;

    private String url;

    @BeforeEach
    public void init() {
        mockWebServer = new MockWebServer();
        url = mockWebServer.url("/").toString();
        testRunner = TestRunners.newTestRunner(PutSlack.class);
    }

    @Test
    public void testBlankText() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, "");

        testRunner.enqueue(new byte[0]);
        assertThrows(AssertionError.class, () -> testRunner.run(1));
    }

    @Test
    public void testBlankTextViaExpression() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, "${invalid-attr}"); // Create a blank webhook text

        testRunner.enqueue(new byte[0]);
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_FAILURE);
    }

    @Test
    public void testInvalidChannel() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        testRunner.setProperty(PutSlack.CHANNEL, "invalid");

        testRunner.enqueue(new byte[0]);
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_FAILURE);
    }

    @Test
    public void testInvalidIconUrl() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        testRunner.setProperty(PutSlack.ICON_URL, "invalid");

        testRunner.enqueue(new byte[0]);
        assertThrows(AssertionError.class, () -> testRunner.run(1));
    }

    @Test
    public void testInvalidIconEmoji() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        testRunner.setProperty(PutSlack.ICON_EMOJI, "invalid");

        testRunner.enqueue(new byte[0]);
        assertThrows(AssertionError.class, () -> testRunner.run(1));
    }

    @Test
    public void testInvalidDynamicProperties() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        PropertyDescriptor dynamicProp = new PropertyDescriptor.Builder()
                .dynamic(true)
                .name("foo")
                .build();
        testRunner.setProperty(dynamicProp, "{\"a\": a}");

        testRunner.enqueue("{}".getBytes());
        testRunner.run(1);
        testRunner.assertTransferCount(PutSlack.REL_FAILURE, 1);
    }

    @Test
    public void testValidDynamicProperties() {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        PropertyDescriptor dynamicProp = new PropertyDescriptor.Builder()
                .dynamic(true)
                .name("foo")
                .build();
        testRunner.setProperty(dynamicProp, "{\"a\": \"a\"}");

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue("{}".getBytes());
        testRunner.run(1);
        testRunner.assertTransferCount(PutSlack.REL_FAILURE, 0);
    }

    @Test
    public void testValidDynamicPropertiesWithExpressionLanguage() {
        ProcessSession session = testRunner.getProcessSessionFactory().createSession();
        FlowFile ff = session.create();
        Map<String, String> props = new HashMap<>();
        props.put("foo", "\"bar\"");
        props.put("ping", "pong");
        ff = session.putAllAttributes(ff, props);

        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        PropertyDescriptor dynamicProp = new PropertyDescriptor.Builder()
                .dynamic(true)
                .name("foo")
                .build();
        testRunner.setProperty(dynamicProp, "{\"foo\": ${foo}, \"ping\":\"${ping}\"}");

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue(ff);
        testRunner.run(1);
        testRunner.assertTransferCount(PutSlack.REL_SUCCESS, 1);
    }

    @Test
    public void testInvalidDynamicPropertiesWithExpressionLanguage() {
        ProcessSession session = testRunner.getProcessSessionFactory().createSession();
        FlowFile ff = session.create();
        Map<String, String> props = new HashMap<>();
        props.put("foo", "\"\"bar\"");
        props.put("ping", "\"pong");
        ff = session.putAllAttributes(ff, props);

        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, WEBHOOK_TEST_TEXT);
        PropertyDescriptor dynamicProp = new PropertyDescriptor.Builder()
                .dynamic(true)
                .name("foo")
                .build();
        testRunner.setProperty(dynamicProp, "{\"foo\": ${foo}, \"ping\":\"${ping}\"}");

        testRunner.enqueue(ff);
        testRunner.run(1);
        testRunner.assertTransferCount(PutSlack.REL_SUCCESS, 0);
        testRunner.assertTransferCount(PutSlack.REL_FAILURE, 1);
    }

    @Test
    public void testGetPropertyDescriptors() {
        PutSlack processor = new PutSlack();
        List<PropertyDescriptor> pd = processor.getSupportedPropertyDescriptors();
        assertEquals(7, pd.size(), "size should be eq");
        assertTrue(pd.contains(PutSlack.WEBHOOK_TEXT));
        assertTrue(pd.contains(PutSlack.WEBHOOK_URL));
        assertTrue(pd.contains(PutSlack.CHANNEL));
        assertTrue(pd.contains(PutSlack.USERNAME));
        assertTrue(pd.contains(PutSlack.ICON_URL));
        assertTrue(pd.contains(PutSlack.ICON_EMOJI));
        assertTrue(pd.contains(PutSlack.SSL_CONTEXT_SERVICE));
    }

    @Test
    public void testSimplePut() throws InterruptedException {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, PutSlackTest.WEBHOOK_TEST_TEXT);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue(new byte[0]);
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_SUCCESS, 1);

        String expected = "payload=%7B%22text%22%3A%22Hello+From+Apache+NiFi%22%7D";
        final RecordedRequest recordedRequest = mockWebServer.takeRequest();
        final String requestBody = recordedRequest.getBody().readString(StandardCharsets.UTF_8);
        assertEquals(expected, requestBody);
    }

    @Test
    public void testSimplePutWithAttributes() throws InterruptedException {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, PutSlackTest.WEBHOOK_TEST_TEXT);
        testRunner.setProperty(PutSlack.CHANNEL, "#test-attributes");
        testRunner.setProperty(PutSlack.USERNAME, "integration-test-webhook");
        testRunner.setProperty(PutSlack.ICON_EMOJI, ":smile:");

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue(new byte[0]);
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_SUCCESS, 1);

        final String expected = "payload=%7B%22text%22%3A%22Hello+From+Apache+NiFi%22%2C%22channel%22%3A%22%23test-attributes%22%2C%22username%22%3A%22" +
                "integration-test-webhook%22%2C%22icon_emoji%22%3A%22%3Asmile%3A%22%7D";
        final RecordedRequest recordedRequest = mockWebServer.takeRequest();
        final String requestBody = recordedRequest.getBody().readString(StandardCharsets.UTF_8);
        assertEquals(expected, requestBody);
    }

    @Test
    public void testSimplePutWithAttributesIconURL() throws InterruptedException {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, url);
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, PutSlackTest.WEBHOOK_TEST_TEXT);
        testRunner.setProperty(PutSlack.CHANNEL, "#test-attributes-url");
        testRunner.setProperty(PutSlack.USERNAME, "integration-test-webhook");
        testRunner.setProperty(PutSlack.ICON_URL, "http://lorempixel.com/48/48/");

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue(new byte[0]);
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_SUCCESS, 1);

        final String expected = "payload=%7B%22text%22%3A%22Hello+From+Apache+NiFi%22%2C%22channel%22%3A%22%23test-attributes-url%22%2C%22username%22%3A%22"
            + "integration-test-webhook%22%2C%22icon_url%22%3A%22http%3A%2F%2Florempixel.com%2F48%2F48%2F%22%7D";
        final RecordedRequest recordedRequest = mockWebServer.takeRequest();
        final String requestBody = recordedRequest.getBody().readString(StandardCharsets.UTF_8);
        assertEquals(expected, requestBody);
    }

    @Test
    public void testSimplePutWithEL() throws InterruptedException {
        testRunner.setProperty(PutSlack.WEBHOOK_URL, "${slack.url}");
        testRunner.setProperty(PutSlack.WEBHOOK_TEXT, PutSlackTest.WEBHOOK_TEST_TEXT);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200));

        testRunner.enqueue(new byte[0], new HashMap<String,String>(){{
            put("slack.url", url);
        }});
        testRunner.run(1);
        testRunner.assertAllFlowFilesTransferred(PutSlack.REL_SUCCESS, 1);

        String expected = "payload=%7B%22text%22%3A%22Hello+From+Apache+NiFi%22%7D";
        final RecordedRequest recordedRequest = mockWebServer.takeRequest();
        final String requestBody = recordedRequest.getBody().readString(StandardCharsets.UTF_8);
        assertEquals(expected, requestBody);
    }
}
