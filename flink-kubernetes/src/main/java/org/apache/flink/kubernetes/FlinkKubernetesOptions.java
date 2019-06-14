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

package org.apache.flink.kubernetes;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ConfigurationUtils;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.util.Preconditions;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;

import java.util.Properties;

import static org.apache.flink.configuration.ConfigOptions.key;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.DYNAMIC_PROPERTY_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.HOST_OPTION;
import static org.apache.flink.runtime.entrypoint.parser.CommandLineOptions.REST_PORT_OPTION;

/**
 * Parameters that will be used in Flink on k8s cluster.
 * */
public class FlinkKubernetesOptions {

	public static final ConfigOption<Boolean> DEBUG_MODE =
		key("k8s.debugmode.enable")
			.defaultValue(false)
			.withDescription("Whether enable debug mode.");

	public static final ConfigOption<String> EXTERNAL_IP =
		key("jobmanager.k8s.externalip")
			.defaultValue("")
			.withDescription("The external ip for job manager external IP.");

	public static final Option IMAGE_OPTION = Option.builder("i")
		.longOpt("image")
		.required(false)
		.hasArg(true)
		.argName("image-name")
		.desc("the docker image name.")
		.build();

	public static final Option CLUSTERID_OPTION = Option.builder("cid")
		.longOpt("clusterid")
		.required(false)
		.hasArg(true)
		.argName("clusterid")
		.desc("the cluster id that will be used for current session.")
		.build();

	public static final Option KUBERNETES_CONFIG_FILE_OPTION = Option.builder("kc")
		.longOpt("kubeConfig")
		.required(false)
		.hasArg(true)
		.argName("ConfigFilePath")
		.desc("The config file to for K8s API client.")
		.build();

	public static final Option KUBERNETES_MODE_OPTION = Option.builder("k8s")
		.longOpt("KubernetesMode")
		.required(false)
		.hasArg(false)
		.desc("Whether use Kubernetes as resource manager.")
		.build();

	public static final Option HELP_OPTION = Option.builder("h")
		.longOpt("help")
		.required(false)
		.hasArg(false)
		.desc("Help for Kubernetes session CLI.")
		.build();

	private Configuration configuration;

	private String clusterId;

	private String namespace = "default";

	private String imageName;

	private String kubeConfigFileName = null;

	private String serviceUUID;

	private boolean sessionMode;

	private boolean detached;

	public boolean isDetached() {
		return detached;
	}

	public void setDetached(boolean detached) {
		this.detached = detached;
	}

	public boolean isSessionMode() {
		return sessionMode;
	}

	public void setSessionMode(boolean sessionMode) {
		this.sessionMode = sessionMode;
	}

	public FlinkKubernetesOptions(Configuration configuration, String clusterId) {
		Preconditions.checkArgument(configuration != null);
		this.configuration = configuration;
		this.clusterId = clusterId;
	}

	public String getServiceUUID() {
		return serviceUUID;
	}

	public void setServiceUUID(String serviceUUID) {
		this.serviceUUID = serviceUUID;
	}

	public String getClusterId() {
		return clusterId;
	}

	public void setClusterId(String clusterId) {
		this.clusterId = clusterId;
	}

	public String getImageName(){
		return this.imageName;
	}

	public void setImageName(String imageName) {
		this.imageName = imageName;
	}

	public String getNamespace() {
		return namespace;
	}

	public String getExternalIP() {
		return this.configuration.getString(FlinkKubernetesOptions.EXTERNAL_IP);
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public Integer getServicePort(ConfigOption<Integer> port) {
		return this.configuration.getInteger(port);
	}

	public Boolean getIsDebugMode() {
		return this.configuration.getBoolean(FlinkKubernetesOptions.DEBUG_MODE);
	}

	public Configuration getConfiguration() {
		return configuration;
	}

	public String getKubeConfigFilePath() {
		return kubeConfigFileName;
	}

	public void setKubeConfigFileName(String kubeConfigFileName) {
		this.kubeConfigFileName = kubeConfigFileName;
	}

	/**
	 * build FlinkKubernetesOption from commandline.
	 * */
	public static FlinkKubernetesOptions fromCommandLine(CommandLine commandLine){
		final Properties dynamicProperties = commandLine.getOptionProperties(DYNAMIC_PROPERTY_OPTION.getOpt());
		final String restPortString = commandLine.getOptionValue(REST_PORT_OPTION.getOpt(), "-1");
		int restPort = Integer.parseInt(restPortString);
		String hostname = commandLine.getOptionValue(HOST_OPTION.getOpt());
		final String imageName = commandLine.getOptionValue(IMAGE_OPTION.getOpt());
		final String clusterId = commandLine.getOptionValue(CLUSTERID_OPTION.getOpt());

		//hostname = hostname == null ? clusterId : hostname;
		Configuration configuration = GlobalConfiguration
			.loadConfigurationWithDynamicProperties(ConfigurationUtils.createConfiguration(dynamicProperties));

		if (hostname != null) {
			System.out.print("rest.address is: " + hostname);
			configuration.setString(RestOptions.ADDRESS, hostname);
		}

		if (restPort == -1) {
			restPort = RestOptions.PORT.defaultValue();
			configuration.setInteger(RestOptions.PORT, restPort);
		}

		FlinkKubernetesOptions options = new FlinkKubernetesOptions(configuration, clusterId);
		options.setImageName(imageName);

		return options;
	}
}
