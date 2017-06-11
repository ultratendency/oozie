/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.action.hadoop;

import com.google.common.collect.Maps;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A generic password masker that masks {@code Map<String, String>} values given that its keys are considered password keys.
 * <p/>
 * Tested with {@link System#getProperties()} and {@link System#getenv()}.
 */
public class PasswordMasker {

    /**
     * The mask that is applied to recognized passwords.
     **/
    private static final String PASSWORD_MASK = "*****";

    /**
     * A key is considered a password key, if it contains {{pass}}, case ignored.
     **/
    private static final String PASSWORD_KEY = "pass";

    /**
     * Tells us whether a given string contains a password fragment. A password fragment is something that looks
     * like {{-Djavax.net.ssl.trustStorePassword=password}} or {{HADOOP_CREDSTORE_PASSWORD=pwd123}}
     *
     **/
    private static final String PASSWORD_CONTAINING_REGEX =
            "(.*)([\\w[.\\w]*]*(?i)" + PASSWORD_KEY + "[\\w]*=)([\\w]+)(.*)";

    private static final Pattern PASSWORD_CONTAINING_PATTERN = Pattern
            .compile(PASSWORD_CONTAINING_REGEX);

    /**
     * Extracts a password fragment from a given string.
     * <p/>
     * {@see java.util.Matcher#find()}
     **/
    private static final String PASSWORD_EXTRACTING_REGEX =
            "([\\w[.\\w]*]*(?i)pass[\\w]*=)([\\w]+)";

    private static final Pattern PASSWORD_EXTRACTING_PATTERN = Pattern
            .compile(PASSWORD_EXTRACTING_REGEX);

    /**
     * Returns a map where keys are masked if they are considered a password.
     * There are two cases when passwords are masked:
     * 1. The key contains the string "pass". In this case, the entire value is considered a password and replaced completely with
     * a masking string.
     * 2. The value matches a regular expression. Strings like "HADOOP_CREDSTORE_PASSWORD=pwd123" or
     * "-Djavax.net.ssl.trustStorePassword=password" are considered password definition strings and the text after the equal sign
     * is replaced with a masking string.
     *
     * @param unmasked key-value map
     * @return A new map where values are changed based on the replace algorithm described above
     */
    public Map<String, String> mask(Map<String, String> unmasked) {
        return Maps.transformEntries(unmasked, new Maps.EntryTransformer<String, String, String>() {
            @Override
            public String transformEntry(@Nonnull String key, @Nonnull String value) {
                checkNotNull(key, "key has to be set");
                checkNotNull(value, "value has to be set");

                if (isPasswordKey(key)) {
                    return PASSWORD_MASK;
                }

                return maskPasswordsIfNecessary(value);
            }
        });
    }

    /**
     * Masks passwords inside a string. A substring is subject to password masking if it looks like
     * "HADOOP_CREDSTORE_PASSWORD=pwd123" or "-Djavax.net.ssl.trustStorePassword=password". The text after the equal sign is
     * replaced with a masking string.
     *
     * @param unmasked String which might contain passwords
     * @return The same string where passwords are replaced with a masking string. If there is no password inside, the original
     * string is returned.
     */
    public String maskPasswordsIfNecessary(String unmasked) {
        if (containsPasswordFragment(unmasked)) {
            return maskPasswordFragments(unmasked);
        } else {
            return unmasked;
        }
    }

    private boolean isPasswordKey(String key) {
        return key.toLowerCase().contains(PASSWORD_KEY);
    }

    private boolean containsPasswordFragment(String maybePasswordFragments) {
        return PASSWORD_CONTAINING_PATTERN
                .matcher(maybePasswordFragments)
                .matches();
    }

    private String maskPasswordFragments(String maybePasswordFragments) {
        StringBuilder maskedBuilder = new StringBuilder();
        Matcher passwordFragmentsMatcher = PASSWORD_EXTRACTING_PATTERN
                .matcher(maybePasswordFragments);

        int start = 0, end;
        while (passwordFragmentsMatcher.find()) {
            end = passwordFragmentsMatcher.start();

            maskedBuilder.append(maybePasswordFragments.substring(start, end));
            maskedBuilder.append(passwordFragmentsMatcher.group(1));
            maskedBuilder.append(PASSWORD_MASK);

            start = passwordFragmentsMatcher.end();
        }

        maskedBuilder.append(maybePasswordFragments.substring(start));

        return maskedBuilder.toString();
    }
}
