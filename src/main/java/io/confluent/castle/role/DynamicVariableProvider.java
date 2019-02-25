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

package io.confluent.castle.role;

import io.confluent.castle.cluster.CastleCluster;
import io.confluent.castle.cluster.CastleNode;

/**
 * Provides a value for a dynamic variable.
 *
 * Dynamic variables vary at based on cluster properties.  For example,
 * one dynamic variable could represent the connection string used to connect to
 * Kafka.
 */
public abstract class DynamicVariableProvider {
    private final int priority;

    protected DynamicVariableProvider(int priority) {
        this.priority = priority;
    }

    /**
     * The priority of this dynamic variable provider-- higher priorities take
     * precedence.
     */
    final public int priority() {
        return priority;
    }

    /**
     * Calculate the value of this dynamic variable.
     */
    public abstract String calculate(CastleCluster cluster,
                                     CastleNode node) throws Exception;
}
