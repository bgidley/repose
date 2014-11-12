package org.openrepose.filters.authz;

import com.rackspace.httpdelegation.JavaDelegationManagerProxy;
import org.openrepose.common.auth.openstack.AuthenticationService;
import org.openrepose.commons.utils.StringUtilities;
import org.openrepose.commons.utils.http.CommonHttpHeader;
import org.openrepose.commons.utils.http.HttpStatusCode;
import org.openrepose.commons.utils.http.OpenStackServiceHeader;
import org.openrepose.commons.utils.servlet.http.ReadableHttpServletResponse;
import org.openrepose.components.authz.rackspace.config.DelegatingType;
import org.openrepose.components.authz.rackspace.config.IgnoreTenantRoles;
import org.openrepose.components.authz.rackspace.config.ServiceEndpoint;
import org.openrepose.core.filter.logic.FilterAction;
import org.openrepose.core.filter.logic.FilterDirector;
import org.openrepose.core.filter.logic.common.AbstractFilterLogicHandler;
import org.openrepose.core.filter.logic.impl.FilterDirectorImpl;
import org.openrepose.filters.authz.cache.CachedEndpoint;
import org.openrepose.filters.authz.cache.EndpointListCache;
import org.openstack.docs.identity.api.v2.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;

public class RequestAuthorizationHandler extends AbstractFilterLogicHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RequestAuthorizationHandler.class);
    private final AuthenticationService authenticationService;
    private final EndpointListCache endpointListCache;
    private final ServiceEndpoint myEndpoint;
    private final DelegatingType delegating;
    private final List<String> ignoreTenantRoles;

    public RequestAuthorizationHandler(AuthenticationService authenticationService, EndpointListCache endpointListCache,
                                       ServiceEndpoint myEndpoint, IgnoreTenantRoles ignoreTenantRoles, DelegatingType delegating) {
        this.authenticationService = authenticationService;
        this.endpointListCache = endpointListCache;
        this.myEndpoint = myEndpoint;
        this.delegating = delegating;
        this.ignoreTenantRoles = getListOfRoles(ignoreTenantRoles);
    }

    private List<String> getListOfRoles(IgnoreTenantRoles ignoreTenantRoles) {
        List<String> roles = new ArrayList<>();
        if(ignoreTenantRoles != null) {
            roles.addAll(ignoreTenantRoles.getIgnoreTenantRole());
            roles.addAll(ignoreTenantRoles.getRole());
        }
        return roles;
    }

    @Override
    public FilterDirector handleRequest(HttpServletRequest request, ReadableHttpServletResponse response) {
        final FilterDirector myDirector = new FilterDirectorImpl();
        myDirector.setFilterAction(FilterAction.RETURN);
        myDirector.setResponseStatus(HttpStatusCode.INTERNAL_SERVER_ERROR);
        String message = "Failure in authorization component";

        final String authenticationToken = request.getHeader(CommonHttpHeader.AUTH_TOKEN.toString());

        try {
            if (StringUtilities.isBlank(authenticationToken)) {
                // Reject if no token
                message = "Authentication token not found in X-Auth-Token header. Rejecting request.";
                LOG.debug(message);
                myDirector.setResponseStatus(HttpStatusCode.UNAUTHORIZED);
            } else if (adminRoleMatchIgnoringCase(request.getHeaders(OpenStackServiceHeader.ROLES.toString())) ||
                            isEndpointAuthorizedForToken(authenticationToken)) {
                myDirector.setFilterAction(FilterAction.PASS);
            } else {
                message = "User token: " + authenticationToken +
                        ": The user's service catalog does not contain an endpoint that matches " +
                        "the endpoint configured in openstack-authorization.cfg.xml: \"" +
                        myEndpoint.getHref() + "\".  User not authorized to access service.";
                LOG.info(message);
                myDirector.setResponseStatus(HttpStatusCode.FORBIDDEN);
            }
        } catch (Exception ex) {
            LOG.error(message + ex.getMessage(), ex);
            myDirector.setResponseStatus(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }

        if(delegating != null && myDirector.getFilterAction() != FilterAction.PASS) {
            myDirector.setFilterAction(FilterAction.PASS);
            for(Map.Entry<String, List<String>> mapHeaders : JavaDelegationManagerProxy.buildDelegationHeaders(myDirector.getResponseStatusCode(), "client-authorization", message, delegating.getQuality()).entrySet()) {
                for (String headerValue : mapHeaders.getValue()) {
                    myDirector.requestHeaderManager().appendHeader(mapHeaders.getKey(), headerValue);
                }
            }
        }
        return myDirector;
    }

    private boolean adminRoleMatchIgnoringCase(Enumeration<String> roleStringList) {
        List<String> roles = Collections.list(roleStringList);
        if(!roles.isEmpty()) {
            for (String ignoreTenantRole : ignoreTenantRoles) {
                for (String role : roles) {
                    if (ignoreTenantRole.equalsIgnoreCase(role)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isEndpointAuthorizedForToken(String userToken) {
        List<CachedEndpoint> cachedEndpoints = requestEndpointsForToken(userToken);
        if(cachedEndpoints != null) {
            for (CachedEndpoint authorizedEndpoint : cachedEndpoints) {
                if (StringUtilities.isBlank(authorizedEndpoint.getPublicUrl())) {
                    LOG.warn("Endpoint Public URL is null.  This is a violation of the OpenStack Identity Service contract.");
                }
                if (StringUtilities.isBlank(authorizedEndpoint.getType())) {
                    LOG.warn("Endpoint Type is null.  This is a violation of the OpenStack Identity Service contract.");
                }
                if (StringUtilities.nullSafeStartsWith(authorizedEndpoint.getPublicUrl(), myEndpoint.getHref())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<CachedEndpoint> requestEndpointsForToken(String userToken) {
        List<CachedEndpoint> cachedEndpoints = endpointListCache.getCachedEndpointsForToken(userToken);

        if (cachedEndpoints == null || cachedEndpoints.isEmpty()) {
            List<Endpoint> authorizedEndpoints = authenticationService.getEndpointsForToken(userToken);
            if(authorizedEndpoints != null) {
                cachedEndpoints = new LinkedList<>();
                for (Endpoint ep : authorizedEndpoints) {
                    cachedEndpoints.add(new CachedEndpoint(ep.getPublicURL(), ep.getRegion(), ep.getName(), ep.getType()));
                }
                try {
                    endpointListCache.cacheEndpointsForToken(userToken, cachedEndpoints);
                } catch (IOException ioe) {
                    LOG.error("Caching failure. Reason: " + ioe.getMessage(), ioe);
                }
            }
        }
        return cachedEndpoints;
    }
}
