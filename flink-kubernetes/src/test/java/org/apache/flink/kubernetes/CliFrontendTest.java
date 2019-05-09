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

import org.apache.flink.client.cli.CliArgsException;
import org.apache.flink.client.cli.CliFrontend;
import org.apache.flink.client.cli.CustomCommandLine;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.runtime.security.SecurityConfiguration;
import org.apache.flink.runtime.security.SecurityUtils;

import org.junit.Test;

import java.util.List;

/**
 * To test kubernetes related commands started from CliFrontend, such as 'bin/flink run -k8s..'.
 */
public class CliFrontendTest {

	@Test(expected = CliArgsException.class)
	public void testRunRemoteKube() throws Exception {
		// test unrecognized option
		String[] parameters = {"run",
			"-cid", "flink-session-cluster-938a01fd-7d15-475f-8772-b5c9ee9fe982",
			"-k8s",
			//"-Dk8s.debugmode.enable=true",
			"-j", "/Users/chunhui.shi/dev/tmp/WordCount.jar",
			"--input", "/Users/chunhui.shi/dev/tmp/README.md",
			"--output", "/Users/chunhui.shi/dev/tmp/out.txt"
		};

		CliFrontend testFrontend = getCliFrontendAsMain();
		SecurityUtils.install(new SecurityConfiguration(testFrontend.getConfiguration()));
		int retCode = SecurityUtils.getInstalledContext()
			.runSecured(() -> testFrontend.parseParameters(parameters));
		System.exit(retCode);
	}

	@Test(expected = CliArgsException.class)
	public void testStartSessionCluster() throws Exception {

	}

	/**
	 * To load the same configurations when CliFrontend is called from {CliFrontend.main}.
	 * @return
	 * @throws Exception
	 */
	public static CliFrontend getCliFrontendAsMain() throws Exception {
		// 1. find the configuration directory

		final String configurationDirectory =
			"/Users/chunhui.shi/dev/chunhui-shi/flink/flink-dist/target/flink-1.9-SNAPSHOT-bin/flink-1.9-SNAPSHOT/conf";
		//CliFrontend.getConfigurationDirectoryFromEnv();

		// 2. load the global configuration
		final Configuration configuration = GlobalConfiguration.loadConfiguration(configurationDirectory);

		// 3. load the custom command lines
		final List<CustomCommandLine<?>> customCommandLines = CliFrontend.loadCustomCommandLines(
			configuration,
			".");

		CliFrontend testFrontend = new CliFrontend(
			configuration,
			customCommandLines);
		System.out.print("configurationDirectory: " + configurationDirectory);
		System.out.print("cli.configuration: " + testFrontend.getConfiguration().toString());

		return testFrontend;

	}

}
