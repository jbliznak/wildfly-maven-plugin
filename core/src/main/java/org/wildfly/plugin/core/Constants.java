/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.plugin.core;

/**
 * @author jdenise
 */
public interface Constants {

    public static final String CLI_RESOLVE_PARAMETERS_VALUES = "--resolve-parameter-values";
    public static final String CLI_ECHO_COMMAND_ARG = "--echo-command";
    public static final String PLUGIN_PROVISIONING_FILE = ".wildfly-maven-plugin-provisioning.xml";
    public static final String STANDALONE = "standalone";
    public static final String STANDALONE_XML = "standalone.xml";
    public static final String FORK_EMBEDDED_PROCESS_OPTION="jboss-fork-embedded";
}
