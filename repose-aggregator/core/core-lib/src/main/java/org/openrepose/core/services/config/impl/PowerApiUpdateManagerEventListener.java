package org.openrepose.core.services.config.impl;

import org.openrepose.commons.config.manager.UpdateListener;
import org.openrepose.commons.config.resource.ConfigurationResource;
import org.openrepose.core.services.event.common.Event;
import org.openrepose.core.services.event.common.EventListener;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * @author fran
 */
public class PowerApiUpdateManagerEventListener implements EventListener<ConfigurationEvent, ConfigurationResource> {

    private final Map<String, Map<Integer, ParserListenerPair>> listenerMap;
    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(PowerApiUpdateManagerEventListener.class);

    public PowerApiUpdateManagerEventListener(Map<String, Map<Integer, ParserListenerPair>> listenerMap) {
        this.listenerMap = listenerMap;
    }

    @Override
    public void onEvent(Event<ConfigurationEvent, ConfigurationResource> e) {
        final Thread currentThread = Thread.currentThread();
        final ClassLoader previousClassLoader = currentThread.getContextClassLoader();
        final String payloadName = e.payload().name();
        Map<Integer, ParserListenerPair> listeners = getListenerMap(payloadName);
        
        LOG.info("Configuration event triggered for: " + payloadName);
        LOG.info("Notifying " + listeners.values().size() + " listeners");

        for (ParserListenerPair parserListener : listeners.values()) {
            UpdateListener updateListener = parserListener.getListener();
           
            if (updateListener != null) {
                LOG.info("Notifying " + updateListener.getClass().getName());
                
                currentThread.setContextClassLoader(parserListener.getClassLoader());
                try {
                    PowerApiConfigurationManager.loadConfig(parserListener.getFilterName(),
                            e.payload().name(),
                            updateListener,
                            parserListener.getParser(),
                            parserListener.getConfigurationInformation(),
                            e.payload());
                } finally {
                    currentThread.setContextClassLoader(previousClassLoader);
                }
            } else {
                LOG.warn("Update listener is null for " + payloadName);
            }
        }
    }

    public synchronized Map<Integer, ParserListenerPair> getListenerMap(String resourceName) {
        final Map<Integer, ParserListenerPair> mapReference = new HashMap<Integer, ParserListenerPair>(listenerMap.get(resourceName));

        return Collections.unmodifiableMap(mapReference);
    }
}
