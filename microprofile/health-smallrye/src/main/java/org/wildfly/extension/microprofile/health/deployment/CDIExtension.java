/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017, Red Hat, Inc., and individual contributors
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

package org.wildfly.extension.microprofile.health.deployment;

import io.smallrye.health.SmallRyeHealthReporter;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.Health;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.modules.Module;
import org.wildfly.extension.microprofile.health.HealthReporter;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.BeforeShutdown;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.util.AnnotationLiteral;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * @author <a href="http://jmesnil.net/">Jeff Mesnil</a> (c) 2017 Red Hat inc.
 */
public class CDIExtension implements Extension {

    private static final Logger LOGGER = Logger.getLogger(CDIExtension.class.getName());
    private final HealthReporter reporter;
    private final Module module;

    static final class HealthLiteral extends AnnotationLiteral<Health> implements Health {

        static final HealthLiteral INSTANCE = new HealthLiteral();

        private static final long serialVersionUID = 1L;

    }

    // Use a single CDI instance to select and destroy all HealthCheck probes instances
    private Instance<Object> instance;
    private final List<HealthCheck> healthChecks = new ArrayList<>();
    private final List<HealthCheck> livenessChecks = new ArrayList<>();
    private final List<HealthCheck> readinessChecks = new ArrayList<>();
    private HealthCheck defaultReadinessCheck;


    public CDIExtension(HealthReporter healthReporter, Module module) {
        this.reporter = healthReporter;
        this.module = module;
    }

    /**
     * Get CDI <em>instances</em> of HealthCheck and
     * add them to the {@link HealthReporter}.
     */
    private void afterDeploymentValidation(@Observes final AfterDeploymentValidation avd) throws NamingException {

        System.out.println("");
        System.out.println("After deployment validation " + module.getName());
        BeanManager beanManager = tryGetBeanManagerFromJndiLookup()
                .orElseGet(() -> CDI.current().getBeanManager());
        instance = beanManager.createInstance();
        System.out.println(module.getClassLoader());
        System.out.println(Arrays.toString(instance.select(HealthCheck.class, Any.Literal.INSTANCE).stream().toArray(HealthCheck[]::new)));

        addHealthChecks(HealthLiteral.INSTANCE, reporter::addHealthCheck, healthChecks);
        addHealthChecks(Liveness.Literal.INSTANCE, reporter::addLivenessCheck, livenessChecks);
        addHealthChecks(Readiness.Literal.INSTANCE, reporter::addReadinessCheck, readinessChecks);
        if (readinessChecks.isEmpty()) {
            Config config = ConfigProvider.getConfig(module.getClassLoader());
            boolean disableDefaultprocedure = config.getOptionalValue("mp.health.disable-default-procedures", Boolean.class).orElse(false);
            if (!disableDefaultprocedure) {
                // no readiness probe are present in the deployment. register a readiness check so that the deployment is considered ready
                defaultReadinessCheck = new DefaultReadinessHealthCheck(module.getName());
                reporter.addReadinessCheck(defaultReadinessCheck, module.getClassLoader());
            }
        }
    }

    private Optional<BeanManager> tryGetBeanManagerFromJndiLookup() {
        try {
            BeanManager jndiBeanManager = InitialContext.doLookup("java:comp/BeanManager");
            return Optional.of(jndiBeanManager);
        } catch (NamingException e) {
            LOGGER.fine("Could not get BeanManager instance using JNDI lookup for java:comp/BeanManager : " + e.getMessage());
            return Optional.empty();
        }
    }

    private void addHealthChecks(AnnotationLiteral qualifier,
                                 BiConsumer<HealthCheck, ClassLoader> healthFunction, List<HealthCheck> healthChecks) {
        for (HealthCheck healthCheck : instance.select(HealthCheck.class, qualifier)) {
            healthFunction.accept(healthCheck, module.getClassLoader());
            healthChecks.add(healthCheck);
        }
    }

    /**
     * Called when the deployment is undeployed.
     * <p>
     * Remove all the instances of {@link HealthCheck} from the {@link HealthReporter}.
     */
    public void beforeShutdown(@Observes final BeforeShutdown bs) {
        removeHealthCheck(healthChecks, reporter::removeHealthCheck);
        removeHealthCheck(livenessChecks, reporter::removeLivenessCheck);
        removeHealthCheck(readinessChecks, reporter::removeReadinessCheck);

        if (defaultReadinessCheck != null) {
            reporter.removeReadinessCheck(defaultReadinessCheck);
            defaultReadinessCheck = null;
        }

        instance = null;
    }

    private void removeHealthCheck(List<HealthCheck> healthChecks,
                                   Consumer<HealthCheck> healthFunction) {
        for (HealthCheck healthCheck : healthChecks) {
            healthFunction.accept(healthCheck);
            instance.destroy(healthCheck);
        }
        healthChecks.clear();
    }

    public void vetoSmallryeHealthReporter(@Observes ProcessAnnotatedType<SmallRyeHealthReporter> pat) {
        pat.veto();
    }

    private static final class DefaultReadinessHealthCheck implements HealthCheck {

        private final String deploymentName;

        DefaultReadinessHealthCheck(String deploymentName) {
            this.deploymentName = deploymentName;
        }

        @Override
        public HealthCheckResponse call() {
            return HealthCheckResponse.named("ready-" + deploymentName)
                    .up()
                    .build();
        }
    }
}
