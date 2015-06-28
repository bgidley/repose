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
package features.filters.keystonev2.akkatimeout

import framework.ReposeValveTest
import framework.category.Slow
import framework.mocks.MockIdentityV2Service
import org.joda.time.DateTime
import org.junit.experimental.categories.Category
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain

import javax.servlet.http.HttpServletResponse

/**
 * Created by jennyvo on 1/5/15.
 *  Akka timeout "matches" http connection pool timeout.
 *  Test is checking if the HttpClient connection timeout is less than 50 seconds,
 *  then the client is notified then and not delayed until 50 seconds.
 */
@Category(Slow)
class AkkaTimeoutSameAsHttpConnTimeoutTest extends ReposeValveTest {

    def static originEndpoint
    def static identityEndpoint

    def static MockIdentityV2Service fakeIdentityV2Service

    def setupSpec() {

        deproxy = new Deproxy()
        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/akkatimeout", params)
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityV2Service = new MockIdentityV2Service(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort,
                'identity service', null, fakeIdentityV2Service.handler)


    }

    def cleanupSpec() {
        if (deproxy)
            deproxy.shutdown()
        if (repose)
            repose.stop()
    }

    def setup() {
        fakeIdentityV2Service.resetHandlers()
    }

    def "akka timeout test, auth response time out is less than socket connection time out, but greater than the system default of 20 seconds"() {
        fakeIdentityV2Service.with {
            client_token = UUID.randomUUID().toString()
            tokenExpiresAt = DateTime.now().plusDays(1)
            client_tenantid = 613
            service_admin_role = "not-admin"
            client_userid = 1234
            sleeptime = 29000
        }

        when: "User passes a request through repose"
        MessageChain mc = deproxy.makeRequest(
                url: "$reposeEndpoint/servers/613/",
                method: 'GET',
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityV2Service.client_token
                ]
        )

        then: "Request should not be passed from repose"
        mc.receivedResponse.code == "200"
        mc.handlings.size() == 1
    }

    def "akka timeout test, auth response time out greater than socket connection time out"() {
        reposeLogSearch.cleanLog()
        fakeIdentityV2Service.with {
            client_token = UUID.randomUUID().toString()
            tokenExpiresAt = DateTime.now().plusDays(1)
            client_tenantid = 613
            service_admin_role = "not-admin"
            client_userid = 1234
            sleeptime = 35000
        }

        when: "User passes a request through repose"
        MessageChain mc = deproxy.makeRequest(
                url: "$reposeEndpoint/servers/613/",
                method: 'GET',
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityV2Service.client_token
                ]
        )

        then: "Request should not be passed from repose"
        mc.receivedResponse.code == HttpServletResponse.SC_GATEWAY_TIMEOUT.toString()
        mc.handlings.size() == 0
        sleep(1000)
        reposeLogSearch.searchByString("Error acquiring value from akka .GET. or the cache. Reason: Futures timed out after .31000 milliseconds.").size() > 0
        reposeLogSearch.searchByString("NullPointerException").size() == 0
    }
}
