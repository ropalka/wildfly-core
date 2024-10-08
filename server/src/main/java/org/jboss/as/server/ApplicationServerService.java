/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.server;

import static org.jboss.as.server.ServerService.EXTERNAL_MODULE_CAPABILITY;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.as.controller.ControlledProcessState;
import org.jboss.as.controller.RunningModeControl;
import org.jboss.as.domain.http.server.ConsoleAvailabilityService;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.server.controller.git.GitContentRepository;
import org.jboss.as.server.deployment.ContentCleanerService;
import org.jboss.as.server.deployment.DeploymentMountProvider;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.as.server.mgmt.domain.RemoteFileRepositoryService;
import org.jboss.as.server.moduleservice.ExternalModuleService;
import org.jboss.as.server.moduleservice.ServiceModuleLoader;
import org.jboss.as.server.suspend.ServerSuspendController;
import org.jboss.as.version.ProductConfig;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceActivator;
import org.jboss.msc.service.ServiceActivatorContext;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceRegistry;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.threads.AsyncFuture;

/**
 * The root service for an Application Server process.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class ApplicationServerService implements Service<AsyncFuture<ServiceContainer>> {

    private final List<ServiceActivator> extraServices;
    private final Bootstrap.Configuration configuration;
    private final RunningModeControl runningModeControl;
    private final ControlledProcessState processState;
    private final ServerSuspendController suspendController;
    private final boolean standalone;
    private final boolean selfContained;
    private final ElapsedTime elapsedTime;
    private volatile FutureServiceContainer futureContainer;
    private volatile boolean everStopped;

    ApplicationServerService(final List<ServiceActivator> extraServices, final Bootstrap.Configuration configuration,
                             final ControlledProcessState processState, final ServerSuspendController suspendController,
                             final ElapsedTime elapsedTime) {
        this.extraServices = extraServices;
        this.configuration = configuration;
        runningModeControl = configuration.getRunningModeControl();
        standalone = configuration.getServerEnvironment().isStandalone();
        selfContained = configuration.getServerEnvironment().isSelfContained();
        this.processState = processState;
        this.suspendController = suspendController;
        this.elapsedTime = elapsedTime;
    }

    @Override
    public synchronized void start(final StartContext context) throws StartException {

        // If this is a reload, track start time independently of the overall process elapsed time
        ElapsedTime startupTime = everStopped ? elapsedTime.checkpoint() : elapsedTime;

        final Bootstrap.Configuration configuration = this.configuration;
        final ServerEnvironment serverEnvironment = configuration.getServerEnvironment();
        final ProductConfig config = serverEnvironment.getProductConfig();
        final String prettyVersion = config.getPrettyVersionString();
        final String banner = serverEnvironment.getStability() == org.jboss.as.version.Stability.EXPERIMENTAL ? config.getBanner() : "";
        ServerLogger.AS_ROOT_LOGGER.serverStarting(prettyVersion, banner);
        if (System.getSecurityManager() != null) {
            ServerLogger.AS_ROOT_LOGGER.securityManagerEnabled();
        }
        if (ServerLogger.CONFIG_LOGGER.isDebugEnabled()) {
            final Properties properties = System.getProperties();
            final StringBuilder b = new StringBuilder(8192);
            b.append(ServerLogger.ROOT_LOGGER.configuredSystemPropertiesLabel());
            for (String property : new TreeSet<String>(properties.stringPropertyNames())) {
                String propVal = property.toLowerCase(Locale.ROOT).contains("password") ? "<redacted>" : properties.getProperty(property, "<undefined>");
                if (property.toLowerCase(Locale.ROOT).equals("sun.java.command") && !propVal.isEmpty()) {
                    Pattern pattern = Pattern.compile("(-D(?:[^ ])+=)((?:[^ ])+)");
                    Matcher matcher = pattern.matcher(propVal);
                    StringBuffer sb = new StringBuffer(propVal.length());
                    while (matcher.find()) {
                        if (matcher.group(1).contains("password")) {
                            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + "<redacted>"));
                        }
                    }
                    matcher.appendTail(sb);
                    propVal = sb.toString();
                }
                if (property.toLowerCase(Locale.ROOT).equals("wildfly.config.url") && !propVal.isEmpty()) {
                    ServerLogger.CONFIG_LOGGER.wildflyConfigUrlIsSet(property + " = " + propVal);
                }
                b.append("\n\t").append(property).append(" = ").append(propVal);
            }
            ServerLogger.CONFIG_LOGGER.debug(b);
            ServerLogger.CONFIG_LOGGER.debugf(ServerLogger.ROOT_LOGGER.vmArgumentsLabel(getVMArguments()));
            if (ServerLogger.CONFIG_LOGGER.isTraceEnabled()) {
                b.setLength(0);
                final Map<String,String> env = System.getenv();
                b.append(ServerLogger.ROOT_LOGGER.configuredSystemEnvironmentLabel());
                for (String key : new TreeSet<String>(env.keySet())) {
                    String envVal = key.toLowerCase(Locale.ROOT).contains("password") ? "<redacted>" : env.get(key);
                    b.append("\n\t").append(key).append(" = ").append(envVal);
                }
                ServerLogger.CONFIG_LOGGER.trace(b);
            }
        }
        final ServiceTarget serviceTarget = context.getChildTarget();
        final ServiceController<?> myController = context.getController();
        final ServiceContainer container = myController.getServiceContainer();
        futureContainer = new FutureServiceContainer();

        CurrentServiceContainer.setServiceContainer(context.getController().getServiceContainer());

        final BootstrapListener bootstrapListener = new BootstrapListener(container, startupTime, serviceTarget, futureContainer, prettyVersion, serverEnvironment.getServerTempDir());
        bootstrapListener.getStabilityMonitor().addController(myController);
        // Install either a local or remote content repository
        if(standalone) {
            if ( ! selfContained ) {
                if(serverEnvironment.useGit()) {
                    GitContentRepository.addService(serviceTarget, serverEnvironment.getGitRepository(), serverEnvironment.getServerContentDir(), serverEnvironment.getServerTempDir());
                } else {
                    ContentRepository.Factory.addService(serviceTarget, serverEnvironment.getServerContentDir(), serverEnvironment.getServerTempDir());
                }
            }
        } else {
            RemoteFileRepositoryService.addService(serviceTarget, serverEnvironment.getServerContentDir(), serverEnvironment.getServerTempDir());
        }
        ContentCleanerService.addService(serviceTarget, ServerService.JBOSS_SERVER_CLIENT_FACTORY, ServerService.JBOSS_SERVER_SCHEDULED_EXECUTOR);
        DeploymentMountProvider.Factory.addService(serviceTarget);
        ServiceModuleLoader.addService(serviceTarget, configuration);
        ExternalModuleService.addService(serviceTarget, EXTERNAL_MODULE_CAPABILITY.getCapabilityServiceName());

        ConsoleAvailabilityService.addService(serviceTarget, bootstrapListener::logAdminConsole);

        //Add server path manager service
        ServerPathManagerService.addService(serviceTarget, new ServerPathManagerService(configuration.getCapabilityRegistry()), serverEnvironment);
        ServerService.addService(serviceTarget, configuration, processState, bootstrapListener, runningModeControl, configuration.getAuditLogger(),
                configuration.getAuthorizer(), configuration.getSecurityIdentitySupplier(), suspendController);
        final ServiceActivatorContext serviceActivatorContext = new ServiceActivatorContext() {
            @Override
            public ServiceTarget getServiceTarget() {
                return serviceTarget;
            }

            @Override
            public ServiceRegistry getServiceRegistry() {
                return container;
            }
        };

        for(ServiceActivator activator : extraServices) {
            activator.activate(serviceActivatorContext);
        }

        // TODO: decide the fate of these

        // Add server environment
        ServerEnvironmentService.addService(serverEnvironment, serviceTarget);


        // Add product config service
        final ServiceBuilder<?> sb = serviceTarget.addService(Services.JBOSS_PRODUCT_CONFIG_SERVICE);
        final Consumer<ProductConfig> productConfigConsumer = sb.provides(Services.JBOSS_PRODUCT_CONFIG_SERVICE);
        sb.setInstance(org.jboss.msc.Service.newInstance(productConfigConsumer, config));
        sb.install();

        // BES 2011/06/11 -- moved this to AbstractControllerService.start()
//        processState.setRunning();

        if (ServerLogger.AS_ROOT_LOGGER.isDebugEnabled()) {
            final long nanos = context.getElapsedTime();
            ServerLogger.AS_ROOT_LOGGER.debugf(prettyVersion + " root service started in %d.%06d ms",
                    Long.valueOf(nanos / 1000000L), Long.valueOf(nanos % 1000000L));
        }
    }

    @Override
    public synchronized void stop(final StopContext context) {
        //Moved to AbstractControllerService.stop()
        //processState.setStopping();
        CurrentServiceContainer.setServiceContainer(null);
        String prettyVersion = configuration.getServerEnvironment().getProductConfig().getPrettyVersionString();
        ServerLogger.AS_ROOT_LOGGER.serverStopped(prettyVersion, (int) (context.getElapsedTime() / 1000000L));
        BootstrapListener.deleteStartupMarker(configuration.getServerEnvironment().getServerTempDir());
        everStopped = true;
    }

    @Override
    public AsyncFuture<ServiceContainer> getValue() throws IllegalStateException, IllegalArgumentException {
        return futureContainer;
    }

    private String getVMArguments() {
      final StringBuilder result = new StringBuilder(1024);
      final RuntimeMXBean rmBean = ManagementFactory.getRuntimeMXBean();
      final List<String> inputArguments = rmBean.getInputArguments();
      for (String arg : inputArguments) {
          result.append(arg).append(" ");
      }
      return result.toString();
   }

}
