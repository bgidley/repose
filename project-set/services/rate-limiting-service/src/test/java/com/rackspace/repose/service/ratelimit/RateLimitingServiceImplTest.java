package com.rackspace.repose.service.ratelimit;

import com.rackspace.repose.service.limits.schema.HttpMethod;
import com.rackspace.repose.service.limits.schema.RateLimitList;
import com.rackspace.repose.service.limits.schema.TimeUnit;
import com.rackspace.repose.service.ratelimit.cache.CachedRateLimit;
import com.rackspace.repose.service.ratelimit.cache.ManagedRateLimitCache;
import com.rackspace.repose.service.ratelimit.cache.NextAvailableResponse;
import com.rackspace.repose.service.ratelimit.cache.RateLimitCache;
import com.rackspace.repose.service.ratelimit.config.ConfiguredLimitGroup;
import com.rackspace.repose.service.ratelimit.config.ConfiguredRatelimit;
import com.rackspace.repose.service.ratelimit.config.RateLimitingConfiguration;
import com.rackspace.repose.service.ratelimit.exception.OverLimitException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Ignore;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import static org.mockito.Mockito.*;

@RunWith(Enclosed.class)
public class RateLimitingServiceImplTest {

   public static class WhenTestingRateLimitService extends RateLimitTestContext {

      RateLimitingService rateLimitingService;
      RateLimitCache cache;
      RateLimitingConfiguration config;
      String group1 = "group1";
      ConfiguredRatelimit rl1;
      private Map<String, CachedRateLimit> cacheMap;
      private ConfiguredLimitGroup configuredLimitGroup;

      @Before
      public final void beforeAll() {


         cache = mock(ManagedRateLimitCache.class);
         config = new RateLimitingConfiguration();

         cacheMap = new HashMap<String, CachedRateLimit>();
         configuredLimitGroup = new ConfiguredLimitGroup();

         configuredLimitGroup.setDefault(Boolean.TRUE);
         configuredLimitGroup.setId("configured-limit-group");
         configuredLimitGroup.getGroups().add("user");

         cacheMap.put(SIMPLE_URI, newCachedRateLimitFor(SIMPLE_URI, SIMPLE_URI_REGEX, HttpMethod.GET, HttpMethod.PUT));

         configuredLimitGroup.getLimit().add(newLimitFor(SIMPLE_URI, SIMPLE_URI_REGEX, HttpMethod.GET));
         configuredLimitGroup.getLimit().add(newLimitFor(SIMPLE_URI, SIMPLE_URI_REGEX, HttpMethod.PUT));
         configuredLimitGroup.getLimit().add(newLimitFor(SIMPLE_URI, SIMPLE_URI_REGEX, HttpMethod.DELETE));
         configuredLimitGroup.getLimit().add(newLimitFor(SIMPLE_URI, SIMPLE_URI_REGEX, HttpMethod.POST));

         cacheMap.put(COMPLEX_URI_REGEX, newCachedRateLimitFor(COMPLEX_URI, COMPLEX_URI_REGEX, HttpMethod.GET, HttpMethod.PUT));

         configuredLimitGroup.getLimit().add(newLimitFor(COMPLEX_URI, COMPLEX_URI_REGEX, HttpMethod.GET));
         configuredLimitGroup.getLimit().add(newLimitFor(COMPLEX_URI, COMPLEX_URI_REGEX, HttpMethod.DELETE));
         configuredLimitGroup.getLimit().add(newLimitFor(COMPLEX_URI, COMPLEX_URI_REGEX, HttpMethod.PUT));
         configuredLimitGroup.getLimit().add(newLimitFor(COMPLEX_URI, COMPLEX_URI_REGEX, HttpMethod.POST));

         config.getLimitGroup().add(configuredLimitGroup);

         when(cache.getUserRateLimits("usertest1")).thenReturn(cacheMap);

         rateLimitingService = new RateLimitingServiceImpl(cache, config);
      }

      @Test
      public void shouldReturnLimitsOnQuery() {

         List<String> groups = new ArrayList<String>();
         groups.add("configure-limit-group");
         RateLimitList list = rateLimitingService.queryLimits("user", groups);

         assertNotNull(list);

      }

      @Test(expected = IllegalArgumentException.class)
      public void shouldReturnExceptionOnNullUser() {
         List<String> groups = new ArrayList<String>();
         groups.add("configure-limit-group");
         RateLimitList list = null;

         list = rateLimitingService.queryLimits(null, groups);


      }

      @Test
      public void shouldTrackLimits() throws IOException, OverLimitException{
         List<String> groups = new ArrayList<String>();
         groups.add("configure-limit-group");

         when(cache.updateLimit(any(HttpMethod.class), any(String.class), any(String.class),
                 any(ConfiguredRatelimit.class))).thenReturn(new NextAvailableResponse(true, new Date(), 10));
         
         rateLimitingService.trackLimits("user", groups, "/loadbalancer/something", "GET");
      }
      
      @Test(expected = OverLimitException.class)
      public void shouldThrowOverLimits() throws IOException, OverLimitException{
         List<String> groups = new ArrayList<String>();
         groups.add("configure-limit-group");

         when(cache.updateLimit(any(HttpMethod.class), any(String.class), any(String.class),
                 any(ConfiguredRatelimit.class))).thenReturn(new NextAvailableResponse(false, new Date(), 10));
         
         rateLimitingService.trackLimits("user", groups, "/loadbalancer/something", "GET");
      }
      
      @Test(expected = IllegalArgumentException.class)
      public void shouldThrowIllegalArgumentsOnNullUser() throws IOException, OverLimitException{
         List<String> groups = new ArrayList<String>();
         groups.add("configure-limit-group");

         when(cache.updateLimit(any(HttpMethod.class), any(String.class), any(String.class),
                 any(ConfiguredRatelimit.class))).thenReturn(new NextAvailableResponse(false, new Date(), 10));
         
         rateLimitingService.trackLimits(null, groups, "/loadbalancer/something", "GET");
      }
      
   }
}
