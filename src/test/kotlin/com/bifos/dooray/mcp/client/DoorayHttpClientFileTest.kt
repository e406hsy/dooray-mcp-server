package com.bifos.dooray.mcp.client

import com.bifos.dooray.mcp.exception.CustomException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DoorayHttpClientFileTest {

    private val apiKey = "TEST_KEY"
    private val metaJson =
        """{"header":{"isSuccessful":true,"resultCode":0,"resultMessage":""},""" +
            """"result":{"id":"f1","name":"image.gif","size":12345,"mimeType":"image/gif","createdAt":"2024-10-08T19:20:23+09:00",""" +
            """"creator":{"type":"member","member":{"organizationMemberId":"m1"}}}}"""
    private val redirectTarget =
        "https://file-api.dooray.com/downloads/project/v1/projects/p1/posts/po1/files/f1?media=raw"

    @Test
    @DisplayName("getPostFileMeta - media=meta 로 조회하여 파일 메타 정보를 파싱한다")
    fun getPostFileMeta() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(metaJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = DoorayHttpClient("https://api.dooray.com", apiKey, engine)

        val response = client.getPostFileMeta("p1", "po1", "f1")

        assertEquals("/project/v1/projects/p1/posts/po1/files/f1", requests.single().url.encodedPath)
        assertEquals("meta", requests.single().url.parameters["media"])
        assertEquals("image.gif", response.result.name)
        assertEquals(12345, response.result.size)
        assertEquals("image/gif", response.result.mimeType)
        assertEquals("m1", response.result.creator?.member?.organizationMemberId)
    }

    @Test
    @DisplayName("downloadPostFile - 307 리다이렉트를 Authorization 헤더 유지한 채 다른 호스트로 따라가 본문을 반환한다")
    fun downloadFollowsRedirectKeepingAuthorization() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.url.host) {
                "api.dooray.com" ->
                    respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, redirectTarget))
                "file-api.dooray.com" ->
                    if (request.headers[HttpHeaders.Authorization] == "dooray-api $apiKey")
                        respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/gif"))
                    else respond("", HttpStatusCode.Unauthorized)
                else -> error("unexpected host ${request.url.host}")
            }
        }
        val client = DoorayHttpClient("https://api.dooray.com", apiKey, engine)

        val bytes = client.downloadPostFile("p1", "po1", "f1")

        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(2, requests.size)
        assertEquals("/project/v1/projects/p1/posts/po1/files/f1", requests[0].url.encodedPath)
        assertEquals("raw", requests[0].url.parameters["media"])
        assertEquals(redirectTarget, requests[1].url.toString())
    }

    @Test
    @DisplayName("downloadPostFile - 리다이렉트 없이 200이면 본문을 그대로 반환한다")
    fun downloadWithoutRedirect() = runTest {
        val engine = MockEngine {
            respond(byteArrayOf(9, 8), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        }
        val client = DoorayHttpClient("https://api.dooray.com", apiKey, engine)

        assertContentEquals(byteArrayOf(9, 8), client.downloadPostFile("p1", "po1", "f1"))
    }

    @Test
    @DisplayName("downloadPostFile - 404 응답이면 CustomException 을 던진다")
    fun downloadNotFoundThrows() = runTest {
        val engine = MockEngine {
            respond(
                """{"header":{"isSuccessful":false,"resultCode":404,"resultMessage":"not found"}}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = DoorayHttpClient("https://api.dooray.com", apiKey, engine)

        val e = assertFailsWith<CustomException> { client.downloadPostFile("p1", "po1", "f1") }
        assertEquals(404, e.httpStatus)
    }
}
