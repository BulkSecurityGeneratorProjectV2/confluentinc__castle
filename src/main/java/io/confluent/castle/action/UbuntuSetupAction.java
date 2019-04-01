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

package io.confluent.castle.action;

import io.confluent.castle.cluster.CastleCluster;
import io.confluent.castle.cluster.CastleNode;
import io.confluent.castle.command.CommandResultException;
import io.confluent.castle.role.UbuntuNodeRole;

import java.util.Arrays;
import java.util.List;

/**
 * Install some necessary components on Ubuntu.
 */
public final class UbuntuSetupAction extends Action {
    public final static String TYPE = "ubuntuSetup";

    private final UbuntuNodeRole role;

    private final static int APT_GET_BUSY_ERROR_CODE = 100;

    private final static int APT_GET_RETRY_INTERVAL = 200;

    public UbuntuSetupAction(String scope, UbuntuNodeRole role) {
        super(new ActionId(TYPE, scope),
            new TargetId[] {},
            new String[] {},
            0);
        this.role = role;
    }

    @Override
    public void call(CastleCluster cluster, CastleNode node) throws Throwable {
        node.log().printf("*** %s: Beginning UbuntuSetup...%n", node.nodeName());
        while (true) {
            List<String> commandLine = Arrays.asList("-n", "--",
                "sudo", "dpkg", "--configure", "-a", "&&",
                "sudo", "apt-get", "update", "-y", "&&",
                "sudo", "apt-get", "install", "-y", "iptables", "rsync", "wget", "curl", "collectd-core",
                "coreutils", "cmake", "pkg-config", "libfuse-dev", role.jdkPackage());
            int returnCode = node.uplink().command().argList(commandLine).run();
            if (returnCode == 0) {
                break;
            } else if (returnCode == APT_GET_BUSY_ERROR_CODE) {
                node.log().printf("*** %s: got exit status %d: retrying in %d ms.%n",
                    node.nodeName(),
                    returnCode,
                    APT_GET_RETRY_INTERVAL);
                Thread.sleep(APT_GET_RETRY_INTERVAL);
            } else {
                throw new CommandResultException(commandLine, returnCode);
            }
        }
        node.log().printf("*** %s: Finished UbuntuSetup.%n", node.nodeName());
    }
};
