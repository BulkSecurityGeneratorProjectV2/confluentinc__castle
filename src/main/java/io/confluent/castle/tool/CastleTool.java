/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.confluent.castle.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.confluent.castle.cluster.CastleNodeSpec;
import io.confluent.castle.common.CastleUtil;
import io.confluent.castle.common.JsonConfigFile;
import io.confluent.castle.common.StringExpander;
import net.sourceforge.argparse4j.ArgumentParserBuilder;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import io.confluent.castle.action.ActionRegistry;
import io.confluent.castle.action.ActionScheduler;
import io.confluent.castle.cluster.CastleCluster;
import io.confluent.castle.cluster.CastleClusterSpec;
import io.confluent.castle.common.CastleLog;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.confluent.castle.common.JsonUtil.JSON_SERDE;
import static net.sourceforge.argparse4j.impl.Arguments.store;
import static net.sourceforge.argparse4j.impl.Arguments.storeTrue;

/**
 * The castle command.
 */
public final class CastleTool {
    private static final String CASTLE_CLUSTER_INPUT_PATH = "CASTLE_CLUSTER_INPUT_PATH";
    private static final String CASTLE_TARGETS = "CASTLE_TARGETS";
    private static final String CASTLE_WORKING_DIRECTORY = "CASTLE_WORKING_DIRECTORY";
    private static final String CASTLE_VERBOSE = "CASTLE_VERBOSE";
    private static final boolean CASTLE_VERBOSE_DEFAULT = false;
    private static final String CASTLE_MAX_CONCURRENT_ACTIONS = "CASTLE_MAX_CONCURRENT_ACTIONS";
    private static final int CASTLE_MAX_CONCURRENT_ACTIONS_DEFAULT = 6;
    private static final String CASTLE_PREFIX = "CASTLE_";

    private static final String CASTLE_DESCRIPTION = String.format(
        "The Kafka castle cluster tool.%n" +
        "%n" +
        "Valid targets:%n" +
        "up:                Bring up all nodes.%n" +
        "  init:            Allocate nodes.%n" +
        "  setup:           Set up all nodes.%n" +
        "  start:           Start the system.%n" +
        "%n" +
        "status:            Get the system status.%n" +
        "  daemonStatus:    Get the status of system daemons.%n" +
        "  taskStatus:      Get the status of trogdor tasks.%n" +
        "%n" +
        "down:              Bring down all nodes.%n" +
        "  saveLogs:        Save the system logs.%n" +
        "  stop:            Stop the system.%n" +
        "  destroy:         Deallocate nodes.%n" +
        "%n" +
        "destroyNodes:    Destroy all nodes.%n" +
        "%n" +
        "ssh [nodes] [cmd]: Ssh to the given node(s)%n" +
        "%n");

    private static String getEnv(String name, String defaultValue) {
        String val = System.getenv(name);
        if (val != null) {
            return val;
        }
        return defaultValue;
    }

    private static boolean getEnvBoolean(String name, boolean defaultValue) {
        String val = System.getenv(name);
        if (val != null) {
            try {
                return Boolean.parseBoolean(val);
            } catch (NumberFormatException e) {
                throw new RuntimeException("Unable to parse value " + name +
                    " given for " + name, e);
            }
        }
        return defaultValue;
    }

    public static class EnvironmentVariableStringExpander implements StringExpander {
        @Override
        public String lookupVariable(String key) {
            if (!key.startsWith(CASTLE_PREFIX)) {
                return null;
            }
            String value = System.getenv(key);
            if (value == null) {
                throw new RuntimeException("You must set the environment variable " + key +
                    " to use this configuration file.");
            }
            return value;
        }
    }

    private static CastleClusterSpec readClusterSpec(String clusterInputPath) throws Throwable {
        JsonNode confNode = new JsonConfigFile(clusterInputPath).jsonNode();
        JsonNode expandedConfNode = new EnvironmentVariableStringExpander().expand(confNode);
        return JSON_SERDE.treeToValue(expandedConfNode, CastleClusterSpec.class);
    }

    private static void mergerClusterConf(String newPath, String oldPath) throws Throwable {
        CastleClusterSpec newSpec = readClusterSpec(newPath);
        CastleClusterSpec oldSpec = readClusterSpec(oldPath);
        if ((!JSON_SERDE.writeValueAsString(newSpec.conf()).
                    equals(JSON_SERDE.writeValueAsString(oldSpec.conf()))) ||
                (!JSON_SERDE.writeValueAsString(newSpec.roles()).
                    equals(JSON_SERDE.writeValueAsString(oldSpec.roles())))) {
            HashMap<String, CastleNodeSpec> mergedNodes = new HashMap<>();
            for (Map.Entry<String, CastleNodeSpec> entry : newSpec.nodes().entrySet()) {
                String nodeName = entry.getKey();
                CastleNodeSpec newNode = entry.getValue();
                CastleNodeSpec oldNode = oldSpec.nodes().get(nodeName);
                mergedNodes.put(nodeName, new CastleNodeSpec(newNode.roleNames(),
                    oldNode != null ? oldNode.rolePatches() : Collections.emptyMap()));
            }
            CastleClusterSpec mergedSpec =
                new CastleClusterSpec(newSpec.conf(), mergedNodes, newSpec.roles());
            System.out.printf("Merging new data from %s into %s%n", newPath, oldPath);
            JSON_SERDE.writeValue(new File(oldPath), mergedSpec);
        }
    }

