package org.openrepose.filters.ratelimiting.util.combine;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.openrepose.commons.utils.transform.StreamTransform;
import org.openrepose.core.services.ratelimit.config.*;
import org.openrepose.filters.ratelimiting.util.LimitsEntityStreamTransformer;
import org.openrepose.filters.ratelimiting.util.TransformHelper;
import org.openrepose.core.services.ratelimit.RateLimitListBuilder;
import org.openrepose.core.services.ratelimit.cache.CachedRateLimit;

import javax.xml.bind.JAXBContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TODO: seems that sometimes this test includes namespaces on some of the elements.
 * Not while being run in the IDe, but sometimes in maven, and almost every time with gradle.
 */
public class CombineLimitsTransformTest {

    public static final String SIMPLE_URI_REGEX = "/loadbalancer/.*";
    public static final String COMPLEX_URI_REGEX = "/loadbalancer/vips/.*";
    public static final String SIMPLE_URI = "*loadbalancer*";
    public static final String COMPLEX_URI = "*loadbalancer/vips*";
    public static final String SIMPLE_ID = "12345-ABCDE";
    public static final String COMPLEX_ID = "09876-ZYXWV";

    public static final String COMBINER_XSL_LOCATION = "/META-INF/xslt/limits-combine.xsl";
    public static final ObjectFactory LIMITS_OBJECT_FACTORY = new ObjectFactory();

    //This validation pattern needs to take into account that there might be an xml namespace in there.
    //Seems that the namespace gets dropped on the <rates> tag
    //TODO: a better solution to this would be to xpath out the elements and ensure that the xpath structure matches
    //The below regex is brittle and I just did to get it passing again.
    //Don't use regexp on XML: http://stackoverflow.com/a/1732454/423218
    private final Pattern validationPattern = Pattern.compile(".*(<[\\S:]*rates xmlns.*>.*</[\\S:]*rates>).*(<absolute>.*</absolute>).*", Pattern.DOTALL);
    private final Pattern validationPatternJson = Pattern.compile(".*\"rate\":.*(\"absolute\":).*", Pattern.DOTALL);
    private StreamTransform<LimitsTransformPair, OutputStream> combiner;

    @BeforeClass
    public static void setUpClass() {
        System.setProperty("javax.xml.parsers.DocumentBuilderFactory",
                "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl");
    }

    @Before
    public void standUp() throws Exception {
        combiner = new CombinedLimitsTransformer(
                TransformHelper.getTemplatesFromInputStream(
                        LimitsEntityStreamTransformer.class.getResourceAsStream(COMBINER_XSL_LOCATION)),
                JAXBContext.newInstance(LIMITS_OBJECT_FACTORY.getClass()), LIMITS_OBJECT_FACTORY);
    }

    @Test
    public void shouldCombineInputStreamWithJaxbElement() throws Exception {
        final InputStream is = CombineLimitsTransformTest.class.getResourceAsStream(
                "/META-INF/schema/examples/absolute-limits.xml");

        RateLimitList rll = createRateLimitList();

        final LimitsTransformPair tPair = new LimitsTransformPair(is, rll);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        combiner.transform(tPair, output);

        final String actual = output.toString();
        final Matcher matcher = validationPattern.matcher(actual);

        try {
            assertTrue("Combined limits must match expected output pattern", matcher.matches());
            assertNotNull("Combined limits must include rate limits", matcher.group(1));
            assertNotNull("Combined limits must include absolute limits", matcher.group(2));
        } catch (AssertionError e) {
            System.err.println("================================================================================");
            System.err.println("This is the CombineLimitsTransformTest AssertionError output:");
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("is = " + is);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("rll = " + rll);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("tPair = " + tPair);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("output = " + output);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("combiner = " + combiner);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("actual = " + actual);
            System.err.println("--------------------------------------------------------------------------------");
            System.err.println("matcher = " + matcher);
            System.err.println("================================================================================");
            throw e;
        }
    }

    @Test
    @Ignore
    public void shouldCombineInputStreamWithJaxbElementJson() throws Exception {
        final InputStream is = CombineLimitsTransformTest.class.getResourceAsStream(
                "/META-INF/schema/examples/absolute-limits.json");

        RateLimitList rll = createRateLimitList();

        final LimitsTransformPair tPair = new LimitsTransformPair(is, rll);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        combiner.transform(tPair, output);

        final String actual = output.toString();
        final Matcher matcher = validationPatternJson.matcher(actual);

        assertTrue("Combined limits must match expected output pattern", matcher.matches());

        assertNotNull("Combined limits must include rate limits", matcher.group(1));
        assertNotNull("Combined limits must include absolute limits", matcher.group(2));
    }

    private RateLimitList createRateLimitList() {
        final Map<String, CachedRateLimit> cacheMap;
        final ConfiguredLimitGroup configuredLimitGroup;

        LinkedList<HttpMethod> methods = new LinkedList<HttpMethod>();
        methods.add(HttpMethod.GET);
        methods.add(HttpMethod.PUT);
        methods.add(HttpMethod.POST);
        methods.add(HttpMethod.DELETE);

        cacheMap = new HashMap<String, CachedRateLimit>();
        configuredLimitGroup = new ConfiguredLimitGroup();

        configuredLimitGroup.setDefault(Boolean.TRUE);
        configuredLimitGroup.setId("configured-limit-group");
        configuredLimitGroup.getGroups().add("user");

        cacheMap.put(SIMPLE_ID, new CachedRateLimit(newLimitConfig(SIMPLE_ID, SIMPLE_URI, SIMPLE_URI_REGEX, methods), 1));

        configuredLimitGroup.getLimit().add(newLimitConfig(SIMPLE_ID, SIMPLE_URI, SIMPLE_URI_REGEX, methods));

        cacheMap.put(COMPLEX_ID, new CachedRateLimit(newLimitConfig(COMPLEX_ID, COMPLEX_URI, COMPLEX_URI_REGEX, methods), 1));

        configuredLimitGroup.getLimit().add(newLimitConfig(COMPLEX_ID, COMPLEX_URI, COMPLEX_URI_REGEX, methods));

        return new RateLimitListBuilder(cacheMap, configuredLimitGroup).toRateLimitList();
    }

    private ConfiguredRatelimit newLimitConfig(String limitId, String uri, String uriRegex, LinkedList<HttpMethod> methods) {
        final ConfiguredRatelimit configuredRateLimit = new ConfiguredRatelimit();

        configuredRateLimit.setId(limitId);
        configuredRateLimit.setUnit(TimeUnit.HOUR);
        configuredRateLimit.setUri(uri);
        configuredRateLimit.setUriRegex(uriRegex);
        configuredRateLimit.setValue(20);
        for (HttpMethod m : methods) {
            configuredRateLimit.getHttpMethods().add(m);
        }

        return configuredRateLimit;
    }
}
