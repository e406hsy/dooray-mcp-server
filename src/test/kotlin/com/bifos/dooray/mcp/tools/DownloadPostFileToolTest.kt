package com.bifos.dooray.mcp.tools

import com.bifos.dooray.mcp.client.DoorayClient
import com.bifos.dooray.mcp.service.ProjectResolver
import com.bifos.dooray.mcp.types.DoorayApiHeader
import com.bifos.dooray.mcp.types.PostFileMeta
import com.bifos.dooray.mcp.types.PostFileMetaResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class DownloadPostFileToolTest {

    private fun successHeader() = DoorayApiHeader(isSuccessful = true, resultCode = 0, resultMessage = "")

    private fun meta(name: String = "image.gif", id: String = "file1") =
        PostFileMetaResponse(
            header = successHeader(),
            result = PostFileMeta(id = id, name = name, size = 5, mimeType = "image/gif")
        )

    private fun request(vararg args: Pair<String, String>): CallToolRequest {
        val mockRequest = mockk<CallToolRequest>()
        every { mockRequest.arguments } returns buildJsonObject { args.forEach { (k, v) -> put(k, v) } }
        return mockRequest
    }

    private fun resolver(): ProjectResolver {
        val resolver = mockk<ProjectResolver>()
        coEvery { resolver.resolveProjectId("project1") } returns "project1"
        return resolver
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - output_dir에 원본 파일명으로 저장하고 경로를 응답한다")
    fun downloadsFileIntoOutputDir(@TempDir tempDir: File) = runTest {
        // given
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val client = mockk<DoorayClient>()
        coEvery { client.getPostFileMeta("project1", "post1", "file1") } returns meta()
        coEvery { client.downloadPostFile("project1", "post1", "file1") } returns bytes

        // when
        val handler = downloadPostFileHandler(client, resolver())
        val result = handler(
            mockk<ClientConnection>(),
            request(
                "project_id" to "project1",
                "post_id" to "post1",
                "file_id" to "file1",
                "output_dir" to tempDir.absolutePath
            )
        )

        // then
        val saved = File(tempDir, "image.gif")
        assertTrue(saved.exists(), "파일이 저장되어야 합니다")
        assertContentEquals(bytes, saved.readBytes())

        val responseText = (result.content.first() as TextContent).text
        assertContains(responseText, "\"success\": true")
        assertContains(responseText, "image.gif")
        assertContains(responseText, saved.absolutePath)
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - file_id 누락 에러")
    fun missingFileId() = runTest {
        val handler = downloadPostFileHandler(mockk<DoorayClient>(), mockk<ProjectResolver>(relaxed = true))
        val result = handler(mockk<ClientConnection>(), request("project_id" to "project1", "post_id" to "post1"))

        val responseText = (result.content.first() as TextContent).text
        assertContains(responseText, "\"isError\": true")
        assertContains(responseText, "MISSING_FILE_ID")
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - 메타 조회 API 에러 시 다운로드 없이 에러를 응답한다")
    fun metaApiError(@TempDir tempDir: File) = runTest {
        val client = mockk<DoorayClient>()
        coEvery { client.getPostFileMeta(any(), any(), any()) } returns
            PostFileMetaResponse(
                header = DoorayApiHeader(isSuccessful = false, resultCode = 404, resultMessage = "File not found"),
                result = PostFileMeta(id = "file1", name = "", size = 0)
            )

        val handler = downloadPostFileHandler(client, resolver())
        val result = handler(
            mockk<ClientConnection>(),
            request("project_id" to "project1", "post_id" to "post1", "file_id" to "file1", "output_dir" to tempDir.absolutePath)
        )

        val responseText = (result.content.first() as TextContent).text
        assertContains(responseText, "\"isError\": true")
        assertContains(responseText, "DOORAY_API_404")
        assertContains(responseText, "File not found")
        assertTrue(tempDir.listFiles().isNullOrEmpty(), "에러 시 파일이 저장되면 안 됩니다")
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - output_dir 생략 시 기본 다운로드 디렉터리에 저장한다")
    fun defaultOutputDir() = runTest {
        val client = mockk<DoorayClient>()
        val fileName = "default-dir-${System.nanoTime()}.gif"
        coEvery { client.getPostFileMeta(any(), any(), any()) } returns meta(name = fileName)
        coEvery { client.downloadPostFile(any(), any(), any()) } returns byteArrayOf(9)

        val handler = downloadPostFileHandler(client, resolver())
        val result = handler(
            mockk<ClientConnection>(),
            request("project_id" to "project1", "post_id" to "post1", "file_id" to "file1")
        )

        val expected = File(defaultDownloadDir(), fileName)
        try {
            assertTrue(expected.exists(), "기본 디렉터리(${expected.parent})에 저장되어야 합니다")
            val responseText = (result.content.first() as TextContent).text
            assertContains(responseText, expected.absolutePath)
        } finally {
            expected.delete()
        }
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - 파일명의 경로 구분자를 제거해 output_dir 밖으로 나가지 않는다")
    fun sanitizesFileName(@TempDir tempDir: File) = runTest {
        val escaped = File(tempDir.parentFile, "evil.gif").apply { delete() }
        val client = mockk<DoorayClient>()
        coEvery { client.getPostFileMeta(any(), any(), any()) } returns meta(name = "../evil.gif")
        coEvery { client.downloadPostFile(any(), any(), any()) } returns byteArrayOf(1)

        val handler = downloadPostFileHandler(client, resolver())
        handler(
            mockk<ClientConnection>(),
            request("project_id" to "project1", "post_id" to "post1", "file_id" to "file1", "output_dir" to tempDir.absolutePath)
        )

        assertTrue(File(tempDir, "evil.gif").exists(), "output_dir 안에 정제된 이름으로 저장되어야 합니다")
        assertTrue(!escaped.exists(), "output_dir 밖에 저장되면 안 됩니다")
    }

    @Test
    @DisplayName("업무 첨부파일 다운로드 도구 - 같은 이름의 파일이 있으면 file_id를 붙여 덮어쓰지 않는다")
    fun avoidsOverwritingExistingFile(@TempDir tempDir: File) = runTest {
        val existing = File(tempDir, "image.gif").apply { writeBytes(byteArrayOf(0)) }
        val client = mockk<DoorayClient>()
        coEvery { client.getPostFileMeta(any(), any(), any()) } returns meta(name = "image.gif", id = "file1")
        coEvery { client.downloadPostFile(any(), any(), any()) } returns byteArrayOf(7, 7)

        val handler = downloadPostFileHandler(client, resolver())
        val result = handler(
            mockk<ClientConnection>(),
            request("project_id" to "project1", "post_id" to "post1", "file_id" to "file1", "output_dir" to tempDir.absolutePath)
        )

        assertContentEquals(byteArrayOf(0), existing.readBytes(), "기존 파일은 그대로여야 합니다")
        val renamed = File(tempDir, "image-file1.gif")
        assertContentEquals(byteArrayOf(7, 7), renamed.readBytes())
        assertContains((result.content.first() as TextContent).text, renamed.absolutePath)
    }
}
