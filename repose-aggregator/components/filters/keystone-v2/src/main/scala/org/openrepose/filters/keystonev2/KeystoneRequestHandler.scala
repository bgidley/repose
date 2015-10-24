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
package org.openrepose.filters.keystonev2

import java.io.InputStream
import java.util.{Calendar, GregorianCalendar}
import javax.servlet.http.HttpServletResponse._
import javax.ws.rs.core.MediaType

import com.fasterxml.jackson.core.JsonProcessingException
import com.typesafe.scalalogging.slf4j.LazyLogging
import org.apache.http.HttpHeaders
import org.apache.http.client.utils.DateUtils
import org.joda.time.format.ISODateTimeFormat
import org.openrepose.commons.utils.http.{CommonHttpHeader, ServiceClientResponse}
import org.openrepose.core.services.serviceclient.akka.AkkaServiceClient
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._
import play.api.libs.json._

import scala.collection.JavaConverters._
import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
 * Contains the functions which interact with the Keystone API.
 */
class KeystoneRequestHandler(identityServiceUri: String, akkaServiceClient: AkkaServiceClient, traceId: Option[String])
  extends LazyLogging {

  import KeystoneRequestHandler._

  /**
   * Call to the Keystone service to get the admin token
   * @return a Successful token, or a Failure
   */
  final def getAdminToken(adminUsername: String, adminPassword: String): Try[String] = {
    //authenticate, or get the admin token
    val authenticationPayload = Json.obj(
      "auth" -> Json.obj(
        "passwordCredentials" -> Json.obj(
          "username" -> adminUsername,
          "password" -> adminPassword
        )
      )
    )

    val akkaResponse = Try(akkaServiceClient.post(ADMIN_TOKEN_KEY,
      s"$identityServiceUri$TOKEN_ENDPOINT",
      (Map(CommonHttpHeader.ACCEPT.toString -> MediaType.APPLICATION_JSON)
        ++ traceId.map(CommonHttpHeader.TRACE_GUID.toString -> _)).asJava,
      Json.stringify(authenticationPayload),
      MediaType.APPLICATION_JSON_TYPE
    ))

    akkaResponse match {
      case Success(serviceClientResponse) =>
        serviceClientResponse.getStatus match {
          case statusCode if statusCode >= 200 && statusCode < 300 =>
            val jsonResponse = Source.fromInputStream(serviceClientResponse.getData).getLines().mkString("")
            val json = Json.parse(jsonResponse)
            Try(Success((json \ "access" \ "token" \ "id").as[String])) match {
              case Success(s) => s
              case Failure(f) =>
                Failure(IdentityCommunicationException("Token not found in identity response during admin authentication", f))
            }
          case statusCode@(SC_REQUEST_ENTITY_TOO_LARGE | SC_TOO_MANY_REQUESTS) =>
            Failure(OverLimitException(statusCode, buildRetryValue(serviceClientResponse), "Rate limited when getting admin token"))
          case statusCode if statusCode >= 500 =>
            Failure(IdentityCommunicationException("Identity Service not available to get admin token"))
          case _ => Failure(IdentityResponseProcessingException("Unable to successfully get admin token from Identity"))
        }
      case Failure(x) => Failure(IdentityResponseProcessingException("Failure communicating with identity during admin authentication", x))
    }
  }

  final def validateToken(validatingToken: String, validatableToken: String): Try[ValidToken] = {
    def extractUserInformation(keystoneResponse: InputStream): Try[ValidToken] = {
      val input: String = Source.fromInputStream(keystoneResponse).getLines mkString ""
      try {
        val json = Json.parse(input)
        //Have to convert it to a vector, because List isn't serializeable in 2.10
        val userId = (json \ "access" \ "user" \ "id").as[String]
        val roleNames = (json \ "access" \ "user" \ "roles" \\ "name").map(_.as[String]).toVector
        val expirationDate = iso8601ToRfc1123((json \ "access" \ "token" \ "expires").as[String])
        val username = (json \ "access" \ "user" \ "name").asOpt[String]
        val defaultTenantId = (json \ "access" \ "token" \ "tenant" \ "id").asOpt[String]
        val tenantIds = (json \ "access" \ "user" \ "roles" \\ "tenantId").map(_.as[String]).toVector
        val tenantName = (json \ "access" \ "token" \ "tenant" \ "name").asOpt[String]
        val defaultRegion = (json \ "access" \ "user" \ "RAX-AUTH:defaultRegion").asOpt[String]
        val contactId = (json \ "access" \ "user" \ "RAX-AUTH:contactId").asOpt[String]
        val impersonatorId = (json \ "access" \ "RAX-AUTH:impersonator" \ "id").asOpt[String]
        val impersonatorName = (json \ "access" \ "RAX-AUTH:impersonator" \ "name").asOpt[String]
        val impersonatorRoles = (json \ "access" \ "RAX-AUTH:impersonator" \ "roles" \\ "name").map(_.as[String]).toVector
        val validToken = ValidToken(expirationDate,
          userId,
          roleNames,
          username,
          tenantName,
          defaultTenantId,
          tenantIds,
          impersonatorId,
          impersonatorName,
          impersonatorRoles,
          defaultRegion,
          contactId)

        Success(validToken)
      } catch {
        case oops@(_: JsResultException | _: JsonProcessingException) =>
          Failure(IdentityCommunicationException("Unable to parse JSON from identity validate token response", oops))
      }
    }

    val akkaResponse = Try(akkaServiceClient.get(s"$TOKEN_KEY_PREFIX$validatableToken",
      s"$identityServiceUri$TOKEN_ENDPOINT/$validatableToken",
      (Map(CommonHttpHeader.AUTH_TOKEN.toString -> validatingToken,
        CommonHttpHeader.ACCEPT.toString -> MediaType.APPLICATION_JSON)
        ++ traceId.map(CommonHttpHeader.TRACE_GUID.toString -> _)).asJava))

    handleResponse("validate token", akkaResponse, extractUserInformation)
  }

  final def getEndpointsForToken(authenticatingToken: String, forToken: String): Try[EndpointsData] = {
    def extractEndpointInfo(inputStream: InputStream): Try[EndpointsData] = {
      implicit val endpointsReader = (
        (JsPath \ "region").readNullable[String] and
          (JsPath \ "name").readNullable[String] and
          (JsPath \ "type").readNullable[String] and
          (JsPath \ "publicURL").read[String]
        )(Endpoint.apply _)

      val jsonString = Source.fromInputStream(inputStream).getLines mkString ""
      val json = Json.parse(jsonString)

      //Have to convert it to a vector, because List isn't serializeable in 2.10
      (json \ "endpoints").validate[Vector[Endpoint]] match {
        case s: JsSuccess[Vector[Endpoint]] =>
          val endpoints = s.get
          Success(new EndpointsData(jsonString, endpoints))
        case f: JsError =>
          Failure(IdentityCommunicationException("Identity didn't respond with proper Endpoints JSON"))
      }
    }

    val akkaResponse = Try(akkaServiceClient.get(s"$ENDPOINTS_KEY_PREFIX$forToken",
      s"$identityServiceUri${ENDPOINTS_ENDPOINT(forToken)}",
      (Map(CommonHttpHeader.AUTH_TOKEN.toString -> authenticatingToken,
        CommonHttpHeader.ACCEPT.toString -> MediaType.APPLICATION_JSON)
        ++ traceId.map(CommonHttpHeader.TRACE_GUID.toString -> _)).asJava))

    handleResponse("endpoints", akkaResponse, extractEndpointInfo)
  }

  final def getGroups(authenticatingToken: String, forToken: String): Try[Vector[String]] = {
    def extractGroupInfo(inputStream: InputStream): Try[Vector[String]] = {
      Try {
        val input: String = Source.fromInputStream(inputStream).getLines mkString ""
        val json = Json.parse(input)

        (json \ "RAX-KSGRP:groups" \\ "id").map(_.as[String]).toVector
      }
    }

    val akkaResponse = Try(akkaServiceClient.get(s"$GROUPS_KEY_PREFIX$forToken",
      s"$identityServiceUri${GROUPS_ENDPOINT(forToken)}",
      (Map(CommonHttpHeader.AUTH_TOKEN.toString -> authenticatingToken,
        CommonHttpHeader.ACCEPT.toString -> MediaType.APPLICATION_JSON)
        ++ traceId.map(CommonHttpHeader.TRACE_GUID.toString -> _)).asJava))

    handleResponse("groups", akkaResponse, extractGroupInfo)
  }
}