    public static void main(String[] args) throws Throwable {
        ArgumentParser parser = ArgumentParsers.newFor("castle-tool").
            addHelp(true).build().
            description(CASTLE_DESCRIPTION);

        parser.addArgument("-c", "--cluster")
            .action(store())
            .type(String.class)
            .dest(CASTLE_CLUSTER_INPUT_PATH)
            .metavar(CASTLE_CLUSTER_INPUT_PATH)
            .setDefault(getEnv(CASTLE_CLUSTER_INPUT_PATH, ""))
            .help("The cluster file to use.");
        parser.addArgument("-w", "--working-directory")
            .action(store())
            .type(String.class)
            .dest(CASTLE_WORKING_DIRECTORY)
            .metavar(CASTLE_WORKING_DIRECTORY)
            .setDefault(getEnv(CASTLE_WORKING_DIRECTORY, ""))
            .help("The output path to store logs, cluster files, and other outputs in.");
        parser.addArgument("-v")
            .action(storeTrue())
            .type(Boolean.class)
            .required(false)
            .dest(CASTLE_VERBOSE)
            .metavar(CASTLE_VERBOSE)
            .setDefault(getEnvBoolean(CASTLE_VERBOSE, CASTLE_VERBOSE_DEFAULT))
            .help("Enable verbose logging.");
        parser.addArgument("-m", "--max-concurrent-actions")
            .action(store())
            .type(Integer.class)
            .dest(CASTLE_MAX_CONCURRENT_ACTIONS)
            .metavar(CASTLE_MAX_CONCURRENT_ACTIONS)
            .setDefault(Integer.valueOf(getEnv(CASTLE_MAX_CONCURRENT_ACTIONS,
                Integer.toString(CASTLE_MAX_CONCURRENT_ACTIONS_DEFAULT))))
            .help("The maximum number of concurrent actions to allow.");
        parser.addArgument("target")
            .nargs("*")
            .action(store())
            .required(false)
            .dest(CASTLE_TARGETS)
            .metavar(CASTLE_TARGETS)
            .help("The target action(s) to run.");

        final Namespace res = parser.parseArgsOrFail(args);
        final CastleLog clusterLog = CastleLog.
            fromStdout("cluster", res.getBoolean(CASTLE_VERBOSE));
        CastleShutdownManager shutdownManager = new CastleShutdownManager(clusterLog);
        shutdownManager.install();
        try {
            List<String> targets = res.<String>getList(CASTLE_TARGETS);
            if (targets.isEmpty()) {
                parser.printHelp();
                System.exit(0);
            }
            String workingDirectory = res.getString(CASTLE_WORKING_DIRECTORY);
            if (workingDirectory == null || workingDirectory.isEmpty()) {
                throw new RuntimeException("You must specify the working directory " +
                    "with -w or " + CASTLE_WORKING_DIRECTORY);
            }
            String clusterPath = res.getString(CASTLE_CLUSTER_INPUT_PATH);
            Path defaultClusterConfPath = Paths.get(workingDirectory,
                CastleEnvironment.CLUSTER_FILE_NAME);
            if (defaultClusterConfPath.toFile().exists()) {
                if (!clusterPath.isEmpty()) {
                    mergerClusterConf(clusterPath, defaultClusterConfPath.toString());
                }
                clusterPath = defaultClusterConfPath.toAbsolutePath().toString();
            } else if (clusterPath.isEmpty()) {
                throw new RuntimeException("You must specify a cluster with with -c or --cluster.");
            } else if (!new File(clusterPath).exists()) {
                throw new RuntimeException("The specified cluster path " + clusterPath +
                    " does not exist.");
            }
            Files.createDirectories(Paths.get(workingDirectory));
            CastleEnvironment env = new CastleEnvironment(workingDirectory);
            CastleClusterSpec clusterSpec = readClusterSpec(clusterPath);

            int maxConcurrentActions = res.getInt(CASTLE_MAX_CONCURRENT_ACTIONS);
            try (CastleCluster cluster = new CastleCluster(env, clusterLog,
                    shutdownManager, clusterSpec)) {
                if (targets.contains(CastleSsh.COMMAND)) {
                    CastleSsh.run(cluster, targets);
                } else {
                    try (ActionScheduler scheduler = cluster.createScheduler(targets,
                        ActionRegistry.INSTANCE.actions(cluster.nodes().keySet()),
                        maxConcurrentActions)) {
                        scheduler.await(cluster.conf().globalTimeout(), TimeUnit.SECONDS);
                    }
                }
            }
            shutdownManager.shutdownNormally();
            System.exit(shutdownManager.returnCode().code());
        } catch (Throwable exception) {
            System.out.printf("Exiting with exception: %s%n", CastleUtil.fullStackTrace(exception));
            System.exit(1);
        }
    }
};
