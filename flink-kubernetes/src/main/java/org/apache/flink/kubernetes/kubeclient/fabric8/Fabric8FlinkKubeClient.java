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

package org.apache.flink.kubernetes.kubeclient.fabric8;

import org.apache.flink.configuration.RestOptions;
import org.apache.flink.kubernetes.FlinkKubernetesOptions;
import org.apache.flink.kubernetes.kubeclient.Endpoint;
import org.apache.flink.kubernetes.kubeclient.KubeClient;
import org.apache.flink.kubernetes.kubeclient.TaskManagerPodParameter;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.Decorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.JobManagerPodDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.LoadBalancerDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.OwnerReferenceDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.PodInitializerDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.ServiceInitializerDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.ServicePortDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.TaskManagerDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.debug.ExternalIPDecorator;
import org.apache.flink.kubernetes.kubeclient.fabric8.decorators.debug.PodDebugDecorator;

import io.fabric8.kubernetes.api.model.LoadBalancerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The implementation of {@link KubeClient}.
 * */
public class Fabric8FlinkKubeClient implements KubeClient {

	private static final Logger LOG = LoggerFactory.getLogger(Fabric8FlinkKubeClient.class);

	private FlinkKubernetesOptions flinkKubeOptions;

	private List<Decorator<Pod, FlinkPod>> clusterPodDecorators;

	private List<Decorator<Pod, FlinkPod>> taskManagerPodDecorators;

	private List<Decorator<Service, FlinkService>> serviceDecorators;

	private KubernetesClient internalClient;

	public Fabric8FlinkKubeClient(FlinkKubernetesOptions kubeOptions, KubernetesClient client) {
		this.internalClient = client;
		this.flinkKubeOptions = kubeOptions;
		this.clusterPodDecorators = new ArrayList<>();
		this.serviceDecorators = new ArrayList<>();
		this.taskManagerPodDecorators = new ArrayList<>();
	}

	@Override
	public void initialize() {
		this.serviceDecorators.add(new ServiceInitializerDecorator());
		this.serviceDecorators.add(new ServicePortDecorator());

		this.clusterPodDecorators.add(new PodInitializerDecorator());
		this.clusterPodDecorators.add(new JobManagerPodDecorator());
		this.clusterPodDecorators.add(new OwnerReferenceDecorator());

		this.taskManagerPodDecorators.add(new PodInitializerDecorator());
		this.taskManagerPodDecorators.add(new OwnerReferenceDecorator());

		if (this.flinkKubeOptions.getIsDebugMode()) {
			this.serviceDecorators.add(new ExternalIPDecorator());
			this.clusterPodDecorators.add(new PodDebugDecorator());
			this.taskManagerPodDecorators.add(new PodDebugDecorator());
		} else {
			this.serviceDecorators.add(new LoadBalancerDecorator());
		}
	}

	@Override
	public void createClusterPod() {
		FlinkPod pod = new FlinkPod(this.flinkKubeOptions);

		for (Decorator<Pod, FlinkPod> d : this.clusterPodDecorators) {
			pod = d.decorate(pod);
		}
		LOG.info(pod.getInternalResource().getSpec().toString());

		this.internalClient.pods().create(pod.getInternalResource());
	}

	@Override
	public String createTaskManagerPod(TaskManagerPodParameter parameter) {
		FlinkPod pod = new FlinkPod(this.flinkKubeOptions);

		for (Decorator<Pod, FlinkPod> d : this.taskManagerPodDecorators) {
			pod = d.decorate(pod);
		}

		pod = new TaskManagerDecorator(parameter).decorate(pod);

		this.internalClient.pods().create(pod.getInternalResource());

		return parameter.getPodName();
	}

	@Override
	public boolean stopPod(String podName) {
		return this.internalClient.pods().withName(podName).delete();
	}

	/**
	 * Extract service address.
	 * */
	private String extractServiceAddress(Service service) {
		if (this.flinkKubeOptions.getIsDebugMode()
			&& this.flinkKubeOptions.getExternalIP() != null) {
			//TODO preconditions check
			return this.flinkKubeOptions.getExternalIP();
		}
		if (service.getStatus() != null
			&& service.getStatus().getLoadBalancer() != null
			&& service.getStatus().getLoadBalancer().getIngress() != null
			&& service.getStatus().getLoadBalancer().getIngress().size() > 0
			) {
			return service.getStatus().getLoadBalancer().getIngress().get(0).getIp();
		} else if (service.getSpec().getExternalIPs() != null
			&& service.getSpec().getExternalIPs().size() > 0) {
			return service.getSpec().getExternalIPs().get(0);
		}

		return null;
	}

	@Override
	public CompletableFuture<Endpoint> createClusterService(String clusterId) {
		this.flinkKubeOptions.setClusterId(clusterId);
		FlinkService service = new FlinkService(this.flinkKubeOptions);

		for (Decorator<Service, FlinkService> d : this.serviceDecorators) {
			service = d.decorate(service);
		}

		LOG.info(service.getInternalResource().getSpec().toString());

		this.internalClient.services().create(service.getInternalResource());

		ActionWatcher<Service> watcher = new ActionWatcher<>(Watcher.Action.ADDED, service.getInternalResource());
		this.internalClient.services().watch(watcher);

		return CompletableFuture.supplyAsync(() -> {
			Service createdService = watcher.await(1, TimeUnit.MINUTES);
			String address = extractServiceAddress(createdService);
			if (address == null) {
				address = "127.0.0.1";
				LOG.warn("extractServiceAddress got null address for createdService: ", createdService);
			}

			String uuid = createdService.getMetadata().getUid();
			if (uuid != null) {
				flinkKubeOptions.setServiceUUID(uuid);
			}

			int uiPort = this.flinkKubeOptions.getServicePort(RestOptions.PORT);
			return new Endpoint(address, uiPort);
		});
	}

	@Override
	public List<String> listFlinkClusters() {
		return null;
	}

	@Override
	public void stopAndCleanupCluster(String clusterId) {
		this.internalClient.services().withName(clusterId).delete();
	}

	@Override
	public void logException(Exception e) {
		LOG.error("Kubernetes Exception: {}", e);
	}

	@Override
	public Endpoint getResetEndpoint(String clusterId) {
		Service service = this.internalClient.services().withName(clusterId).fromServer().get();
		if (service == null) {
			LOG.error("service status is null for internal client: " + this.internalClient.toString());
			return null;
		}

		LoadBalancerStatus lbStatus = service.getStatus().getLoadBalancer();
		LOG.info("loadbalancer status: " + lbStatus.toString());

		String address = lbStatus.getIngress().get(0).getIp();
		int port = service.getSpec().getPorts().get(0).getPort();

		return new Endpoint(address, port);
	}

	@Override
	public void close() {

	}
}
