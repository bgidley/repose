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

package org.openrepose.commons.utils.logging.apache.format.stock

import javax.servlet.http.{HttpServletRequest, HttpServletResponse}

import org.junit.runner.RunWith
import org.openrepose.commons.utils.logging.apache.HttpLogFormatterState
import org.openrepose.commons.utils.servlet.http.MutableHttpServletResponse
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

@RunWith(classOf[JUnitRunner])
class ResponseMessageHandlerTest extends FunSpec with BeforeAndAfter with GivenWhenThen with Matchers with MockitoSugar {
  val escapeThis = "\b\n\t\f\r\\\"'/&<>"
  val mockRequest = mock[HttpServletRequest]
  val mockResponse = mock[HttpServletResponse]
  var response: MutableHttpServletResponse = _

  before {
    response = MutableHttpServletResponse.wrap(mockRequest, mockResponse)
    response.sendError(0, escapeThis)
  }

  describe("the string should be properly escaped") {
    List(
      ("PLAIN", HttpLogFormatterState.PLAIN, "\b\n\t\f\r\\\"'/&<>"),
      ("JSON", HttpLogFormatterState.JSON, "\\b\\n\\t\\f\\r\\\\\\\"'\\/&<>"),
      ("XML", HttpLogFormatterState.XML, "\n\t\r\\&quot;&apos;/&amp;&lt;&gt;")
    ).foreach { case (name, state, expected) =>
      it(s"should escape the string for $name") {
        Given(s"an unencoded Java String object and a $name configured ResponseMessageHandler")
        val responseMessageHandler = new ResponseMessageHandler(state)

        When(s"the Java String is $name encoded")
        val result = responseMessageHandler.handle(mockRequest, response)

        Then(s"the result should be $name compatible")
        result shouldBe expected
      }
    }
  }
}
