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

import com.amazonaws.SdkClientException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.context.PropertyContext;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.fileresource.service.api.FileResource;
import org.apache.nifi.fileresource.service.api.FileResourceService;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.aws.credentials.provider.service.AWSCredentialsProviderService;
import org.apache.nifi.processors.aws.util.RegionUtilV1;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import static org.apache.nifi.processors.aws.AbstractAWSCredentialsProviderProcessor.AWS_CREDENTIALS_PROVIDER_SERVICE;
import static org.apache.nifi.processors.aws.util.RegionUtilV1.resolveS3Region;
import static org.apache.nifi.util.StringUtils.isBlank;

@Tags({"Amazon", "S3", "AWS", "file", "resource"})
@SeeAlso({FetchS3Object.class})
@CapabilityDescription("Provides an Amazon Web Services (AWS) S3 file resource for other components.")
public class S3FileResourceService extends AbstractControllerService implements FileResourceService {

    public static final PropertyDescriptor BUCKET = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AbstractS3Processor.BUCKET)
            .build();

    public static final PropertyDescriptor KEY = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(AbstractS3Processor.KEY)
            .build();

    public static final PropertyDescriptor S3_REGION = new PropertyDescriptor.Builder()
            .fromPropertyDescriptor(RegionUtilV1.S3_REGION)
            .build();

    public static final PropertyDescriptor ENDPOINT_OVERRIDE = new PropertyDescriptor.Builder()
            .name("Endpoint Override URL")
            .description("Endpoint URL to use instead of the AWS default including scheme, host, port, and path. " +
                         "The AWS libraries select an endpoint URL based on the AWS region, but this property overrides " +
                         "the selected endpoint URL, allowing use with other S3-compatible endpoints.")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .required(false)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    public static final PropertyDescriptor USE_PATH_STYLE_ACCESS = new PropertyDescriptor.Builder()
            .name("use-path-style-access")
            .displayName("Use Path Style Access")
            .description("Path-style access can be enforced by setting this property to true. Set it to true if your endpoint does not support " +
                         "virtual-hosted-style requests, only path-style requests.")
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = Arrays.asList(
            BUCKET,
            KEY,
            S3_REGION,
            ENDPOINT_OVERRIDE,
            AWS_CREDENTIALS_PROVIDER_SERVICE,
            USE_PATH_STYLE_ACCESS);

    private final Cache<String, AmazonS3> clientCache = Caffeine.newBuilder().build();

    private volatile PropertyContext context;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        this.context = context;
    }

    @OnDisabled
    public void onDisabled() {
        this.context = null;
        clientCache.asMap().values().forEach(AmazonS3::shutdown);
        clientCache.invalidateAll();
        clientCache.cleanUp();
    }

    @Override
    public FileResource getFileResource(Map<String, String> attributes) {
        final AWSCredentialsProviderService awsCredentialsProviderService = context.getProperty(AWS_CREDENTIALS_PROVIDER_SERVICE)
                .asControllerService(AWSCredentialsProviderService.class);
        final AmazonS3 client = getS3Client(attributes, awsCredentialsProviderService.getCredentialsProvider());

        try {
            return fetchObject(client, attributes);
        } catch (final ProcessException | SdkClientException e) {
            throw new ProcessException("Failed to fetch s3 object", e);
        }
    }

    /**
     * Fetches s3 object from the provided bucket and returns it as FileResource
     *
     * @param client amazon s3 client
     * @param attributes configuration attributes
     * @return fetched s3 object as FileResource
     * @throws ProcessException if the object 'bucketName/key' does not exist
     */
    private FileResource fetchObject(final AmazonS3 client, final Map<String, String> attributes) throws ProcessException,
            SdkClientException {
        final String bucketName = context.getProperty(BUCKET).evaluateAttributeExpressions(attributes).getValue();
        final String key = context.getProperty(KEY).evaluateAttributeExpressions(attributes).getValue();

        if (isBlank(bucketName) || isBlank(key)) {
            throw new ProcessException("Bucket name or key value is missing");
        }

        if (!client.doesObjectExist(bucketName, key)) {
            throw new ProcessException(String.format("Object '%s/%s' does not exist in s3", bucketName, key));
        }

        final S3Object object = client.getObject(bucketName, key);
        return new FileResource(object.getObjectContent(), object.getObjectMetadata().getContentLength());
    }

    protected AmazonS3 getS3Client(Map<String, String> attributes, AWSCredentialsProvider credentialsProvider) {
        final Region region = resolveS3Region(context, attributes);
        String urlStr = StringUtils.trimToEmpty(context.getProperty(ENDPOINT_OVERRIDE).evaluateAttributeExpressions().getValue());

        // Create a cache key based on the region and the endpoint URL
        String cacheKey = (urlStr.isEmpty() ? region.getName() : region.getName() + "::" + urlStr);

        return clientCache.get(cacheKey, ignored -> {
            AmazonS3ClientBuilder builder = AmazonS3Client.builder()
                    .withCredentials(credentialsProvider);

            if (!urlStr.isEmpty()) {
                builder.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(urlStr,
                        region.getName()));
                builder.setPathStyleAccessEnabled(context.getProperty(USE_PATH_STYLE_ACCESS).asBoolean());
            } else {
                builder.withRegion(region.getName());
            }

            return builder.build();
        });
    }
}