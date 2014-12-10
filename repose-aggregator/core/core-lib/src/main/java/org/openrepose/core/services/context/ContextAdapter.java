package org.openrepose.core.services.context;

import org.openrepose.core.services.RequestProxyService;
import org.openrepose.core.services.classloader.ClassLoaderManagerService;
import org.openrepose.core.services.config.ConfigurationService;
import org.openrepose.core.services.context.container.ContainerConfigurationService;
import org.openrepose.core.services.event.common.EventService;
import org.openrepose.nodeservice.request.RequestHeaderService;
import org.openrepose.core.services.headers.response.ResponseHeaderService;
import org.openrepose.core.services.logging.LoggingService;
import org.openrepose.core.services.reporting.ReportingService;
import org.openrepose.core.services.reporting.metrics.MetricsService;
import org.openrepose.core.services.rms.ResponseMessageService;
import org.openrepose.core.services.routing.RoutingService;
import org.openrepose.core.services.threading.ThreadingService;
import org.openrepose.core.services.datastore.DatastoreService;
import org.openrepose.core.services.healthcheck.HealthCheckService;
import org.openrepose.core.services.httpclient.HttpClientService;
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient;


@Deprecated
public interface ContextAdapter {

    ClassLoaderManagerService classLoader();
    EventService eventService();
    ThreadingService threadingService();
    DatastoreService datastoreService();
    ConfigurationService configurationService();
    ContainerConfigurationService containerConfigurationService();
    ResponseMessageService responseMessageService();
    LoggingService loggingService();
    MetricsService metricsService();
    RoutingService routingService();
    RequestProxyService requestProxyService();
    ReportingService reportingService();
    String getReposeVersion();
    HttpClientService httpConnectionPoolService();
    AkkaServiceClient akkaServiceClientService();
    RequestHeaderService requestHeaderService();
    ResponseHeaderService responseHeaderService();
    HealthCheckService healthCheckService();
    <T> T filterChainBuilder();
    <T> T  reposeConfigurationInformation();
    
   <T extends ServiceContext<?>> T getContext(Class<T> clazz);

}
