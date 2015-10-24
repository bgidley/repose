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
package features.filters.apivalidator.jsonschemacheck

import framework.ReposeValveTest
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

/**
 * Created by jennyvo on 10/2/15.
 */
class JsonSchemaCheckTest extends ReposeValveTest {

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/apivalidator/jsonschemacheck", params)
        repose.start()
        repose.waitForNon500FromUrl(reposeEndpoint)
    }

    def cleanupSpec() {
        if (repose)
            repose.stop()
        if (deproxy)
            deproxy.shutdown()
    }

    def "PUT to /path/to/test Json checking should get 200"() {
        setup: "declare messageChain to be of type MessageChain"
        MessageChain messageChain

        def Map<String, String> headers = [
                "Accept"      : "application/json",
                "Content-Type": "application/json",
                "Host"        : "localhost",
                "User-Agent"  : "gdeproxy",
                "x-roles"     : "group1"
        ]

        def reqBody = """{
            "firstName" : "Test",
            "lastName" : "Repose",
            "age" : 100
            }"""

        when:
        messageChain = deproxy.makeRequest(url: reposeEndpoint + "/path/to/test",
                method: 'PUT', headers: headers,
                requestBody: reqBody
        )

        then:
        "result should be " + 200
        messageChain.receivedResponse.code.equals("200")
    }

    def "PUT to /path/to/something invalid path Json checking should get 404"() {
        setup: "declare messageChain to be of type MessageChain"
        MessageChain messageChain

        def Map<String, String> headers = [
                "Accept"      : "application/json",
                "Content-Type": "application/json",
                "Host"        : "localhost",
                "User-Agent"  : "gdeproxy",
                "x-roles"     : "group1"
        ]

        def reqBody = """{
            "firstName" : "Test",
            "lastName" : "Repose",
            "age" : 100
            }"""

        when:
        messageChain = deproxy.makeRequest(url: reposeEndpoint + "/path/to/something",
                method: 'PUT', headers: headers,
                requestBody: reqBody
        )

        then:
        "result should be " + 404
        messageChain.receivedResponse.code.equals("404")
    }

    def "PUT to /path/to/test Json checking Invalid Json should get 400"() {
        setup: "declare messageChain to be of type MessageChain"
        MessageChain messageChain

        def Map<String, String> headers = [
                "Accept"      : "application/json",
                "Content-Type": "application/json",
                "Host"        : "localhost",
                "User-Agent"  : "gdeproxy",
                "x-roles"     : "group1"
        ]

        def reqBody = """{
            "firstname" : "Test",
            "lastName" : "Repose",
            "age" : 100
            }"""

        when:
        messageChain = deproxy.makeRequest(url: reposeEndpoint + "/path/to/test",
                method: 'PUT', headers: headers,
                requestBody: reqBody
        )

        then:
        "result should be " + 400
        messageChain.receivedResponse.code.equals("400")
        messageChain.receivedResponse.body.toString().contains('Message Bad Content: object has missing required properties (["firstName"]')
    }

    def "POST to /path/to/post by pass Json checking should get 200"() {
        setup: "declare messageChain to be of type MessageChain"
        MessageChain messageChain

        def Map<String, String> headers = [
                "Accept"      : "application/json",
                "Content-Type": "application/json",
                "Host"        : "localhost",
                "User-Agent"  : "gdeproxy",
                "x-roles"     : "group1"
        ]

        def reqBody = """{
            "name" : "Test Repose",
            "age" : 100
            }"""

        when:
        messageChain = deproxy.makeRequest(url: reposeEndpoint + "/path/to/post",
                method: 'POST', headers: headers,
                requestBody: reqBody
        )

        then:
        "result should be " + 200
        messageChain.receivedResponse.code.equals("200")
    }

    @Unroll("#method to #path not allow resp 405")
    def "Method not allow to resources resp 405"() {
        setup: "declare messageChain to be of type MessageChain"
        MessageChain messageChain

        def Map<String, String> headers = [
                "Accept"      : "application/json",
                "Content-Type": "application/json",
                "Host"        : "localhost",
                "User-Agent"  : "gdeproxy",
                "x-roles"     : "group1"
        ]

        def reqBody = """{
            "firstName" : "Test",
            "lastName" : "Repose",
            "age" : 100
            }"""

        when:
        messageChain = deproxy.makeRequest(url: reposeEndpoint + path,
                method: method, headers: headers,
                requestBody: reqBody
        )

        then:
        "result should be " + 405
        messageChain.receivedResponse.code.equals("405")

        where:
        method | path
        "PUT"  | "/path/to/post"
        "POST" | "/path/to/test"
        "GET"  | "/path/to/post"
        "GET"  | "/path/to/test"
    }
}
