/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.camel.itest.osgi;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Inject;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.OptionUtils;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.ops4j.pax.exam.CoreOptions.equinox;
import static org.ops4j.pax.exam.CoreOptions.felix;
import static org.ops4j.pax.exam.CoreOptions.knopflerfish;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.mavenConfiguration;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.container.def.PaxRunnerOptions.scanFeatures;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.MavenUtils.asInProject;
import static org.ops4j.pax.exam.container.def.PaxRunnerOptions.logProfile;
/**
 * @version $Revision$
 */
@RunWith(JUnit4TestRunner.class)
public class OSGiIntegrationTest {
    private static final transient Log LOG = LogFactory.getLog(OSGiIntegrationTest.class);

    @Inject
    BundleContext bundleContext;

    @Test
    public void listBundles() throws Exception {
        LOG.info("************ Hello from OSGi ************");

        for (Bundle b : bundleContext.getBundles()) {
            LOG.info("Bundle " + b.getBundleId() + " : " + b.getSymbolicName());
        }

        // TODO we should be using Camel OSGi really to deal with class loader issues
        CamelContext camelContext = new DefaultCamelContext();

        camelContext.addRoutes(new RouteBuilder() {
            public void configure() throws Exception {
                from("seda:foo").to("seda:bar");
            }
        });

        camelContext.start();

        LOG.info("CamelContext started");

        Thread.sleep(2000);

        LOG.info("CamelContext stopping");

        camelContext.stop();

        LOG.info("CamelContext stopped");
    }

    @Configuration
    public static Option[] configure() {
        Option[] options = options(
            // install log service using pax runners profile abstraction (there are more profiles, like DS)
            logProfile(),
    
            // this is how you set the default log level when using pax logging (logProfile)
            systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("DEBUG"),
    
            // lets deploy the bundles we need
            mavenBundle().groupId("org.apache.camel").artifactId("camel-core").versionAsInProject(),
            
            //scanFeatures("mvn:org.apache.camel.karaf/features/2.0-SNAPSHOT/xml/features",
            //              "camel-core"),
               
            
            knopflerfish(), felix(), equinox());

        // use config generated by the Maven plugin (until PAXEXAM-62/64 get resolved)
        if (OSGiIntegrationTest.class.getClassLoader().getResource("META-INF/maven/paxexam-config.args") != null) {
           options = OptionUtils.combine(options, mavenConfiguration());
        }
        
        return options;
    }
   
   
}