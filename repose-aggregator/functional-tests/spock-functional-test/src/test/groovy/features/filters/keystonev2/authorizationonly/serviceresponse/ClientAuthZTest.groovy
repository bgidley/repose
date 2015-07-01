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
package features.filters.keystonev2.authorizationonly.serviceresponse

import framework.ReposeValveTest
import framework.category.Slow
import framework.mocks.MockIdentityV2Service
import org.junit.experimental.categories.Category
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import org.rackspace.deproxy.Response
import spock.lang.Unroll

@Category(Slow.class)
class ClientAuthZTest extends ReposeValveTest {

    def static originEndpoint
    def static identityEndpoint

    static MockIdentityV2Service fakeIdentityV2Service

    def setupSpec() {
        deproxy = new Deproxy()

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/authorizationonly/common", params)
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityV2Service = new MockIdentityV2Service(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort,
                'identity service', null, fakeIdentityV2Service.handler)
    }


    def cleanupSpec() {
        if (deproxy) {
            deproxy.shutdown()
        }
        repose.stop()
    }

    def "User's service endpoint test"() {
        given:
        fakeIdentityV2Service.endpointUrl = "localhost"

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss", method: 'GET', headers: ['X-Auth-Token': fakeIdentityV2Service.client_token])

        then: "User should receive a 200 response"
        mc.receivedResponse.code == "200"
        mc.handlings.size() == 1
    }

    def "Should split request headers according to rfc by default"() {
        given:
        def userAgentValue = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_8_4) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/29.0.1547.65 Safari/537.36"
        def reqHeaders =
                [
                        "user-agent"  : userAgentValue,
                        "x-pp-user"   : "usertest1, usertest2, usertest3",
                        "accept"      : "application/xml;q=1 , application/json;q=0.5",
                        'X-Auth-Token': fakeIdentityV2Service.client_token
                ]

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(
                url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss",
                method: 'GET',
                headers: reqHeaders)

        then: "User should receive a 200 response"
        mc.handlings.size() == 1
        mc.receivedResponse.code == "200"
        mc.handlings[0].request.getHeaders().findAll("user-agent").size() == 1
        mc.handlings[0].request.headers['user-agent'] == userAgentValue
        mc.handlings[0].request.getHeaders().findAll("x-pp-user").size() == 3
        mc.handlings[0].request.getHeaders().findAll("accept").size() == 2
    }

    def "Should not split response headers according to rfc"() {
        given: "Origin service returns headers "
        def respHeaders = ["location": "http://somehost.com/blah?a=b,c,d", "via": "application/xml;q=0.3, application/json;q=1"]
        def handler = { request -> return new Response(201, "Created", respHeaders, "") }
        Map<String, String> headers = ['X-Auth-Token': fakeIdentityV2Service.client_token]

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss",
                method: 'GET', headers: headers, defaultHandler: handler)

        then:
        mc.handlings.size() == 1
        mc.receivedResponse.code == "201"
        mc.receivedResponse.headers.findAll("location").size() == 1
        mc.receivedResponse.headers['location'] == "http://somehost.com/blah?a=b,c,d"
        mc.receivedResponse.headers.findAll("via").size() == 1
    }

    @Unroll("Requests - headers: #headerName with \"#headerValue\" keep its case")
    def "Requests - headers should keep its case in requests"() {

        when: "make a request with the given header and value"
        def headers = [
                'X-Auth-Token': fakeIdentityV2Service.client_token
        ]
        headers[headerName.toString()] = headerValue.toString()

        MessageChain mc = deproxy.makeRequest(
                url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss",
                method: 'GET',
                headers: headers)

        then: "the request should keep headerName and headerValue case"
        mc.handlings.size() == 1
        mc.handlings[0].request.headers.contains(headerName)
        mc.handlings[0].request.headers.getFirstValue(headerName) == headerValue


        where:
        headerName         | headerValue
        "Accept"           | "text/plain"
        "ACCEPT"           | "text/PLAIN"
        "accept"           | "TEXT/plain;q=0.2"
        "aCCept"           | "text/plain"
        "CONTENT-Encoding" | "identity"
        "Content-ENCODING" | "identity"
        //"content-encoding" | "idENtItY"
        //"Content-Encoding" | "IDENTITY"
    }

    @Unroll("Responses - headers: #headerName with \"#headerValue\" keep its case")
    def "Responses - header keep its case in responses"() {
        given:
        def headers = ['X-Auth-Token': fakeIdentityV2Service.client_token]
        when: "make a request with the given header and value"
        def respHeaders = [
                "location": "http://somehost.com/blah?a=b,c,d"
        ]
        respHeaders[headerName.toString()] = headerValue.toString()

        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss",
                method: 'GET', headers: headers, defaultHandler: { new Response(201, "Created", respHeaders, "") })

        then: "the response should keep headerName and headerValue case"
        mc.handlings.size() == 1
        mc.receivedResponse.headers.contains(headerName)
        mc.receivedResponse.headers.getFirstValue(headerName) == headerValue


        where:
        headerName     | headerValue
        "Content-Type" | "application/json"
        "CONTENT-Type" | "application/json"
        "Content-TYPE" | "application/json"
        //"content-type" | "application/xMl"
        //"Content-Type" | "APPLICATION/xml"
    }

    def "When user is not authorized should receive a 403 FORBIDDEN response"() {

        given: "IdentityService is configured with allowed endpoints that will differ from the user's requested endpoint"
        def token = UUID.randomUUID().toString()
        fakeIdentityV2Service.client_token = token
        fakeIdentityV2Service.originServicePort = 99999

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + token + "/ss", method: 'GET', headers: ['X-Auth-Token': token])
        def foundLogs = reposeLogSearch.searchByString("User token: " + token +
                ": The user's service catalog does not contain an endpoint that matches the endpoint configured in openstack-authorization.cfg.xml")

        then: "User should receive a 403 FORBIDDEN response"
        foundLogs.size() == 1
        mc.handlings.size() == 0
        mc.receivedResponse.code == "403"
    }

    def "User's invalid service endpoint should receive a 403 FORBIDDEN response "() {
        given: "IdentityService is configured with allowed endpoints that will differ from the user's requested endpoint"
        def token = UUID.randomUUID().toString()
        fakeIdentityV2Service.client_token = token
        fakeIdentityV2Service.originServicePort = properties.targetPort
        fakeIdentityV2Service.endpointUrl = "invalidurl"

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + token + "/ss", method: 'GET', headers: ['X-Auth-Token': token])
        def foundLogs = reposeLogSearch.searchByString("User token: " + token +
                ": The user's service catalog does not contain an endpoint that matches the endpoint configured in openstack-authorization.cfg.xml")

        then: "User should receive a 403 FORBIDDEN response"
        foundLogs.size() == 1
        mc.handlings.size() == 0
        mc.receivedResponse.code == "403"
    }

    def "Tracing header should include in request to Identity"() {

        fakeIdentityV2Service.with {
            client_token = UUID.randomUUID().toString()
            endpointUrl = "localhost"
        }

        when: "User passes a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/v1/" + fakeIdentityV2Service.client_token + "/ss",
                method: 'GET',
                headers: ['content-type': 'application/json', 'X-Auth-Token': fakeIdentityV2Service.client_token])

        then: "Things are forward to the origin, because we're not validating existence of tenant"
        mc.receivedResponse.code == "200"
        mc.handlings.size() == 1
        mc.orphanedHandlings.each {
            e -> assert e.request.headers.contains("x-trans-id")
        }
    }
}
