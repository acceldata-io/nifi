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
package org.apache.nifi.processors.standard;

import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.resource.ResourceCardinality;
import org.apache.nifi.components.resource.ResourceType;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.file.monitor.LastModifiedMonitor;
import org.apache.nifi.util.file.monitor.SynchronousFileWatcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@EventDriven
@SideEffectFree
@SupportsBatching
@InputRequirement(Requirement.INPUT_REQUIRED)
@Tags({"scan", "attributes", "search", "lookup", "find", "text"})
@CapabilityDescription("Scans the specified attributes of FlowFiles, checking to see if any of their values are "
        + "present within the specified dictionary of terms")
public class ScanAttribute extends AbstractProcessor {

    public static final String MATCH_CRITERIA_ALL = "All Must Match";
    public static final String MATCH_CRITERIA_ANY = "At Least 1 Must Match";

    public static final PropertyDescriptor MATCHING_CRITERIA = new PropertyDescriptor.Builder()
            .name("Match Criteria")
            .description("If set to All Must Match, then FlowFiles will be routed to 'matched' only if all specified "
                    + "attributes' values are found in the dictionary. If set to At Least 1 Must Match, FlowFiles will "
                    + "be routed to 'matched' if any attribute specified is found in the dictionary")
            .required(true)
            .allowableValues(MATCH_CRITERIA_ANY, MATCH_CRITERIA_ALL)
            .defaultValue(MATCH_CRITERIA_ANY)
            .build();
    public static final PropertyDescriptor ATTRIBUTE_PATTERN = new PropertyDescriptor.Builder()
            .name("Attribute Pattern")
            .description("Regular Expression that specifies the names of attributes whose values will be matched against the terms in the dictionary")
            .required(true)
            .addValidator(StandardValidators.REGULAR_EXPRESSION_VALIDATOR)
            .defaultValue(".*")
            .build();
    public static final PropertyDescriptor DICTIONARY_FILE = new PropertyDescriptor.Builder()
            .name("Dictionary File")
            .description("A new-line-delimited text file that includes the terms that should trigger a match. Empty lines are ignored.  The contents of "
                    + "the text file are loaded into memory when the processor is scheduled and reloaded when the contents are modified.")
            .required(true)
            .identifiesExternalResource(ResourceCardinality.SINGLE, ResourceType.FILE)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();
    public static final PropertyDescriptor DICTIONARY_FILTER = new PropertyDescriptor.Builder()
            .name("Dictionary Filter Pattern")
            .description("A Regular Expression that will be applied to each line in the dictionary file. If the regular expression does not "
                    + "match the line, the line will not be included in the list of terms to search for. If a Matching Group is specified, only the "
                    + "portion of the term that matches that Matching Group will be used instead of the entire term. If not specified, all terms in "
                    + "the dictionary will be used and each term will consist of the text of the entire line in the file")
            .required(false)
            .addValidator(StandardValidators.createRegexValidator(0, 1, false))
            .defaultValue(null)
            .build();

    private List<PropertyDescriptor> properties;
    private Set<Relationship> relationships;

    private volatile Pattern dictionaryFilterPattern = null;
    private volatile Pattern attributePattern = null;
    private volatile Set<String> dictionaryTerms = null;
    private volatile SynchronousFileWatcher fileWatcher = null;

    public static final Relationship REL_MATCHED = new Relationship.Builder()
            .name("matched")
            .description("FlowFiles whose attributes are found in the dictionary will be routed to this relationship")
            .build();
    public static final Relationship REL_UNMATCHED = new Relationship.Builder()
            .name("unmatched")
            .description("FlowFiles whose attributes are not found in the dictionary will be routed to this relationship")
            .build();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(DICTIONARY_FILE);
        properties.add(ATTRIBUTE_PATTERN);
        properties.add(MATCHING_CRITERIA);
        properties.add(DICTIONARY_FILTER);
        this.properties = Collections.unmodifiableList(properties);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_MATCHED);
        relationships.add(REL_UNMATCHED);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws IOException {
        final String filterRegex = context.getProperty(DICTIONARY_FILTER).getValue();
        this.dictionaryFilterPattern = (filterRegex == null) ? null : Pattern.compile(filterRegex);

        final String attributeRegex = context.getProperty(ATTRIBUTE_PATTERN).getValue();
        this.attributePattern = (attributeRegex.equals(".*")) ? null : Pattern.compile(attributeRegex);

        this.dictionaryTerms = createDictionary(context);
        this.fileWatcher = new SynchronousFileWatcher(Paths.get(context.getProperty(DICTIONARY_FILE).evaluateAttributeExpressions().getValue()), new LastModifiedMonitor(), 1000L);
    }

    private Set<String> createDictionary(final ProcessContext context) throws IOException {
        final Set<String> terms = new HashSet<>();

        try (final InputStream fis = context.getProperty(DICTIONARY_FILE).evaluateAttributeExpressions().asResource().read();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(fis))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String matchingTerm = line;
                if (dictionaryFilterPattern != null) {
                    final Matcher matcher = dictionaryFilterPattern.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }

                    // Determine if we should use the entire line or only a part, depending on whether or not
                    // a Matching Group was specified in the regex.
                    if (matcher.groupCount() == 1) {
                        matchingTerm = matcher.group(1);
                    } else {
                        matchingTerm = line;
                    }
                }

                terms.add(matchingTerm);
            }
        }

        return Collections.unmodifiableSet(terms);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final List<FlowFile> flowFiles = session.get(50);
        if (flowFiles.isEmpty()) {
            return;
        }

        final ComponentLog logger = getLogger();
        try {
            if (fileWatcher.checkAndReset()) {
                this.dictionaryTerms = createDictionary(context);
            }
        } catch (final IOException e) {
            logger.error("Unable to reload dictionary due to {}", e);
        }

        final boolean matchAll = context.getProperty(MATCHING_CRITERIA).getValue().equals(MATCH_CRITERIA_ALL);

        for (final FlowFile flowFile : flowFiles) {
            final boolean matched = matchAll ? allMatch(flowFile, attributePattern, dictionaryTerms) : anyMatch(flowFile, attributePattern, dictionaryTerms);
            final Relationship relationship = matched ? REL_MATCHED : REL_UNMATCHED;
            session.getProvenanceReporter().route(flowFile, relationship);
            session.transfer(flowFile, relationship);
            logger.info("Transferred {} to {}", flowFile, relationship);
        }
    }

    private boolean allMatch(final FlowFile flowFile, final Pattern attributePattern, final Set<String> dictionary) {
        for (final Map.Entry<String, String> entry : flowFile.getAttributes().entrySet()) {
            if (attributePattern == null || attributePattern.matcher(entry.getKey()).matches()) {
                if (!dictionary.contains(entry.getValue())) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean anyMatch(final FlowFile flowFile, final Pattern attributePattern, final Set<String> dictionary) {
        for (final Map.Entry<String, String> entry : flowFile.getAttributes().entrySet()) {
            if (attributePattern == null || attributePattern.matcher(entry.getKey()).matches()) {
                if (dictionary.contains(entry.getValue())) {
                    return true;
                }
            }
        }

        return false;
    }
}
