package eu.buney.kiche.ktor

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Integration tests for the Kiche Ktor client engine.
 *
 * Uses [KicheTestServer] (embedded HTTP/3 server) for full round-trip
 * over real QUIC + UDP on localhost, same as Ktor's own engine tests.
 */
class KicheHttpClientTest {

    private val testServer = KicheTestServer()
    private lateinit var client: HttpClient

    private val testUrl: String get() = testServer.baseUrl

    @BeforeTest
    fun setUp() {
        testServer.start()
        client = testServer.createClient()
    }

    @AfterTest
    fun tearDown() {
        client.close()
        testServer.stop()
    }

    //region Basic GET

    @Test
    fun `GET returns 200 with body`() = runBlocking {
        val response = client.get("$testUrl/hello")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("hello", response.bodyAsText())
    }

    @Test
    fun `GET empty body`() = runBlocking {
        val response = client.get("$testUrl/empty")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    //endregion

    //region HTTP Methods

    @Test
    fun `GET method is sent correctly`() = runBlocking {
        val response = client.get("$testUrl/echo-method")
        assertEquals("GET", response.bodyAsText())
    }

    @Test
    fun `POST method is sent correctly`() = runBlocking {
        val response = client.post("$testUrl/echo-method")
        assertEquals("POST", response.bodyAsText())
    }

    @Test
    fun `PUT method is sent correctly`() = runBlocking {
        val response = client.put("$testUrl/echo-method")
        assertEquals("PUT", response.bodyAsText())
    }

    @Test
    fun `DELETE method is sent correctly`() = runBlocking {
        val response = client.delete("$testUrl/echo-method")
        assertEquals("DELETE", response.bodyAsText())
    }

    @Test
    fun `PATCH method is sent correctly`() = runBlocking {
        val response = client.patch("$testUrl/echo-method")
        assertEquals("PATCH", response.bodyAsText())
    }

    @Test
    fun `HEAD method is sent correctly`() = runBlocking {
        val response = client.head("$testUrl/echo-method")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `OPTIONS method is sent correctly`() = runBlocking {
        val response = client.options("$testUrl/echo-method")
        assertEquals("OPTIONS", response.bodyAsText())
    }

    //endregion

    //region Request bodies

    @Test
    fun `POST echoes request body`() = runBlocking {
        val payload = "test request body"
        val response = client.post("$testUrl/echo-body") {
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `PUT echoes request body`() = runBlocking {
        val payload = "put body content"
        val response = client.put("$testUrl/echo-body") {
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `PATCH echoes request body`() = runBlocking {
        val payload = "patch body content"
        val response = client.patch("$testUrl/echo-body") {
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(payload, response.bodyAsText())
    }

    @Test
    fun `POST empty body`() = runBlocking {
        val response = client.post("$testUrl/echo-body") {
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("", response.bodyAsText())
    }

    @Test
    fun `POST binary body`() = runBlocking {
        val payload = ByteArray(256) { it.toByte() }
        val response = client.post("$testUrl/echo-body") {
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(payload, response.readRawBytes())
    }

    @Test
    fun `POST 1KB body`() = runBlocking {
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val response = client.post("$testUrl/echo-body") {
            setBody(payload)
        }
        assertContentEquals(payload, response.readRawBytes())
    }

    @Test
    fun `POST 10KB body`() = runBlocking {
        val payload = ByteArray(10_240) { (it % 256).toByte() }
        val response = client.post("$testUrl/echo-body") {
            setBody(payload)
        }
        assertContentEquals(payload, response.readRawBytes())
    }

    //endregion

    //region Status codes

    @Test
    fun `status 200 OK`() = runBlocking {
        assertEquals(HttpStatusCode.OK, client.get("$testUrl/status/200").status)
    }

    @Test
    fun `status 201 Created`() = runBlocking {
        assertEquals(HttpStatusCode.Created, client.get("$testUrl/status/201").status)
    }

    @Test
    fun `status 204 No Content`() = runBlocking {
        assertEquals(HttpStatusCode.NoContent, client.get("$testUrl/status/204").status)
    }

    @Test
    fun `status 301 Moved Permanently`() = runBlocking {
        assertEquals(HttpStatusCode.MovedPermanently, client.get("$testUrl/status/301").status)
    }

    @Test
    fun `status 400 Bad Request`() = runBlocking {
        assertEquals(HttpStatusCode.BadRequest, client.get("$testUrl/status/400").status)
    }

    @Test
    fun `status 401 Unauthorized`() = runBlocking {
        assertEquals(HttpStatusCode.Unauthorized, client.get("$testUrl/status/401").status)
    }

    @Test
    fun `status 403 Forbidden`() = runBlocking {
        assertEquals(HttpStatusCode.Forbidden, client.get("$testUrl/status/403").status)
    }

    @Test
    fun `status 404 Not Found`() = runBlocking {
        assertEquals(HttpStatusCode.NotFound, client.get("$testUrl/nonexistent").status)
    }

    @Test
    fun `status 500 Internal Server Error`() = runBlocking {
        assertEquals(HttpStatusCode.InternalServerError, client.get("$testUrl/status/500").status)
    }

    @Test
    fun `status 502 Bad Gateway`() = runBlocking {
        assertEquals(HttpStatusCode.BadGateway, client.get("$testUrl/status/502").status)
    }

    @Test
    fun `status 503 Service Unavailable`() = runBlocking {
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("$testUrl/status/503").status)
    }

    //endregion

    //region Protocol version

    @Test
    fun `response reports HTTP 3 protocol version`() = runBlocking {
        val response = client.get("$testUrl/hello")
        assertEquals("HTTP", response.version.name)
        assertEquals(3, response.version.major)
        assertEquals(0, response.version.minor)
    }

    //endregion

    //region Request headers

    @Test
    fun `single custom request header is sent`() = runBlocking {
        val response = client.get("$testUrl/headers") {
            header("X-Custom", "test-value")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("test-value", response.headers["x-echo-x-custom"])
    }

    @Test
    fun `multiple custom request headers are sent`() = runBlocking {
        val response = client.get("$testUrl/headers") {
            header("X-First", "one")
            header("X-Second", "two")
            header("X-Third", "three")
        }
        assertEquals("one", response.headers["x-echo-x-first"])
        assertEquals("two", response.headers["x-echo-x-second"])
        assertEquals("three", response.headers["x-echo-x-third"])
    }

    @Test
    fun `user-agent header is sent`() = runBlocking {
        val response = client.get("$testUrl/headers") {
            header(HttpHeaders.UserAgent, "KicheTest/1.0")
        }
        assertEquals("KicheTest/1.0", response.headers["x-echo-user-agent"])
    }

    @Test
    fun `accept header is sent`() = runBlocking {
        val response = client.get("$testUrl/headers") {
            header(HttpHeaders.Accept, "application/json")
        }
        assertEquals("application/json", response.headers["x-echo-accept"])
    }

    //endregion

    //region Response headers

    @Test
    fun `multiple response header values`() = runBlocking {
        val response = client.get("$testUrl/multi-header")
        val values = response.headers.getAll("x-multi")
        assertNotNull(values)
        assertEquals(3, values.size)
        assertTrue("value1" in values)
        assertTrue("value2" in values)
        assertTrue("value3" in values)
    }

    @Test
    fun `single response header value`() = runBlocking {
        val response = client.get("$testUrl/multi-header")
        assertEquals("only", response.headers["x-single"])
    }

    //endregion

    //region Query parameters

    @Test
    fun `query parameters are sent`() = runBlocking {
        val response = client.get("$testUrl/query") {
            url {
                parameters.append("key", "value")
            }
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("key=value", response.bodyAsText())
    }

    @Test
    fun `multiple query parameters`() = runBlocking {
        val response = client.get("$testUrl/query") {
            url {
                parameters.append("a", "1")
                parameters.append("b", "2")
            }
        }
        val body = response.bodyAsText()
        assertTrue(body.contains("a=1"))
        assertTrue(body.contains("b=2"))
    }

    //endregion

    //region Content-Type

    @Test
    fun `content-type header is sent with body`() = runBlocking {
        val response = client.post("$testUrl/content-type") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertTrue(response.bodyAsText().contains("application/json"))
    }

    @Test
    fun `plain text content-type`() = runBlocking {
        val response = client.post("$testUrl/content-type") {
            contentType(ContentType.Text.Plain)
            setBody("hello")
        }
        assertTrue(response.bodyAsText().contains("text/plain"))
    }

    //endregion

    //region Response body sizes

    @Test
    fun `receive 1KB body`() = runBlocking {
        val response = client.get("$testUrl/large/1024")
        val body = response.readRawBytes()
        assertEquals(1024, body.size)
        assertTrue(body.all { it == 'A'.code.toByte() })
    }

    @Test
    fun `receive 64KB body`() = runBlocking {
        val response = client.get("$testUrl/large/65536")
        val body = response.readRawBytes()
        assertEquals(65536, body.size)
        assertTrue(body.all { it == 'A'.code.toByte() })
    }

    @Test
    fun `receive 256KB body`() = runBlocking {
        val response = client.get("$testUrl/large/262144")
        val body = response.readRawBytes()
        assertEquals(262144, body.size)
        assertTrue(body.all { it == 'A'.code.toByte() })
    }

    @Test
    fun `receive 1MB body`() = runBlocking {
        val response = client.get("$testUrl/large/1048576")
        val body = response.readRawBytes()
        assertEquals(1048576, body.size)
    }

    //endregion

    //region Sequential requests

    @Test
    fun `sequential requests on same client`() = runBlocking {
        val r1 = client.get("$testUrl/hello")
        assertEquals("hello", r1.bodyAsText())

        val r2 = client.get("$testUrl/echo-method")
        assertEquals("GET", r2.bodyAsText())

        val r3 = client.post("$testUrl/echo-body") {
            setBody("third")
        }
        assertEquals("third", r3.bodyAsText())
    }

    //endregion
}
