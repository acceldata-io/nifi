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

import com.amazonaws.services.s3.model.GetObjectTaggingRequest;
import com.amazonaws.services.s3.model.GetObjectTaggingResult;
import com.amazonaws.services.s3.model.Tag;
import org.apache.nifi.processors.aws.util.RegionUtilV1;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Provides integration level testing with actual AWS S3 resources for {@link TagS3Object} and requires additional
 * configuration and resources to work.
 */
public class ITTagS3Object extends AbstractS3IT {

    @Test
    public void testSimpleTag() {
        String objectKey = "test-file";
        String tagKey = "nifi-key";
        String tagValue = "nifi-val";

        // put file in s3
        putTestFile(objectKey, getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME));

        // Set up processor
        final TestRunner runner = TestRunners.newTestRunner(new TagS3Object());
        runner.setProperty(TagS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(TagS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(TagS3Object.TAG_KEY, tagKey);
        runner.setProperty(TagS3Object.TAG_VALUE, tagValue);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", objectKey);
        runner.enqueue(new byte[0], attrs);

        // tag file
        runner.run(1);

        // Verify processor succeeds
        runner.assertAllFlowFilesTransferred(TagS3Object.REL_SUCCESS, 1);

        // Verify tag exists on S3 object
        GetObjectTaggingResult res = client.getObjectTagging(new GetObjectTaggingRequest(BUCKET_NAME, objectKey));
        assertTrue(res.getTagSet().contains(new Tag(tagKey, tagValue)), "Expected tag not found on S3 object");
    }

    @Test
    public void testAppendTag() {
        String objectKey = "test-file";
        String tagKey = "nifi-key";
        String tagValue = "nifi-val";

        Tag existingTag = new Tag("oldkey", "oldvalue");

        // put file in s3
        putFileWithObjectTag(objectKey, getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME), Arrays.asList(existingTag));

        // Set up processor
        final TestRunner runner = TestRunners.newTestRunner(new TagS3Object());
        runner.setProperty(TagS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(TagS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(TagS3Object.TAG_KEY, tagKey);
        runner.setProperty(TagS3Object.TAG_VALUE, tagValue);

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", objectKey);
        runner.enqueue(new byte[0], attrs);

        // tag file
        runner.run(1);

        // Verify processor succeeds
        runner.assertAllFlowFilesTransferred(TagS3Object.REL_SUCCESS, 1);

        // Verify new tag and existing exist on S3 object
        GetObjectTaggingResult res = client.getObjectTagging(new GetObjectTaggingRequest(BUCKET_NAME, objectKey));
        assertTrue(res.getTagSet().contains(new Tag(tagKey, tagValue)), "Expected new tag not found on S3 object");
        assertTrue(res.getTagSet().contains(existingTag), "Expected existing tag not found on S3 object");
    }

    @Test
    public void testReplaceTags() {
        String objectKey = "test-file";
        String tagKey = "nifi-key";
        String tagValue = "nifi-val";

        Tag existingTag = new Tag("s3.tag.oldkey", "oldvalue");

        // put file in s3
        putFileWithObjectTag(objectKey, getFileFromResourceName(SAMPLE_FILE_RESOURCE_NAME), Arrays.asList(existingTag));

        // Set up processor
        final TestRunner runner = TestRunners.newTestRunner(new TagS3Object());
        runner.setProperty(TagS3Object.CREDENTIALS_FILE, CREDENTIALS_FILE);
        runner.setProperty(RegionUtilV1.S3_REGION, REGION);
        runner.setProperty(TagS3Object.BUCKET, BUCKET_NAME);
        runner.setProperty(TagS3Object.TAG_KEY, tagKey);
        runner.setProperty(TagS3Object.TAG_VALUE, tagValue);
        runner.setProperty(TagS3Object.APPEND_TAG, "false");

        final Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", objectKey);
        attrs.put("s3.tag."+existingTag.getKey(), existingTag.getValue());
        runner.enqueue(new byte[0], attrs);

        // tag file
        runner.run(1);

        // Verify processor succeeds
        runner.assertAllFlowFilesTransferred(TagS3Object.REL_SUCCESS, 1);

        // Verify flowfile attributes match s3 tags
        MockFlowFile flowFiles = runner.getFlowFilesForRelationship(TagS3Object.REL_SUCCESS).get(0);
        flowFiles.assertAttributeNotExists(existingTag.getKey());
        flowFiles.assertAttributeEquals("s3.tag."+tagKey, tagValue);

        // Verify new tag exists on S3 object and prior tag removed
        GetObjectTaggingResult res = client.getObjectTagging(new GetObjectTaggingRequest(BUCKET_NAME, objectKey));
        assertTrue(res.getTagSet().contains(new Tag(tagKey, tagValue)), "Expected new tag not found on S3 object");
        assertFalse(res.getTagSet().contains(existingTag), "Existing tag not replaced on S3 object");
    }
}

