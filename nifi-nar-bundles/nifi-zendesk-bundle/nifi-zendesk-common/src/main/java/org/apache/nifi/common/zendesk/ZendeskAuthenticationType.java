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

package org.apache.nifi.common.zendesk;

import org.apache.nifi.components.DescribedValue;

import java.util.stream.Stream;

import static java.lang.String.format;

public enum ZendeskAuthenticationType implements DescribedValue {
    PASSWORD("password", "Password",
        "Password of Zendesk login user.") {
        @Override
        public String enrichUserName(String userName) {
            return userName;
        }
    },
    TOKEN("token", "Token",
        "Authentication token generated in Zendesk Admin menu for API access.") {
        @Override
        public String enrichUserName(String userName) {
            return format(ZENDESK_USERNAME_WITH_TOKEN_TEMPLATE, userName);
        }
    };

    private static final String ZENDESK_USERNAME_WITH_TOKEN_TEMPLATE = "%s/token";

    private final String value;
    private final String displayName;
    private final String description;

    ZendeskAuthenticationType(String value, String displayName, String description) {
        this.value = value;
        this.displayName = displayName;
        this.description = description;
    }

    public static ZendeskAuthenticationType forName(String authenticationType) {
        return Stream.of(values()).filter(authType -> authType.getValue().equalsIgnoreCase(authenticationType)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Invalid Zendesk authentication type: " + authenticationType));
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public abstract String enrichUserName(String userName);
}