object KeystoneRequestHandler {
  final val SC_TOO_MANY_REQUESTS = 429
  final val TOKEN_ENDPOINT = "/v2.0/tokens"
  final val GROUPS_ENDPOINT = (userId: String) => s"/v2.0/users/$userId/RAX-KSGRP"
  final val ENDPOINTS_ENDPOINT = (token: String) => s"/v2.0/tokens/$token/endpoints"
  final val ADMIN_TOKEN_KEY = "IDENTITY:V2:ADMIN_TOKEN"
  final val TOKEN_KEY_PREFIX = "IDENTITY:V2:TOKEN:"
  final val GROUPS_KEY_PREFIX = "IDENTITY:V2:GROUPS:"
  final val ENDPOINTS_KEY_PREFIX = "IDENTITY:V2:ENDPOINTS:"

  def iso8601ToRfc1123(iso: String) = {
    val dateTime = ISODateTimeFormat.dateTimeParser().parseDateTime(iso)
    DateUtils.formatDate(dateTime.toDate)
  }

  def buildRetryValue(response: ServiceClientResponse) = {
    response.getHeaders.find(header => HttpHeaders.RETRY_AFTER.equalsIgnoreCase(header.getName)) match {
      case Some(retryValue) => retryValue.getValue
      case _ =>
        val retryCalendar: Calendar = new GregorianCalendar
        retryCalendar.add(Calendar.SECOND, 5)
        DateUtils.formatDate(retryCalendar.getTime)
    }
  }

