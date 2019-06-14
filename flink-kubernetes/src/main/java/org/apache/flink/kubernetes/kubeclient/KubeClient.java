/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.kubernetes.kubeclient;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.kubernetes.kubeclient.fabric8.FlinkService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The client to talk with kubernetes.
 * */
public interface KubeClient extends AutoCloseable {

	/**
	 * Initialize client.
	 * */
	void initialize();

	/**
	 * Create kubernetes services and expose endpoints for access outside cluster.
	 */
	CompletableFuture<FlinkService> createClusterService(String clusterId) throws Exception;

	/**
	 * List all running sessions in current kubernetes cluster.
	 * */
	List<String> listFlinkClusters();

	/**
	 * Create cluster pod.
	 */
	void createClusterPod(boolean sessionMode);

	/**
	 * Create task manager pod.
	 * */
	String createTaskManagerPod(TaskManagerPodParameter parameter);

	/**
	 * stop a pod.
	 * */
	boolean stopPod(String podName);

	/**
	 * stop cluster and clean up all resources, include services, auxiliary services and all running pods.
	 * */
	void stopAndCleanupCluster(String clusterId);

	/**
	 * Log exceptions.
	 * */
	void logException(Exception e);

	/**
	 * retrieve rest endpoint of the given flink clusterId.
	 */
	FlinkService getFlinkService(String flinkClusterId);

	/**
	 *  For a FlinkService, get its port maps.
	 * @param service
	 */
	Map<ConfigOption<Integer>, Endpoint> extractEndpoints(FlinkService service);
}
