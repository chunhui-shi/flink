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

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.JobManagerOptions;
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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
		LOG.info("createClusterPod with spec: " + pod.getInternalResource().getSpec().toString());
		LOG.info("createClusterPod with flinkOption.image: " + this.flinkKubeOptions.getImageName()
			+ ", clusterId:" + this.flinkKubeOptions.getClusterId());

		this.internalClient.pods().create(pod.getInternalResource());
	}

	@Override
	public String createTaskManagerPod(TaskManagerPodParameter parameter) {
		FlinkPod pod = new FlinkPod(this.flinkKubeOptions);

		String serviceUUID = getFlinkService(this.flinkKubeOptions.getClusterId()).getInternalResource().getMetadata().getUid();
		flinkKubeOptions.setServiceUUID(serviceUUID);

		for (Decorator<Pod, FlinkPod> d : this.taskManagerPodDecorators) {
			pod = d.decorate(pod);
		}

		pod = new TaskManagerDecorator(parameter).decorate(pod);
		LOG.info("createTaskManagerPod with spec: " + pod.getInternalResource().getSpec().toString());
		LOG.info("serviceUUID:" + pod.getInternalResource().getMetadata().getOwnerReferences().get(0).getUid());
		LOG.info("createTaskManagerPod with flinkOption.image: " + this.flinkKubeOptions.getImageName()
			+ ", clusterId:" + this.flinkKubeOptions.getClusterId());

		this.internalClient.pods().create(pod.getInternalResource());

		return parameter.getPodName();
	}

	@Override
	public boolean stopPod(String podName) {
		return this.internalClient.pods().withName(podName).delete();
	}

	/**
	 * To get nodePort of configured ports.
	 */
	private int getExposedServicePort(Service service, ConfigOption<Integer> configPort) {
		int port = this.flinkKubeOptions.getServicePort(configPort);
		for (ServicePort p : service.getSpec().getPorts()) {
			if (p.getPort() == port) {
				return p.getNodePort();
			}
		}
		return port;
	}

	/**
	 * Extract service address Endpoint.
	 * There are different cases to extract service address(IP and port) from a service.
	 * 1, when the service is default 'ClusterIP'. In this case the IP is not accessible from the outside
	 * 2, when the service is LoadBalancer.
	 *
	 * @param flinkService
	 */
	public Map<ConfigOption<Integer>, Endpoint> extractEndpoints(FlinkService flinkService) {
		Map<ConfigOption<Integer>, Endpoint> epMap = new HashMap<>();
		Service service = flinkService.getInternalResource();
		String addrStr = null;
		int jmPort = JobManagerOptions.PORT.defaultValue();
		int restPort = RestOptions.PORT.defaultValue();

		if (this.flinkKubeOptions.getIsDebugMode()
			&& this.flinkKubeOptions.getExternalIP() != null && this.flinkKubeOptions.getExternalIP() != "") {
			LOG.debug("extract externalIP in config: " + this.flinkKubeOptions.getExternalIP());
			//TODO preconditions check
			addrStr = this.flinkKubeOptions.getExternalIP();
		}
		else if (service.getStatus() != null
			&& service.getStatus().getLoadBalancer() != null
			&& service.getStatus().getLoadBalancer().getIngress() != null) {
			if (service.getStatus().getLoadBalancer().getIngress().size() > 0) {
				addrStr = service.getStatus().getLoadBalancer().getIngress().get(0).getIp();
			} else { //LoadBalancer type but no Ingress
				if (service.getSpec().getLoadBalancerIP() == null) {
					// use the same address of this rest endpoint
					addrStr = this.internalClient.getMasterUrl().getHost();
					restPort = getExposedServicePort(service, RestOptions.PORT);
					jmPort = getExposedServicePort(service, JobManagerOptions.PORT);
				}
			}
		} else if (service.getSpec().getExternalIPs() != null
			&& service.getSpec().getExternalIPs().size() > 0) {
			LOG.debug("extract externalIP in spec: " + service.getSpec().getExternalIPs().get(0));
			addrStr = service.getSpec().getExternalIPs().get(0);
		}

		epMap.put(RestOptions.PORT, new Endpoint(addrStr, restPort));
		epMap.put(JobManagerOptions.PORT, new Endpoint(addrStr, jmPort));

		return epMap;
	}

	@Override
	public CompletableFuture<FlinkService> createClusterService(String clusterId) {
		this.flinkKubeOptions.setClusterId(clusterId);
		FlinkService flinkService = new FlinkService(this.flinkKubeOptions);

		for (Decorator<Service, FlinkService> d : this.serviceDecorators) {
			flinkService = d.decorate(flinkService);
		}

		LOG.info(flinkService.getInternalResource().getSpec().toString());

		this.internalClient.services().create(flinkService.getInternalResource());

		ActionWatcher<Service> watcher = new ActionWatcher<>(Watcher.Action.ADDED,
			flinkService.getInternalResource());
		this.internalClient.services().withName(clusterId).watch(watcher);

		return CompletableFuture.supplyAsync(() -> {
			Service createdService = watcher.await(1, TimeUnit.MINUTES);
			FlinkService retFlinkService = new FlinkService(this.flinkKubeOptions, createdService);

			String uuid = createdService.getMetadata().getUid();
			if (uuid != null) {
				flinkKubeOptions.setServiceUUID(uuid);
			}

			return retFlinkService;

		});
	}

	@Override
	public List<String> listFlinkClusters() {
		return null;
	}

	@Override
	public void stopAndCleanupCluster(String clusterId) {
		this.internalClient.services().withName(clusterId).cascading(true).delete();
	}

	@Override
	public void logException(Exception e) {
		LOG.error("Kubernetes Exception: {}", e);
	}

	@Override
	public FlinkService getFlinkService(String clusterId) {
		String ns = (this.flinkKubeOptions.getNamespace() == null) ? "default" : this.flinkKubeOptions.getNamespace();
		Service service = this.internalClient.services().inNamespace(ns).withName(clusterId).fromServer().get();
		if (service == null) {
			LOG.error("service status is null for internal client: " + this.internalClient.toString());
			return null;
		}

		FlinkService flinkService = new FlinkService(this.flinkKubeOptions);
		flinkService.setInternalResource(service);
		return flinkService;
	}

	@Override
	public void close() {
		//clear pod without owner service
		String ns = this.flinkKubeOptions.getNamespace() == null ? "default" : this.flinkKubeOptions.getNamespace();
		this.internalClient.pods().inNamespace(ns).withLabelIn("app", "flink-native-k8s").list().getItems().forEach(p -> {
			String ownerName = p.getMetadata().getOwnerReferences().get(0).getName();
			if (this.internalClient.services().inNamespace(ns).withName(ownerName).fromServer().get() == null) {
				this.internalClient.pods().delete(p);
			}
		});
	}
}