/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package features.core.tracing

import framework.ReposeValveTest
import groovy.json.JsonSlurper
import org.apache.commons.codec.binary.Base64
import org.openrepose.commons.utils.http.CommonHttpHeader
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

import java.util.regex.Pattern

class RewriteTracingHeaderTest extends ReposeValveTest {
    def static final Pattern UUID_PATTERN = ~/\p{XDigit}{8}-\p{XDigit}{4}-\p{XDigit}{4}-\p{XDigit}{4}-\p{XDigit}{12}/

    def setupSpec() {
        reposeLogSearch.cleanLog()
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/core/powerfilter/tracing", params)
        repose.configurationProvider.applyConfigs("features/core/tracing/rewritetransid", params)

        repose.start()
    }

    @Unroll
    def "should not pass the externally provided tracing header (#tracingHeaderInput) through the filter chain"() {
        when:
        MessageChain mc = deproxy.makeRequest(
                url: reposeEndpoint, headers: [(CommonHttpHeader.TRACE_GUID.toString()): tracingHeaderInput, via:'some_via'])

        def tracingHeader = mc.handlings[0].request.headers.getFirstValue(CommonHttpHeader.TRACE_GUID.toString())
        def tracingValues = new JsonSlurper().parseText(new String(Base64.decodeBase64(tracingHeader)))

        then:
        tracingValues['requestId'] != 'test-guid-for-rewrite'
        tracingValues['requestId'] ==~ UUID_PATTERN
        tracingValues['origin'] == 'some_via'

        where:
        tracingHeaderInput << ['test-guid-for-rewrite', '', '{"requestId": "4"}', 'eyJyZXF1ZXN0SWQiOiAiNCJ9']
    }

    def "should pass a new tracing header through the filter chain if one was not provided"() {
        when:
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, headers: [via:'some_via'])

        def tracingHeader = mc.handlings[0].request.headers.getFirstValue(CommonHttpHeader.TRACE_GUID.toString())
        def tracingValues = new JsonSlurper().parseText(new String(Base64.decodeBase64(tracingHeader)))

        then:
        tracingValues['requestId'] ==~ UUID_PATTERN
        tracingValues['origin'] == 'some_via'
    }

    @Unroll
    def "should not return the externally provided tracing header (#tracingHeaderInput) if one was provided"() {
        when:
        MessageChain mc = deproxy.makeRequest(
                url: reposeEndpoint, headers: [(CommonHttpHeader.TRACE_GUID.toString()): tracingHeaderInput, via:'some_via'])

        def tracingHeader = mc.receivedResponse.headers.getFirstValue(CommonHttpHeader.TRACE_GUID.toString())
        def tracingValues = new JsonSlurper().parseText(new String(Base64.decodeBase64(tracingHeader)))

        then:
        tracingValues['requestId'] != 'test-guid-for-rewrite'
        tracingValues['requestId'] ==~ UUID_PATTERN
        tracingValues['origin'] == 'some_via'

        where:
        tracingHeaderInput << ['test-guid-for-rewrite', '', '{"requestId": "4"}', 'eyJyZXF1ZXN0SWQiOiAiNCJ9']
    }

    def "should return a tracing header if one was not provided"() {
        when:
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, headers: [via:'some_via'])

        def tracingHeader = mc.receivedResponse.headers.getFirstValue(CommonHttpHeader.TRACE_GUID.toString())
        def tracingValues = new JsonSlurper().parseText(new String(Base64.decodeBase64(tracingHeader)))

        then:
        tracingValues['requestId'] ==~ UUID_PATTERN
        tracingValues['origin'] == 'some_via'
    }
}
