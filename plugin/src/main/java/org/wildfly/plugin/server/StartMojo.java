/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.plugin.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.galleon.ProvisioningException;
import org.jboss.galleon.maven.plugin.util.MavenArtifactRepositoryManager;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.core.launcher.CommandBuilder;
import org.wildfly.core.launcher.DomainCommandBuilder;
import org.wildfly.core.launcher.Launcher;
import org.wildfly.core.launcher.StandaloneCommandBuilder;
import org.wildfly.plugin.common.AbstractServerConnection;
import org.wildfly.plugin.common.Environment;
import org.wildfly.plugin.common.PropertyNames;
import org.wildfly.plugin.common.StandardOutput;
import org.wildfly.plugin.common.Utils;
import org.wildfly.plugin.core.GalleonUtils;
import org.wildfly.plugin.core.MavenRepositoriesEnricher;
import org.wildfly.plugin.core.ServerHelper;

/**
 * Starts a standalone instance of WildFly Application Server.
 * <p/>
 * The purpose of this goal is to start a WildFly Application Server for testing during the maven lifecycle.
 *
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
@Mojo(name = "start", requiresDependencyResolution = ResolutionScope.RUNTIME)
public class StartMojo extends AbstractServerConnection {

    @Component
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession session;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession mavenSession;
    /**
     * The target directory the application to be deployed is located.
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File targetDir;

    /**
     * The WildFly Application Server's home directory. If not used, WildFly will be downloaded.
     */
    @Parameter(alias = "jboss-home", property = PropertyNames.JBOSS_HOME)
    private String jbossHome;

    /**
     * The feature pack location. See the <a href="https://docs.wildfly.org/galleon/#_feature_pack_location">documentation</a>
     * for details on how to format a feature pack location.
     * <p>
     * Note that if you define the version in the feature pack location, e.g. {@code #26.1.1.Final}, the {@code version}
     * configuration parameter should be left blank.
     * </p>
     */
    @Parameter(alias = "feature-pack-location", property = PropertyNames.WILDFLY_FEATURE_PACK_LOCATION)
    private String featurePackLocation;

    /**
     * The version of the WildFly default server to install in case no jboss-home has been set
     * and no server has previously been provisioned.
     * The latest stable version is resolved if left blank.
     */
    @Parameter(alias = "version", property = PropertyNames.WILDFLY_VERSION)
    private String version;

    /**
     * The directory name inside the buildDir where to provision the default server.
     * By default the server is provisioned into the 'server' directory.
     *
     * @since 3.0
     */
    @Parameter(alias = "provisioning-dir", property = PropertyNames.WILDFLY_PROVISIONING_DIR, defaultValue = Utils.WILDFLY_DEFAULT_DIR)
    private String provisioningDir;

    /**
     * The modules path or paths to use. A single path can be used or multiple paths by enclosing them in a paths
     * element.
     */
    @Parameter(alias = "modules-path", property = PropertyNames.MODULES_PATH)
    private ModulesPath modulesPath;

    /**
     * The JVM options to use.
     */
    @Parameter(alias = "java-opts", property = PropertyNames.JAVA_OPTS)
    private String[] javaOpts;

    /**
     * The {@code JAVA_HOME} to use for launching the server.
     */
    @Parameter(alias = "java-home", property = PropertyNames.JAVA_HOME)
    private String javaHome;

    /**
     * The path to the server configuration to use. This is only used for standalone servers.
     */
    @Parameter(alias = "server-config", property = PropertyNames.SERVER_CONFIG)
    private String serverConfig;

    /**
     * The name of the domain configuration to use. This is only used for domain servers.
     */
    @Parameter(alias = "domain-config", property = PropertyNames.DOMAIN_CONFIG)
    private String domainConfig;

    /**
     * The name of the host configuration to use. This is only used for domain servers.
     */
    @Parameter(alias = "host-config", property = PropertyNames.HOST_CONFIG)
    private String hostConfig;

    /**
     * The path to the system properties file to load.
     */
    @Parameter(alias = "properties-file", property = PropertyNames.PROPERTIES_FILE)
    private String propertiesFile;

    /**
     * The timeout value to use when starting the server.
     */
    @Parameter(alias = "startup-timeout", defaultValue = "60", property = PropertyNames.STARTUP_TIMEOUT)
    private long startupTimeout;

    /**
     * The arguments to be passed to the server.
     */
    @Parameter(alias = "server-args", property = PropertyNames.SERVER_ARGS)
    private String[] serverArgs;

    /**
     * Set to {@code true} if you want to skip server start, otherwise {@code false}.
     */
    @Parameter(defaultValue = "false", property = PropertyNames.SKIP)
    private boolean skip;

    /**
     * Indicates how {@code stdout} and {@code stderr} should be handled for the spawned server process. Note that
     * {@code stderr} will be redirected to {@code stdout} if the value is defined unless the value is {@code none}.
     * <div>
     * By default {@code stdout} and {@code stderr} are inherited from the current process. You can change the setting
     * to one of the follow:
     * <ul>
     * <li>{@code none} indicates the {@code stdout} and {@code stderr} stream should not be consumed. This should
     * generally only be used if the {@code shutdown} goal is used in the same maven process.</li>
     * <li>{@code System.out} or {@code System.err} to redirect to the current processes <em>(use this option if you
     * see odd behavior from maven with the default value)</em></li>
     * <li>Any other value is assumed to be the path to a file and the {@code stdout} and {@code stderr} will be
     * written there</li>
     * </ul>
     * </div>
     * <div>
     * Note that if this goal is not later followed by a {@code shutdown} goal in the same maven process you should use
     * a file to redirect the {@code stdout} and {@code stderr} to. Both output streams will be redirected to the same
     * file.
     * </div>
     */
    @Parameter(property = PropertyNames.STDOUT)
    private String stdout;

    /**
     * The users to add to the server.
     */
    @Parameter(alias = "add-user", property = "wildfly.add-user")
    private AddUser addUser;

    /**
     * The type of server to start.
     * <p>
     * {@code STANDALONE} for a standalone server and {@code DOMAIN} for a domain server.
     * </p>
     */
    @Parameter(alias = "server-type", property = "wildfly.server.type", defaultValue = "STANDALONE")
    private ServerType serverType;

    private MavenRepoManager mavenRepoManager;

    /**
     * Specifies the environment variables to be passed to the process being started.
     * <div>
     * <pre>
     * &lt;env&gt;
     *     &lt;HOME&gt;/home/wildfly/&lt;/HOME&gt;
     * &lt;/env&gt;
     * </pre>
     * </div>
     */
    @Parameter
    private Map<String, String> env;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Log log = getLog();
        if (skip) {
            log.debug("Skipping server start");
            return;
        }
        MavenRepositoriesEnricher.enrich(mavenSession, project, repositories);
        mavenRepoManager = new MavenArtifactRepositoryManager(repoSystem, session, repositories);

        // Validate the environment
        final Path jbossHome = provisionIfRequired(targetDir.toPath().resolve(provisioningDir));
        if (!ServerHelper.isValidHomeDirectory(jbossHome)) {
            throw new MojoExecutionException(String.format("JBOSS_HOME '%s' is not a valid directory.", jbossHome));
        }

        // Determine how stdout should be consumed
        try {
            final StandardOutput out = StandardOutput.parse(stdout, true);
            // Create the server and close the client after the start. The process will continue running even after
            // the maven process may have been finished
            try (ModelControllerClient client = createClient()) {
                if (ServerHelper.isStandaloneRunning(client) || ServerHelper.isDomainRunning(client)) {
                    throw new MojoExecutionException(String.format("%s server is already running?", serverType));
                }
                final CommandBuilder commandBuilder = createCommandBuilder(jbossHome);
                log.info(String.format("%s server is starting up.", serverType));
                final Launcher launcher = Launcher.of(commandBuilder)
                        .setRedirectErrorStream(true);
                if (env != null) {
                    launcher.addEnvironmentVariables(env);
                }
                out.getRedirect().ifPresent(launcher::redirectOutput);

                final Process process = launcher.launch();
                // Note that if this thread is started and no shutdown goal is executed this stop the stdout and stderr
                // from being logged any longer. The user was warned in the documentation.
                out.startConsumer(process);
                if (serverType == ServerType.DOMAIN) {
                    ServerHelper.waitForDomain(process, DomainClient.Factory.create(client), startupTimeout);
                } else {
                    ServerHelper.waitForStandalone(process, client, startupTimeout);
                }
                if (!process.isAlive()) {
                    throw new MojoExecutionException("The process has been terminated before the start goal has completed.");
                }
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("The server failed to start", e);
        }
    }

    /**
     * Allows the {@link #javaOpts} to be set as a string. The string is assumed to be space delimited.
     *
     * @param value a spaced delimited value of JVM options
     */
    @SuppressWarnings("unused")
    public void setJavaOpts(final String value) {
        if (value != null) {
            javaOpts = value.split("\\s+");
        }
    }

    private CommandBuilder createCommandBuilder(final Path jbossHome) throws MojoExecutionException {
        if (serverType == ServerType.DOMAIN) {
            return createDomainCommandBuilder(jbossHome);
        }
        return createStandaloneCommandBuilder(jbossHome);
    }

    private CommandBuilder createStandaloneCommandBuilder(final Path jbossHome) throws MojoExecutionException {
        final StandaloneCommandBuilder commandBuilder = StandaloneCommandBuilder.of(jbossHome)
                .setJavaHome(javaHome)
                .addModuleDirs(modulesPath.getModulePaths());

        // Set the JVM options
        if (Utils.isNotNullOrEmpty(javaOpts)) {
            commandBuilder.setJavaOptions(javaOpts);
        }

        if (serverConfig != null) {
            commandBuilder.setServerConfiguration(serverConfig);
        }

        if (propertiesFile != null) {
            commandBuilder.setPropertiesFile(propertiesFile);
        }

        if (serverArgs != null) {
            commandBuilder.addServerArguments(serverArgs);
        }

        final Path javaHomePath = (this.javaHome == null ? Paths.get(System.getProperty("java.home")) : Paths.get(this.javaHome));
        if (Environment.isModularJvm(javaHomePath)) {
            commandBuilder.addJavaOptions(Environment.getModularJvmArguments());
        }

        // Print some server information
        final Log log = getLog();
        log.info("JAVA_HOME : " + commandBuilder.getJavaHome());
        log.info("JBOSS_HOME: " + commandBuilder.getWildFlyHome());
        log.info("JAVA_OPTS : " + Utils.toString(commandBuilder.getJavaOptions(), " "));
        try {
            addUsers(commandBuilder.getWildFlyHome(), commandBuilder.getJavaHome());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add users", e);
        }
        return commandBuilder;
    }

    private CommandBuilder createDomainCommandBuilder(final Path jbossHome) throws MojoExecutionException {
        final Path javaHome = (this.javaHome == null ? Paths.get(System.getProperty("java.home")) : Paths.get(this.javaHome));
        final DomainCommandBuilder commandBuilder = DomainCommandBuilder.of(jbossHome, javaHome)
                .addModuleDirs(modulesPath.getModulePaths());

        // Set the JVM options
        if (Utils.isNotNullOrEmpty(javaOpts)) {
            commandBuilder.setProcessControllerJavaOptions(javaOpts)
                    .setHostControllerJavaOptions(javaOpts);
        }

        if (domainConfig != null) {
            commandBuilder.setDomainConfiguration(domainConfig);
        }

        if (hostConfig != null) {
            commandBuilder.setHostConfiguration(hostConfig);
        }

        if (propertiesFile != null) {
            commandBuilder.setPropertiesFile(propertiesFile);
        }

        if (serverArgs != null) {
            commandBuilder.addServerArguments(serverArgs);
        }

        // Workaround for WFCORE-4121
        if (Environment.isModularJvm(javaHome)) {
            commandBuilder.addHostControllerJavaOptions(Environment.getModularJvmArguments());
            commandBuilder.addProcessControllerJavaOptions(Environment.getModularJvmArguments());
        }

        // Print some server information
        final Log log = getLog();
        log.info("JAVA_HOME : " + commandBuilder.getJavaHome());
        log.info("JBOSS_HOME: " + commandBuilder.getWildFlyHome());
        log.info("JAVA_OPTS : " + Utils.toString(commandBuilder.getHostControllerJavaOptions(), " "));
        try {
            addUsers(commandBuilder.getWildFlyHome(), commandBuilder.getJavaHome());
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to add users", e);
        }
        return commandBuilder;
    }

    private Path provisionIfRequired(final Path installDir) throws MojoFailureException {
        if (jbossHome != null) {
            //we do not need to download WildFly
            return Paths.get(jbossHome);
        }
        try {
            if (!Files.exists(installDir)) {
                getLog().info("Provisioning default server in " + installDir);
                GalleonUtils.provision(installDir, resolveFeaturePackLocation(), version, mavenRepoManager);
            }
            return installDir;
        } catch (ProvisioningException ex) {
            throw new MojoFailureException(ex.getLocalizedMessage(), ex);
        }
    }

    private void addUsers(final Path wildflyHome, final Path javaHome) throws IOException {
        if (addUser != null && addUser.hasUsers()) {
            getLog().info("Adding users: " + addUser);
            addUser.addUsers(wildflyHome, javaHome);
        }
    }

    private String resolveFeaturePackLocation() {
        return featurePackLocation == null ? getDefaultFeaturePackLocation() : featurePackLocation;
    }

    /**
     * Returns the default feature pack location if not defined in the configuration.
     *
     * @return the default feature pack location
     */
    protected String getDefaultFeaturePackLocation() {
        return "wildfly@maven(org.jboss.universe:community-universe)";
    }

    @Override
    public String goal() {
        return "start";
    }
}
