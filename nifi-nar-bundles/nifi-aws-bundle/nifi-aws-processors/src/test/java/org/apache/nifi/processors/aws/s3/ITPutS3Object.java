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
package org.apache.nifi.processors.aws.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingResult;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.ListMultipartUploadsRequest;
import com.amazonaws.services.s3.model.MultipartUpload;
import com.amazonaws.services.s3.model.MultipartUploadListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.model.Tag;
import org.apache.commons.codec.binary.Base64;
import org.apache.nifi.fileresource.service.StandardFileResourceService;
import org.apache.nifi.fileresource.service.api.FileResourceService;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderControllerService;
import org.apache.nifi.processors.aws.s3.encryption.StandardS3EncryptionService;
import org.apache.nifi.processors.aws.util.RegionUtilV1;
import org.apache.nifi.processors.transfer.ResourceTransferSource;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.MockPropertyValue;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.apache.nifi.processors.transfer.ResourceTransferProperties.FILE_RESOURCE_SERVICE;
import static org.apache.nifi.processors.transfer.ResourceTransferProperties.RESOURCE_TRANSFER_SOURCE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Provides integration level testing with actual AWS S3 resources for {@link PutS3Object} and requires additional configuration and resources to work.
 */
public class ITPutS3Object extends AbstractS3IT {
    private static final Logger logger = LoggerFactory.getLogger(ITPutS3Object.class);

    final static String TEST_ENDPOINT = "https://endpoint.com";
    //    final static String TEST_TRANSIT_URI = "https://" + BUCKET_NAME + ".endpoint.com";
    final static String TEST_PARTSIZE_STRING = "50 mb";
    final static Long   TEST_PARTSIZE_LONG = 50L * 1024L * 1024L;

    final static Long S3_MINIMUM_PART_SIZE = 50L * 1024L * 1024L;
    final static Long S3_MAXIMUM_OBJECT_SIZE = 5L * 1024L * 1024L * 1024L;

    final static Pattern reS3ETag = Pattern.compile("[0-9a-fA-f]{32,32}(-[0-9]+)?");


    private static String kmsKeyId = "";
    private static String randomKeyMaterial = "";


    @BeforeAll
    public static void setupClass() {
        byte[] keyRawBytes = new byte[32];
        SecureRandom secureRandom = new SecureRandom();
        secureRandom.nextBytes(keyRawBytes);

        randomKeyMaterial = Base64.encodeBase64String(keyRawBytes);
        kmsKeyId = getKMSKey();
    }

    @AfterAll
    public static void teardownClass() {
        if (StringUtils.isNotEmpty(kmsKeyId)) {
            deleteKMSKey(kmsKeyId);
        }
    }

    @Test
    public void testSimplePut() throws IOException {
        TestRunner runner = initTestRunner();

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());