  def handleResponse[T](call: String, response: Try[ServiceClientResponse], onSuccess: InputStream => Try[T]): Try[T] = {
    response match {
      case Success(serviceClientResponse) =>
        serviceClientResponse.getStatus match {
          case statusCode if statusCode >= 200 && statusCode < 300 => onSuccess(serviceClientResponse.getData)
          case SC_BAD_REQUEST => Failure(BadRequestException(s"Bad $call request to identity"))
          case SC_UNAUTHORIZED =>
            Failure(AdminTokenUnauthorizedException(s"Admin token unauthorized to make $call request"))
          case SC_FORBIDDEN => Failure(IdentityAdminTokenException(s"Admin token forbidden from making $call request"))
          case SC_NOT_FOUND => Failure(InvalidTokenException(s"Token is not valid for $call request"))
          case statusCode@(SC_REQUEST_ENTITY_TOO_LARGE | SC_TOO_MANY_REQUESTS) =>
            Failure(OverLimitException(statusCode, buildRetryValue(serviceClientResponse), s"Rate limited when making $call request"))
          case statusCode if statusCode >= 500 =>
            Failure(IdentityCommunicationException(s"Identity Service not available for $call request"))
          case _ => Failure(IdentityResponseProcessingException(s"Unhandled response from Identity for $call request"))
        }
      case Failure(x) => Failure(IdentityResponseProcessingException(s"Failure communicating with Identity during $call request", x))
    }
  }

  case class EndpointsData(json: String, vector: Vector[Endpoint])

  trait IdentityException

  case class AdminTokenUnauthorizedException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class IdentityAdminTokenException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class IdentityResponseProcessingException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class InvalidTokenException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class BadRequestException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class IdentityCommunicationException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class OverLimitException(statusCode: Int, retryAfter: String, message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class UnparseableTenantException(message: String, cause: Throwable = null) extends Exception(message, cause) with IdentityException

  case class ValidToken(expirationDate: String,
                        userId: String,
                        roles: Seq[String],
                        username: Option[String],
                        tenantName: Option[String],
                        defaultTenantId: Option[String],
                        tenantIds: Seq[String],
                        impersonatorId: Option[String],
                        impersonatorName: Option[String],
                        impersonatorRoles: Seq[String],
                        defaultRegion: Option[String],
                        contactId: Option[String])

  case class Endpoint(region: Option[String], name: Option[String], endpointType: Option[String], publicURL: String) {
    /**
     * Determines whether or not this endpoint meets the requirements set forth by the values contained in
     * endpointRequirement for the purpose of authorization.
     *
     * @param endpointRequirement an endpoint containing fields with required values
     * @return true if this endpoint has field values matching those in the endpointRequirement, false otherwise
     */
    def meetsRequirement(endpointRequirement: Endpoint) = {
      def compare(available: Option[String], required: Option[String]) = (available, required) match {
        case (Some(x), Some(y)) => x == y
        case (None, Some(_)) => false
        case _ => true
      }

      this.publicURL == endpointRequirement.publicURL &&
        compare(this.region, endpointRequirement.region) &&
        compare(this.name, endpointRequirement.name) &&
        compare(this.endpointType, endpointRequirement.endpointType)
    }
  }

}