        for (int i = 0; i < 3; i++) {
            final Map<String, String> attrs = new HashMap<>();
            attrs.put("filename", i + ".txt");
            runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        }
        runner.run(3);

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 3);
    }

    @Test
    public void testSimplePutEncrypted() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.SERVER_SIDE_ENCRYPTION, ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());

        for (int i = 0; i < 3; i++) {
            final Map<String, String> attrs = new HashMap<>();
            attrs.put("filename", i + ".txt");
            runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        }
        runner.run(3);

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 3);
        final List<MockFlowFile> ffs = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        for (MockFlowFile flowFile : ffs) {
            flowFile.assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        }
    }

    @Test
    public void testSimplePutFilenameWithNationalCharacters() throws IOException {
        TestRunner runner = initTestRunner();

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "Iñtërnâtiônàližætiøn.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
    }

    private void testPutThenFetch(String sseAlgorithm) throws IOException {

        // Put
        TestRunner runner = initTestRunner();

        if(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION.equals(sseAlgorithm)){
            runner.setProperty(PutS3Object.SERVER_SIDE_ENCRYPTION, sseAlgorithm);
        }

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename",  "filename-on-s3.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> ffs = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        if(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION.equals(sseAlgorithm)){
            ffs.get(0).assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        } else {
            ffs.get(0).assertAttributeNotExists(PutS3Object.S3_SSE_ALGORITHM);
        }

        // Fetch
        runner = TestRunners.newTestRunner(new FetchS3Object());

        runner.setProperty(FetchS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(FetchS3Object.BUCKET, BUCKET_NAME);

        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3Object.REL_SUCCESS, 1);
        ffs = runner.getFlowFilesForRelationship(FetchS3Object.REL_SUCCESS);
        MockFlowFile ff = ffs.get(0);
        ff.assertContentEquals(getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME));

        if(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION.equals(sseAlgorithm)){
            ff.assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        } else {
            ff.assertAttributeNotExists(PutS3Object.S3_SSE_ALGORITHM);
        }

    }

    @Test
    public void testPutThenFetchWithoutSSE() throws IOException {
        testPutThenFetch(PutS3Object.NO_SERVER_SIDE_ENCRYPTION);
    }

    @Test
    public void testPutThenFetchWithSSE() throws IOException {
        testPutThenFetch(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
    }

    @Test
    public void testPutFromLocalFile() throws Exception {
        TestRunner runner = initTestRunner();
        String attributeName = "file.path";
        Path resourcePath = getResourcePath(SAMPLE_FILE_RESOURCE_NAME);

        String serviceId = FileResourceService.class.getSimpleName();
        FileResourceService service = new StandardFileResourceService();
        runner.addControllerService(serviceId, service);
        runner.setProperty(service, StandardFileResourceService.FILE_PATH, String.format("${%s}", attributeName));
        runner.enableControllerService(service);

        runner.setProperty(RESOURCE_TRANSFER_SOURCE, ResourceTransferSource.FILE_RESOURCE_SERVICE.getValue());
        runner.setProperty(FILE_RESOURCE_SERVICE, serviceId);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(attributeName, resourcePath.toString());
        runner.enqueue(resourcePath, attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        MockFlowFile flowFile = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS).get(0);
        flowFile.assertContentEquals(getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME));

        List<S3ObjectSummary> objectSummaries = client.listObjects(BUCKET_NAME).getObjectSummaries();
        assertThat(objectSummaries, hasSize(1));
        assertEquals(objectSummaries.get(0).getKey(), resourcePath.getFileName().toString());
        assertThat(objectSummaries.get(0).getSize(), greaterThan(0L));
    }

    @Test
    public void testPutFromNonExistentLocalFile() throws Exception {
        TestRunner runner = initTestRunner();
        String attributeName = "file.path";

        String serviceId = FileResourceService.class.getSimpleName();
        FileResourceService service = new StandardFileResourceService();
        runner.addControllerService(serviceId, service);
        runner.setProperty(service, StandardFileResourceService.FILE_PATH, String.format("${%s}", attributeName));
        runner.enableControllerService(service);

        runner.setProperty(RESOURCE_TRANSFER_SOURCE, ResourceTransferSource.FILE_RESOURCE_SERVICE.getValue());
        runner.setProperty(FILE_RESOURCE_SERVICE, serviceId);

        String filePath = "nonexistent.txt";

        Map<String, String> attributes = new HashMap<>();
        attributes.put(attributeName, filePath);

        runner.enqueue("", attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_FAILURE, 1);
        assertThat(client.listObjects(BUCKET_NAME).getObjectSummaries(), empty());
    }

    @Test
    public void testPutS3ObjectUsingCredentialsProviderService() throws Throwable {
        final TestRunner runner = TestRunners.newTestRunner(new PutS3Object());

        final AWSCredentialsProviderControllerService serviceImpl = new AWSCredentialsProviderControllerService();

        runner.addControllerService("awsCredentialsProvider", serviceImpl);

        runner.setProperty(serviceImpl, AbstractAWSCredentialsProviderProcessor.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.enableControllerService(serviceImpl);

        runner.assertValid(serviceImpl);

        runner.setProperty(PutS3Object.AWS_CREDENTIALS_PROVIDER_SERVICE, "awsCredentialsProvider");
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());

        for (int i = 0; i < 3; i++) {
            final Map<String, String> attrs = new HashMap<>();
            attrs.put("filename", i + ".txt");
            runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        }
        runner.run(3);

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 3);

    }

    @Test
    public void testMetaData() throws IOException {
        PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        PropertyDescriptor prop1 = processor.getSupportedDynamicPropertyDescriptor("TEST-PROP-1");
        runner.setProperty(prop1, "TESTING-1-2-3");
        PropertyDescriptor prop2 = processor.getSupportedDynamicPropertyDescriptor("TEST-PROP-2");
        runner.setProperty(prop2, "TESTING-4-5-6");

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "meta.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        MockFlowFile ff = flowFiles.get(0);
        ff.assertAttributeExists("s3.usermetadata");
    }

    @Test
    public void testContentType() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.CONTENT_TYPE, "text/plain");

        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        MockFlowFile ff1 = flowFiles.get(0);
        ff1.assertAttributeEquals(PutS3Object.S3_CONTENT_TYPE, "text/plain");
    }

    @Test
    public void testContentDispositionInline() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.CONTENT_DISPOSITION, PutS3Object.CONTENT_DISPOSITION_INLINE);

        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        MockFlowFile ff1 = flowFiles.get(0);
        ff1.assertAttributeEquals(PutS3Object.S3_CONTENT_DISPOSITION, PutS3Object.CONTENT_DISPOSITION_INLINE);
    }

    @Test
    public void testContentDispositionNull() throws IOException {
        // Put
        TestRunner runner = initTestRunner();

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename",  "filename-on-s3.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);

        // Fetch
        runner = TestRunners.newTestRunner(new FetchS3Object());

        runner.setProperty(FetchS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(FetchS3Object.BUCKET, BUCKET_NAME);

        runner.enqueue(new byte[0], attrs);

        runner.run(1);

        runner.assertAllFlowFilesTransferred(FetchS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> ffs = runner.getFlowFilesForRelationship(FetchS3Object.REL_SUCCESS);
        MockFlowFile ff = ffs.get(0);
        ff.assertContentEquals(getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME));
        ff.assertAttributeNotExists(PutS3Object.S3_CONTENT_DISPOSITION);
    }

    @Test
    public void testContentDispositionAttachment() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.CONTENT_DISPOSITION, PutS3Object.CONTENT_DISPOSITION_ATTACHMENT);

        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        MockFlowFile ff1 = flowFiles.get(0);
        ff1.assertAttributeEquals(PutS3Object.S3_CONTENT_DISPOSITION, "attachment; filename=\"hello.txt\"");
    }

    @Test
    public void testCacheControl() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.CACHE_CONTROL, "no-cache");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        MockFlowFile ff1 = flowFiles.get(0);
        ff1.assertAttributeEquals(PutS3Object.S3_CACHE_CONTROL, "no-cache");
    }

    @Test
    public void testPutInFolder() throws IOException {
        TestRunner runner = initTestRunner();

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());
        runner.assertValid();

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "folder/1.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
    }

    @Test
    public void testStorageClasses() throws IOException {
        TestRunner runner = initTestRunner();

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());

        for (StorageClass storageClass : StorageClass.values()) {
            if (storageClass == StorageClass.Outposts) {
                // Outposts storage class cannot be tested on AWS cloud infrastructure
                continue;
            }

            runner.setProperty(PutS3Object.STORAGE_CLASS, storageClass.name());

            final Map<String, String> attrs = new HashMap<>();
            attrs.put("filename", "testStorageClasses/small_" + storageClass.name() + ".txt");
            runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

            runner.run();

            runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
            FlowFile file = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS).get(0);
            assertEquals(storageClass.toString(), file.getAttribute(PutS3Object.S3_STORAGECLASS_ATTR_KEY));

            runner.clearTransferState();
        }
    }

    @Test
    public void testStorageClassesMultipart() {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.MULTIPART_THRESHOLD, "50 MB");
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, "50 MB");

        assertTrue(runner.setProperty("x-custom-prop", "hello").isValid());

        for (StorageClass storageClass : StorageClass.values()) {
            if (storageClass == StorageClass.Outposts) {
                // Outposts storage class cannot be tested on AWS cloud infrastructure
                continue;
            }

            runner.setProperty(PutS3Object.STORAGE_CLASS, storageClass.name());

            final Map<String, String> attrs = new HashMap<>();
            attrs.put("filename", "testStorageClasses/large_" + storageClass.name() + ".dat");
            runner.enqueue(new byte[50 * 1024 * 1024 + 1], attrs);

            runner.run();

            runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
            FlowFile file = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS).get(0);
            assertEquals(storageClass.toString(), file.getAttribute(PutS3Object.S3_STORAGECLASS_ATTR_KEY));

            runner.clearTransferState();
        }
    }

    @Test
    public void testPermissions() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.FULL_CONTROL_USER_LIST,"28545acd76c35c7e91f8409b95fd1aa0c0914bfa1ac60975d9f48bc3c5e090b5");

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "folder/4.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
    }

    @Test
    public void testDynamicProperty() {
        final String DYNAMIC_ATTRIB_KEY = "fs.runTimestamp";
        final String DYNAMIC_ATTRIB_VALUE = "${now():toNumber()}";

        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, TEST_PARTSIZE_STRING);
        PropertyDescriptor testAttrib = processor.getSupportedDynamicPropertyDescriptor(DYNAMIC_ATTRIB_KEY);
        runner.setProperty(testAttrib, DYNAMIC_ATTRIB_VALUE);

        final String FILE1_NAME = "file1";
        Map<String, String> attribs = new HashMap<>();
        attribs.put(CoreAttributes.FILENAME.key(), FILE1_NAME);
        runner.enqueue("123".getBytes(), attribs);

        runner.assertValid();
        processor.getPropertyDescriptor(DYNAMIC_ATTRIB_KEY);
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);
        final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, successFiles.size());
        MockFlowFile ff1 = successFiles.get(0);

        long now = System.currentTimeMillis();
        String millisNow = Long.toString(now);
        String millisOneSecAgo = Long.toString(now - 1000L);
        String usermeta = ff1.getAttribute(PutS3Object.S3_USERMETA_ATTR_KEY);
        String[] usermetaLine0 = usermeta.split(System.lineSeparator())[0].split("=");
        String usermetaKey0 = usermetaLine0[0];
        String usermetaValue0 = usermetaLine0[1];
        assertEquals(DYNAMIC_ATTRIB_KEY, usermetaKey0);
        assertTrue(usermetaValue0.compareTo(millisOneSecAgo) >=0 && usermetaValue0.compareTo(millisNow) <= 0);
    }

    @Test
    public void testProvenance() {
        final String PROV1_FILE = "provfile1";

        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.KEY, "${filename}");

        Map<String, String> attribs = new HashMap<>();
        attribs.put(CoreAttributes.FILENAME.key(), PROV1_FILE);
        runner.enqueue("prov1 contents".getBytes(), attribs);

        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);
        final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, successFiles.size());

        final List<ProvenanceEventRecord> provenanceEvents = runner.getProvenanceEvents();
        assertEquals(1, provenanceEvents.size());
        ProvenanceEventRecord provRec1 = provenanceEvents.get(0);
        assertEquals(ProvenanceEventType.SEND, provRec1.getEventType());
        assertEquals(processor.getIdentifier(), provRec1.getComponentId());
        client.setRegion(Region.fromValue(REGION).toAWSRegion());
        String targetUri = client.getUrl(BUCKET_NAME, PROV1_FILE).toString();
        assertEquals(targetUri, provRec1.getTransitUri());
        assertEquals(8, provRec1.getUpdatedAttributes().size());
        assertEquals(BUCKET_NAME, provRec1.getUpdatedAttributes().get(PutS3Object.S3_BUCKET_KEY));
    }

    @Test
    public void testStateDefaults() {
        PutS3Object.MultipartState state1 = new PutS3Object.MultipartState();
        assertEquals("", state1.getUploadId());
        assertEquals((Long) 0L, state1.getFilePosition());
        assertEquals(0L, state1.getPartETags().size());
        assertEquals((Long) 0L, state1.getPartSize());
        assertEquals(StorageClass.Standard.toString(), state1.getStorageClass().toString());
        assertEquals((Long) 0L, state1.getContentLength());
    }

    @Test
    public void testStateToString() {
        final String target = "UID-test1234567890#10001#1/PartETag-1,2/PartETag-2,3/PartETag-3,4/PartETag-4#20002#REDUCED_REDUNDANCY#30003#8675309";
        PutS3Object.MultipartState state2 = new PutS3Object.MultipartState();
        state2.setUploadId("UID-test1234567890");
        state2.setFilePosition(10001L);
        state2.setTimestamp(8675309L);
        for (int partNum = 1; partNum < 5; partNum++) {
            state2.addPartETag(new PartETag(partNum, "PartETag-" + partNum));
        }
        state2.setPartSize(20002L);
        state2.setStorageClass(StorageClass.ReducedRedundancy);
        state2.setContentLength(30003L);
        assertEquals(target, state2.toString());
    }

    @Test
    public void testEndpointOverride() {
        // remove leading "/" from filename to avoid duplicate separators
        final String TESTKEY = AbstractS3IT.SAMPLE_FILE_RESOURCE_NAME.substring(1);

        final PutS3Object processor = new TestablePutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);
        final ProcessContext context = runner.getProcessContext();

        runner.setProperty(PutS3Object.ENDPOINT_OVERRIDE, TEST_ENDPOINT);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.KEY, TESTKEY);

        runner.run();

        assertEquals(BUCKET_NAME, context.getProperty(PutS3Object.BUCKET).toString());
        assertEquals(TESTKEY, context.getProperty(PutS3Object.KEY).evaluateAttributeExpressions(Collections.emptyMap()).toString());
        assertEquals(TEST_ENDPOINT, context.getProperty(PutS3Object.ENDPOINT_OVERRIDE).toString());

        String s3url = ((TestablePutS3Object)processor).testable_getClient(context).getResourceUrl(BUCKET_NAME, TESTKEY);
        assertEquals(TEST_ENDPOINT + "/" + BUCKET_NAME + "/" + TESTKEY, s3url);
    }

    @Test
    public void testMultipartProperties() {
        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);
        final ProcessContext context = runner.getProcessContext();

        runner.setProperty(PutS3Object.FULL_CONTROL_USER_LIST,
                "28545acd76c35c7e91f8409b95fd1aa0c0914bfa1ac60975d9f48bc3c5e090b5");
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, TEST_PARTSIZE_STRING);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.KEY, AbstractS3IT.SAMPLE_FILE_RESOURCE_NAME);

        assertEquals(BUCKET_NAME, context.getProperty(PutS3Object.BUCKET).toString());
        assertEquals(SAMPLE_FILE_RESOURCE_NAME, context.getProperty(PutS3Object.KEY).evaluateAttributeExpressions(Collections.emptyMap()).toString());
        assertEquals(TEST_PARTSIZE_LONG.longValue(),
                context.getProperty(PutS3Object.MULTIPART_PART_SIZE).asDataSize(DataUnit.B).longValue());
    }

    @Test
    public void testLocalStatePersistence() throws IOException {
        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        final String bucket = runner.getProcessContext().getProperty(PutS3Object.BUCKET).getValue();
        final String key = runner.getProcessContext().getProperty(PutS3Object.KEY).evaluateAttributeExpressions(Collections.emptyMap()).getValue();
        final String cacheKey1 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key;
        final String cacheKey2 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-v2";
        final String cacheKey3 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-v3";

        /*
         * store 3 versions of state
         */
        PutS3Object.MultipartState state1orig = new PutS3Object.MultipartState();
        processor.persistLocalState(cacheKey1, state1orig);

        PutS3Object.MultipartState state2orig = new PutS3Object.MultipartState();
        state2orig.setUploadId("1234");
        state2orig.setContentLength(1234L);
        processor.persistLocalState(cacheKey2, state2orig);

        PutS3Object.MultipartState state3orig = new PutS3Object.MultipartState();
        state3orig.setUploadId("5678");
        state3orig.setContentLength(5678L);
        processor.persistLocalState(cacheKey3, state3orig);

        final List<MultipartUpload> uploadList = new ArrayList<>();
        final MultipartUpload upload1 = new MultipartUpload();
        upload1.setKey(key);
        upload1.setUploadId("");
        uploadList.add(upload1);
        final MultipartUpload upload2 = new MultipartUpload();
        upload2.setKey(key + "-v2");
        upload2.setUploadId("1234");
        uploadList.add(upload2);
        final MultipartUpload upload3 = new MultipartUpload();
        upload3.setKey(key + "-v3");
        upload3.setUploadId("5678");
        uploadList.add(upload3);
        final MultipartUploadListing uploadListing = new MultipartUploadListing();
        uploadListing.setMultipartUploads(uploadList);
        final MockAmazonS3Client mockClient = new MockAmazonS3Client();
        mockClient.setListing(uploadListing);

        /*
         * reload and validate stored state
         */
        final PutS3Object.MultipartState state1new = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey1);
        assertEquals("", state1new.getUploadId());
        assertEquals(0L, state1new.getFilePosition().longValue());
        assertEquals(new ArrayList<PartETag>(), state1new.getPartETags());
        assertEquals(0L, state1new.getPartSize().longValue());
        assertEquals(StorageClass.fromValue(StorageClass.Standard.toString()), state1new.getStorageClass());
        assertEquals(0L, state1new.getContentLength().longValue());

        final PutS3Object.MultipartState state2new = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey2);
        assertEquals("1234", state2new.getUploadId());
        assertEquals(0L, state2new.getFilePosition().longValue());
        assertEquals(new ArrayList<PartETag>(), state2new.getPartETags());
        assertEquals(0L, state2new.getPartSize().longValue());
        assertEquals(StorageClass.fromValue(StorageClass.Standard.toString()), state2new.getStorageClass());
        assertEquals(1234L, state2new.getContentLength().longValue());

        final PutS3Object.MultipartState state3new = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey3);
        assertEquals("5678", state3new.getUploadId());
        assertEquals(0L, state3new.getFilePosition().longValue());
        assertEquals(new ArrayList<PartETag>(), state3new.getPartETags());
        assertEquals(0L, state3new.getPartSize().longValue());
        assertEquals(StorageClass.fromValue(StorageClass.Standard.toString()), state3new.getStorageClass());
        assertEquals(5678L, state3new.getContentLength().longValue());
    }

    @Test
    public void testStatePersistsETags() throws IOException {
        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        final String bucket = runner.getProcessContext().getProperty(PutS3Object.BUCKET).getValue();
        final String key = runner.getProcessContext().getProperty(PutS3Object.KEY).evaluateAttributeExpressions(Collections.emptyMap()).getValue();
        final String cacheKey1 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-bv1";
        final String cacheKey2 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-bv2";
        final String cacheKey3 = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-bv3";

        /*
         * store 3 versions of state
         */
        PutS3Object.MultipartState state1orig = new PutS3Object.MultipartState();
        processor.persistLocalState(cacheKey1, state1orig);

        PutS3Object.MultipartState state2orig = new PutS3Object.MultipartState();
        state2orig.setUploadId("1234");
        state2orig.setContentLength(1234L);
        processor.persistLocalState(cacheKey2, state2orig);

        PutS3Object.MultipartState state3orig = new PutS3Object.MultipartState();
        state3orig.setUploadId("5678");
        state3orig.setContentLength(5678L);
        processor.persistLocalState(cacheKey3, state3orig);

        /*
         * persist state to caches so that
         *      1. v2 has 2 and then 4 tags
         *      2. v3 has 4 and then 2 tags
         */
        state2orig.getPartETags().add(new PartETag(1, "state 2 tag one"));
        state2orig.getPartETags().add(new PartETag(2, "state 2 tag two"));
        processor.persistLocalState(cacheKey2, state2orig);
        state2orig.getPartETags().add(new PartETag(3, "state 2 tag three"));
        state2orig.getPartETags().add(new PartETag(4, "state 2 tag four"));
        processor.persistLocalState(cacheKey2, state2orig);

        state3orig.getPartETags().add(new PartETag(1, "state 3 tag one"));
        state3orig.getPartETags().add(new PartETag(2, "state 3 tag two"));
        state3orig.getPartETags().add(new PartETag(3, "state 3 tag three"));
        state3orig.getPartETags().add(new PartETag(4, "state 3 tag four"));
        processor.persistLocalState(cacheKey3, state3orig);
        state3orig.getPartETags().remove(state3orig.getPartETags().size() - 1);
        state3orig.getPartETags().remove(state3orig.getPartETags().size() - 1);
        processor.persistLocalState(cacheKey3, state3orig);

        final List<MultipartUpload> uploadList = new ArrayList<>();
        final MultipartUpload upload1 = new MultipartUpload();
        upload1.setKey(key + "-bv2");
        upload1.setUploadId("1234");
        uploadList.add(upload1);
        final MultipartUpload upload2 = new MultipartUpload();
        upload2.setKey(key + "-bv3");
        upload2.setUploadId("5678");
        uploadList.add(upload2);
        final MultipartUploadListing uploadListing = new MultipartUploadListing();
        uploadListing.setMultipartUploads(uploadList);
        final MockAmazonS3Client mockClient = new MockAmazonS3Client();
        mockClient.setListing(uploadListing);

        /*
         * load state and validate that
         *     1. v2 restore shows 4 tags
         *     2. v3 restore shows 2 tags
         */
        final PutS3Object.MultipartState state2new = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey2);
        assertEquals("1234", state2new.getUploadId());
        assertEquals(4, state2new.getPartETags().size());

        final PutS3Object.MultipartState state3new = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey3);
        assertEquals("5678", state3new.getUploadId());
        assertEquals(2, state3new.getPartETags().size());
    }

    @Test
    public void testStateRemove() throws IOException {
        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        final String bucket = runner.getProcessContext().getProperty(PutS3Object.BUCKET).getValue();
        final String key = runner.getProcessContext().getProperty(PutS3Object.KEY).evaluateAttributeExpressions(Collections.emptyMap()).getValue();
        final String cacheKey = runner.getProcessor().getIdentifier() + "/" + bucket + "/" + key + "-sr";

        final List<MultipartUpload> uploadList = new ArrayList<>();
        final MultipartUpload upload1 = new MultipartUpload();
        upload1.setKey(key);
        upload1.setUploadId("1234");
        uploadList.add(upload1);
        final MultipartUploadListing uploadListing = new MultipartUploadListing();
        uploadListing.setMultipartUploads(uploadList);
        final MockAmazonS3Client mockClient = new MockAmazonS3Client();
        mockClient.setListing(uploadListing);

        /*
         * store state, retrieve and validate, remove and validate
         */
        PutS3Object.MultipartState stateOrig = new PutS3Object.MultipartState();
        stateOrig.setUploadId("1234");
        stateOrig.setContentLength(1234L);
        processor.persistLocalState(cacheKey, stateOrig);

        PutS3Object.MultipartState state1 = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey);
        assertEquals("1234", state1.getUploadId());
        assertEquals(1234L, state1.getContentLength().longValue());

        processor.persistLocalState(cacheKey, null);
        PutS3Object.MultipartState state2 = processor.getLocalStateIfInS3(mockClient, bucket, cacheKey);
        assertNull(state2);
    }

    @Test
    public void testMultipartSmallerThanMinimum() throws IOException {
        final String FILE1_NAME = "file1";

        final byte[] megabyte = new byte[1024 * 1024];
        final Path tempFile = Files.createTempFile("s3mulitpart", "tmp");
        final FileOutputStream tempOut = new FileOutputStream(tempFile.toFile());
        long tempByteCount = 0;
        for (int i = 0; i < 5; i++) {
            tempOut.write(megabyte);
            tempByteCount += megabyte.length;
        }
        tempOut.close();
        System.out.println("file size: " + tempByteCount);
        assertTrue(tempByteCount < S3_MINIMUM_PART_SIZE);

        assertTrue(megabyte.length < S3_MINIMUM_PART_SIZE);
        assertTrue(TEST_PARTSIZE_LONG >= S3_MINIMUM_PART_SIZE && TEST_PARTSIZE_LONG <= S3_MAXIMUM_OBJECT_SIZE);

        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, TEST_PARTSIZE_STRING);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.FILENAME.key(), FILE1_NAME);
        runner.enqueue(new FileInputStream(tempFile.toFile()), attributes);

        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);
        final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, successFiles.size());
        final List<MockFlowFile> failureFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE);
        assertEquals(0, failureFiles.size());
        MockFlowFile ff1 = successFiles.get(0);
        assertEquals(PutS3Object.S3_API_METHOD_PUTOBJECT, ff1.getAttribute(PutS3Object.S3_API_METHOD_ATTR_KEY));
        assertEquals(FILE1_NAME, ff1.getAttribute(CoreAttributes.FILENAME.key()));
        assertEquals(BUCKET_NAME, ff1.getAttribute(PutS3Object.S3_BUCKET_KEY));
        assertEquals(FILE1_NAME, ff1.getAttribute(PutS3Object.S3_OBJECT_KEY));
        assertTrue(reS3ETag.matcher(ff1.getAttribute(PutS3Object.S3_ETAG_ATTR_KEY)).matches());
        assertEquals(tempByteCount, ff1.getSize());
    }

    @Test
    public void testMultipartBetweenMinimumAndMaximum() throws IOException {
        final String FILE1_NAME = "file1";

        final byte[] megabyte = new byte[1024 * 1024];
        final Path tempFile = Files.createTempFile("s3mulitpart", "tmp");
        final FileOutputStream tempOut = new FileOutputStream(tempFile.toFile());
        long tempByteCount = 0;
        while (tempByteCount < TEST_PARTSIZE_LONG + 1) {
            tempOut.write(megabyte);
            tempByteCount += megabyte.length;
        }
        tempOut.close();
        System.out.println("file size: " + tempByteCount);
        assertTrue(tempByteCount > S3_MINIMUM_PART_SIZE && tempByteCount < S3_MAXIMUM_OBJECT_SIZE);
        assertTrue(tempByteCount > TEST_PARTSIZE_LONG);

        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.MULTIPART_THRESHOLD, TEST_PARTSIZE_STRING);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, TEST_PARTSIZE_STRING);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.FILENAME.key(), FILE1_NAME);
        runner.enqueue(new FileInputStream(tempFile.toFile()), attributes);

        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);
        final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, successFiles.size());
        final List<MockFlowFile> failureFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE);
        assertEquals(0, failureFiles.size());
        MockFlowFile ff1 = successFiles.get(0);
        assertEquals(PutS3Object.S3_API_METHOD_MULTIPARTUPLOAD, ff1.getAttribute(PutS3Object.S3_API_METHOD_ATTR_KEY));
        assertEquals(FILE1_NAME, ff1.getAttribute(CoreAttributes.FILENAME.key()));
        assertEquals(BUCKET_NAME, ff1.getAttribute(PutS3Object.S3_BUCKET_KEY));
        assertEquals(FILE1_NAME, ff1.getAttribute(PutS3Object.S3_OBJECT_KEY));
            assertTrue(reS3ETag.matcher(ff1.getAttribute(PutS3Object.S3_ETAG_ATTR_KEY)).matches());
        assertEquals(tempByteCount, ff1.getSize());
    }

    @Test
    public void testMultipartLargerThanObjectMaximum() throws IOException {
        final String FILE1_NAME = "file1";

        final byte[] megabyte = new byte[1024 * 1024];
        final Path tempFile = Files.createTempFile("s3multipart", "tmp");
        final FileOutputStream tempOut = new FileOutputStream(tempFile.toFile());
        for (int i = 0; i < (S3_MAXIMUM_OBJECT_SIZE / 1024 / 1024 + 1); i++) {
            tempOut.write(megabyte);
        }
        tempOut.close();

        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, TEST_PARTSIZE_STRING);

        Map<String, String> attributes = new HashMap<>();
        attributes.put(CoreAttributes.FILENAME.key(), FILE1_NAME);
        runner.enqueue(new FileInputStream(tempFile.toFile()), attributes);

        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);
        final List<MockFlowFile> successFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, successFiles.size());
        final List<MockFlowFile> failureFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE);
        assertEquals(0, failureFiles.size());
        MockFlowFile ff1 = successFiles.get(0);
        assertEquals(FILE1_NAME, ff1.getAttribute(CoreAttributes.FILENAME.key()));
        assertEquals(BUCKET_NAME, ff1.getAttribute(PutS3Object.S3_BUCKET_KEY));
        assertEquals(FILE1_NAME, ff1.getAttribute(PutS3Object.S3_OBJECT_KEY));
        assertTrue(reS3ETag.matcher(ff1.getAttribute(PutS3Object.S3_ETAG_ATTR_KEY)).matches());
        assertTrue(ff1.getSize() > S3_MAXIMUM_OBJECT_SIZE);
    }

    @Test
    public void testS3MultipartAgeoff() throws InterruptedException, IOException {
        final PutS3Object processor = new PutS3Object();
        final TestRunner runner = TestRunners.newTestRunner(processor);
        final ProcessContext context = runner.getProcessContext();

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);

        // set check interval and age off to minimum values
        runner.setProperty(PutS3Object.MULTIPART_S3_AGEOFF_INTERVAL, "1 milli");
        runner.setProperty(PutS3Object.MULTIPART_S3_MAX_AGE, "1 milli");

        // create some dummy uploads
        for (int i = 0; i < 3; i++) {
            final InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(
                    BUCKET_NAME, "file" + i + ".txt");
            assertDoesNotThrow(() -> client.initiateMultipartUpload(initiateRequest));
        }

        // Age off is time dependent, so test has some timing constraints.  This
        // sleep() delays long enough to satisfy interval and age intervals.
        Thread.sleep(2000L);

        // System millis are used for timing, but it is incremented on each
        // call to circumvent what appears to be caching in the AWS library.
        // The increments are 1000 millis because AWS returns upload
        // initiation times in whole seconds.
        long now = System.currentTimeMillis();

        MultipartUploadListing uploadList = processor.getS3AgeoffListAndAgeoffLocalState(context, client, now, BUCKET_NAME);
        assertEquals(3, uploadList.getMultipartUploads().size());

        MultipartUpload upload0 = uploadList.getMultipartUploads().get(0);
        processor.abortS3MultipartUpload(client, BUCKET_NAME, upload0);

        uploadList = processor.getS3AgeoffListAndAgeoffLocalState(context, client, now+1000, BUCKET_NAME);
        assertEquals(2, uploadList.getMultipartUploads().size());

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test-upload.txt");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);
        runner.run();

        uploadList = processor.getS3AgeoffListAndAgeoffLocalState(context, client, now+2000, BUCKET_NAME);
        assertEquals(0, uploadList.getMultipartUploads().size());
    }

    @Test
    public void testObjectTags() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.OBJECT_TAGS_PREFIX, "tagS3");
        runner.setProperty(PutS3Object.REMOVE_TAG_PREFIX, "true");

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "tag-test.txt");
        attrs.put("tagS3PII", "true");
        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME), attrs);

        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);

        GetObjectTaggingResult result = client.getObjectTagging(new GetObjectTaggingRequest(BUCKET_NAME, "tag-test.txt"));
        List<Tag> objectTags = result.getTagSet();

        for (Tag tag : objectTags) {
            System.out.println("Tag Key : " + tag.getKey() + ", Tag Value : " + tag.getValue());
        }

        assertEquals(1, objectTags.size());
        assertEquals("PII", objectTags.get(0).getKey());
        assertEquals("true", objectTags.get(0).getValue());
    }

    @Test
    public void testEncryptionServiceWithServerSideS3EncryptionStrategyUsingSingleUpload() throws IOException, InitializationException {
        byte[] smallData = Files.readAllBytes(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        testEncryptionServiceWithServerSideS3EncryptionStrategy(smallData);
    }

    @Test
    public void testEncryptionServiceWithServerSideS3EncryptionStrategyUsingMultipartUpload() throws IOException, InitializationException {
        byte[] largeData = new byte[51 * 1024 * 1024];
        testEncryptionServiceWithServerSideS3EncryptionStrategy(largeData);
    }

    private void testEncryptionServiceWithServerSideS3EncryptionStrategy(byte[] data) throws IOException, InitializationException {
        TestRunner runner = createPutEncryptionTestRunner(AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3, null);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test.txt");
        runner.enqueue(data, attrs);
        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, flowFiles.size());
        assertEquals(0, runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE).size());
        MockFlowFile putSuccess = flowFiles.get(0);
        assertEquals(AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3, putSuccess.getAttribute(PutS3Object.S3_ENCRYPTION_STRATEGY));

        MockFlowFile flowFile = fetchEncryptedFlowFile(attrs, AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3, null);
        flowFile.assertContentEquals(data);
        flowFile.assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
        flowFile.assertAttributeEquals(PutS3Object.S3_ENCRYPTION_STRATEGY, AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3);
    }

    @Test
    public void testEncryptionServiceWithServerSideKMSEncryptionStrategyUsingSingleUpload() throws IOException, InitializationException {
        byte[] smallData = Files.readAllBytes(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        testEncryptionServiceWithServerSideKMSEncryptionStrategy(smallData);
    }

    @Test
    public void testEncryptionServiceWithServerSideKMSEncryptionStrategyUsingMultipartUpload() throws IOException, InitializationException {
        byte[] largeData = new byte[51 * 1024 * 1024];
        testEncryptionServiceWithServerSideKMSEncryptionStrategy(largeData);
    }

    private void testEncryptionServiceWithServerSideKMSEncryptionStrategy(byte[] data) throws IOException, InitializationException {
        TestRunner runner = createPutEncryptionTestRunner(AmazonS3EncryptionService.STRATEGY_NAME_SSE_KMS, kmsKeyId);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test.txt");
        runner.enqueue(data, attrs);
        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, flowFiles.size());
        assertEquals(0, runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE).size());
        MockFlowFile putSuccess = flowFiles.get(0);
        assertEquals(AmazonS3EncryptionService.STRATEGY_NAME_SSE_KMS, putSuccess.getAttribute(PutS3Object.S3_ENCRYPTION_STRATEGY));

        MockFlowFile flowFile = fetchEncryptedFlowFile(attrs, AmazonS3EncryptionService.STRATEGY_NAME_SSE_KMS, kmsKeyId);
        flowFile.assertContentEquals(data);
        flowFile.assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, "aws:kms");
        flowFile.assertAttributeEquals(PutS3Object.S3_ENCRYPTION_STRATEGY, AmazonS3EncryptionService.STRATEGY_NAME_SSE_KMS);
    }

    @Test
    public void testEncryptionServiceWithServerSideCEncryptionStrategyUsingSingleUpload() throws IOException, InitializationException {
        byte[] smallData = Files.readAllBytes(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        testEncryptionServiceWithServerSideCEncryptionStrategy(smallData);
    }

    @Test
    public void testEncryptionServiceWithServerSideCEncryptionStrategyUsingMultipartUpload() throws IOException, InitializationException {
        byte[] largeData = new byte[51 * 1024 * 1024];
        testEncryptionServiceWithServerSideCEncryptionStrategy(largeData);
    }

    private void testEncryptionServiceWithServerSideCEncryptionStrategy(byte[] data) throws IOException, InitializationException {
        TestRunner runner = createPutEncryptionTestRunner(AmazonS3EncryptionService.STRATEGY_NAME_SSE_C, randomKeyMaterial);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test.txt");
        runner.enqueue(data, attrs);
        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, flowFiles.size());
        assertEquals(0, runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE).size());
        MockFlowFile putSuccess = flowFiles.get(0);
        assertEquals(AmazonS3EncryptionService.STRATEGY_NAME_SSE_C, putSuccess.getAttribute(PutS3Object.S3_ENCRYPTION_STRATEGY));

        MockFlowFile flowFile = fetchEncryptedFlowFile(attrs, AmazonS3EncryptionService.STRATEGY_NAME_SSE_C, randomKeyMaterial);
        flowFile.assertContentEquals(data);
        // successful fetch does not indicate type of original encryption:
        flowFile.assertAttributeEquals(PutS3Object.S3_SSE_ALGORITHM, null);
        // but it does indicate it via our specific attribute:
        flowFile.assertAttributeEquals(PutS3Object.S3_ENCRYPTION_STRATEGY, AmazonS3EncryptionService.STRATEGY_NAME_SSE_C);
    }

    @Test
    public void testEncryptionServiceWithClientSideKMSEncryptionStrategyUsingSingleUpload() throws IOException, InitializationException {
        byte[] smallData = Files.readAllBytes(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        testEncryptionServiceWithClientSideKMSEncryptionStrategy(smallData);
    }

    @Test
    public void testEncryptionServiceWithClientSideKMSEncryptionStrategyUsingMultipartUpload() throws IOException, InitializationException {
        byte[] largeData = new byte[51 * 1024 * 1024];
        testEncryptionServiceWithClientSideKMSEncryptionStrategy(largeData);
    }

    private void testEncryptionServiceWithClientSideKMSEncryptionStrategy(byte[] data) throws InitializationException, IOException {
        TestRunner runner = createPutEncryptionTestRunner(AmazonS3EncryptionService.STRATEGY_NAME_CSE_KMS, kmsKeyId);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test.txt");
        runner.enqueue(data, attrs);
        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, flowFiles.size());
        assertEquals(0, runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE).size());
        MockFlowFile putSuccess = flowFiles.get(0);
        assertEquals(AmazonS3EncryptionService.STRATEGY_NAME_CSE_KMS, putSuccess.getAttribute(PutS3Object.S3_ENCRYPTION_STRATEGY));

        MockFlowFile flowFile = fetchEncryptedFlowFile(attrs, AmazonS3EncryptionService.STRATEGY_NAME_CSE_KMS, kmsKeyId);
        flowFile.assertContentEquals(data);
        flowFile.assertAttributeEquals("x-amz-wrap-alg", "kms");
        flowFile.assertAttributeEquals(PutS3Object.S3_ENCRYPTION_STRATEGY, AmazonS3EncryptionService.STRATEGY_NAME_CSE_KMS);
    }

    @Test
    public void testEncryptionServiceWithClientSideCEncryptionStrategyUsingSingleUpload() throws InitializationException, IOException {
        byte[] smallData = Files.readAllBytes(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        testEncryptionServiceWithClientSideCEncryptionStrategy(smallData);
    }

    @Test
    public void testEncryptionServiceWithClientSideCEncryptionStrategyUsingMultipartUpload() throws IOException, InitializationException {
        byte[] largeData = new byte[51 * 1024 * 1024];
        testEncryptionServiceWithClientSideCEncryptionStrategy(largeData);
    }

    private void testEncryptionServiceWithClientSideCEncryptionStrategy(byte[] data) throws InitializationException, IOException {
        TestRunner runner = createPutEncryptionTestRunner(AmazonS3EncryptionService.STRATEGY_NAME_CSE_C, randomKeyMaterial);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "test.txt");
        runner.enqueue(data, attrs);
        runner.assertValid();
        runner.run();
        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS);

        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(PutS3Object.REL_SUCCESS);
        assertEquals(1, flowFiles.size());
        assertEquals(0, runner.getFlowFilesForRelationship(PutS3Object.REL_FAILURE).size());
        MockFlowFile putSuccess = flowFiles.get(0);
        assertEquals(AmazonS3EncryptionService.STRATEGY_NAME_CSE_C, putSuccess.getAttribute(PutS3Object.S3_ENCRYPTION_STRATEGY));

        MockFlowFile flowFile = fetchEncryptedFlowFile(attrs, AmazonS3EncryptionService.STRATEGY_NAME_CSE_C, randomKeyMaterial);
        flowFile.assertAttributeEquals(PutS3Object.S3_ENCRYPTION_STRATEGY, AmazonS3EncryptionService.STRATEGY_NAME_CSE_C);
        flowFile.assertContentEquals(data);

        flowFile.assertAttributeExists("x-amz-key");
        flowFile.assertAttributeNotEquals("x-amz-key", "");

        flowFile.assertAttributeExists("x-amz-iv");
        flowFile.assertAttributeNotEquals("x-amz-iv", "");
    }

    private static TestRunner createPutEncryptionTestRunner(String strategyName, String keyIdOrMaterial) throws InitializationException {
        TestRunner runner = createEncryptionTestRunner(new PutS3Object(), strategyName, keyIdOrMaterial);

        runner.setProperty(PutS3Object.MULTIPART_THRESHOLD, "50 MB");
        runner.setProperty(PutS3Object.MULTIPART_PART_SIZE, "50 MB");

        return runner;
    }

    private static TestRunner createFetchEncryptionTestRunner(String strategyName, String keyIdOrMaterial) throws InitializationException {
        if (strategyName.equals(AmazonS3EncryptionService.STRATEGY_NAME_SSE_S3) || strategyName.equals(AmazonS3EncryptionService.STRATEGY_NAME_SSE_KMS)) {
            strategyName = null;
        }

        return createEncryptionTestRunner(new FetchS3Object(), strategyName, keyIdOrMaterial);
    }

    private static MockFlowFile fetchEncryptedFlowFile(Map<String, String> attributes, String strategyName, String keyIdOrMaterial) throws InitializationException {
        final TestRunner runner = createFetchEncryptionTestRunner(strategyName, keyIdOrMaterial);
        runner.enqueue(new byte[0], attributes);
        runner.run(1);
        runner.assertAllFlowFilesTransferred(FetchS3Object.REL_SUCCESS, 1);
        List<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(FetchS3Object.REL_SUCCESS);
        return flowFiles.get(0);
    }

    private static TestRunner createEncryptionTestRunner(Processor processor, String strategyName, String keyIdOrMaterial) throws InitializationException {
        final TestRunner runner = TestRunners.newTestRunner(processor);
        final ConfigurationContext context = mock(ConfigurationContext.class);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);

        if (strategyName != null) {
            final StandardS3EncryptionService service = new StandardS3EncryptionService();
            runner.addControllerService(PutS3Object.ENCRYPTION_SERVICE.getName(), service);
            runner.setProperty(PutS3Object.ENCRYPTION_SERVICE, service.getIdentifier());

            runner.setProperty(service, StandardS3EncryptionService.ENCRYPTION_STRATEGY, strategyName);
            runner.setProperty(service, StandardS3EncryptionService.ENCRYPTION_VALUE, keyIdOrMaterial);
            runner.setProperty(service, StandardS3EncryptionService.KMS_REGION, REGION);

            when(context.getProperty(StandardS3EncryptionService.ENCRYPTION_STRATEGY)).thenReturn(new MockPropertyValue(strategyName));
            when(context.getProperty(StandardS3EncryptionService.ENCRYPTION_VALUE)).thenReturn(new MockPropertyValue(keyIdOrMaterial));
            when(context.getProperty(StandardS3EncryptionService.KMS_REGION)).thenReturn(new MockPropertyValue(REGION));

            service.onConfigured(context);
            runner.enableControllerService(service);
        }

        return runner;
    }

    private class MockAmazonS3Client extends AmazonS3Client {
        MultipartUploadListing listing;
        public void setListing(MultipartUploadListing newlisting) {
            listing = newlisting;
        }

        @Override
        public MultipartUploadListing listMultipartUploads(ListMultipartUploadsRequest listMultipartUploadsRequest)
                throws AmazonClientException {
            return listing;
        }
    }

    public class TestablePutS3Object extends PutS3Object {
        public AmazonS3Client testable_getClient(ProcessContext context) {
            return this.getClient(context);
        }
    }

    @Test
    public void testChunkedEncodingDisabled() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.USE_CHUNKED_ENCODING, "false");

        executeSimplePutTest(runner);
    }

    @Test
    public void testPathStyleAccessEnabled() throws IOException {
        TestRunner runner = initTestRunner();

        runner.setProperty(PutS3Object.USE_PATH_STYLE_ACCESS, "true");

        executeSimplePutTest(runner);
    }

    private TestRunner initTestRunner() {
        TestRunner runner = TestRunners.newTestRunner(PutS3Object.class);

        runner.setProperty(PutS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(PutS3Object.BUCKET, BUCKET_NAME);

        return runner;
    }

    private void executeSimplePutTest(TestRunner runner) throws IOException {
        runner.assertValid();

        runner.enqueue(getResourcePath(SAMPLE_FILE_RESOURCE_NAME));
        runner.run();

        runner.assertAllFlowFilesTransferred(PutS3Object.REL_SUCCESS, 1);
    }
}
