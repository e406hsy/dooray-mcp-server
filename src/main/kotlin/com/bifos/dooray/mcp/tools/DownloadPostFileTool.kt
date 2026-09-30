package com.bifos.dooray.mcp.tools

import com.bifos.dooray.mcp.client.DoorayClient
import com.bifos.dooray.mcp.service.ProjectResolver
import com.bifos.dooray.mcp.types.PostFileDownloadResponseData
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** output_dir 미지정 시 사용하는 기본 다운로드 디렉터리 */
fun defaultDownloadDir(): File = File(System.getProperty("java.io.tmpdir"), "dooray-mcp-downloads")

fun downloadPostFileTool(): Tool {
    return Tool(
        name = "dooray_project_download_post_file",
        description =
            "두레이 프로젝트 업무의 첨부파일을 로컬 디스크에 다운로드합니다. " +
                "file_id는 dooray_project_get_post 응답의 files[].id 입니다. " +
                "저장된 파일의 절대 경로를 반환하므로, 이후 파일 읽기 도구로 내용을 확인할 수 있습니다.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        projectIdProperty()
                        postIdProperty("업무 ID (dooray_project_list_posts 또는 dooray_project_get_post로 조회 가능)")
                        putJsonObject("file_id") {
                            put("type", "string")
                            put("description", "첨부파일 ID (dooray_project_get_post 응답의 files[].id)")
                        }
                        putJsonObject("output_dir") {
                            put("type", "string")
                            put(
                                "description",
                                "파일을 저장할 로컬 디렉터리 (절대 경로 권장). 생략 시 시스템 임시 디렉터리 아래 " +
                                    "dooray-mcp-downloads 에 저장됩니다. 같은 이름의 파일이 있으면 file_id를 붙여 저장합니다."
                            )
                        }
                    },
                required = listOf("project_id", "post_id", "file_id")
            ),
        outputSchema = null,
        annotations = null
    )
}

fun downloadPostFileHandler(
    doorayClient: DoorayClient,
    projectResolver: ProjectResolver
): suspend (ClientConnection, CallToolRequest) -> CallToolResult {
    return { _, request ->
        toolHandler {
            val projectId = projectResolver.resolveProjectId(
                request.requireParam("project_id", "MISSING_PROJECT_ID", "project_id 파라미터가 필요합니다.")
            )
            val postId = request.requireParam("post_id", "MISSING_POST_ID", "post_id 파라미터가 필요합니다.")
            val fileId = request.requireParam(
                "file_id", "MISSING_FILE_ID",
                "file_id 파라미터가 필요합니다. dooray_project_get_post 응답의 files[].id를 사용하세요."
            )
            val outputDir = request.optionalParam("output_dir")?.let { File(it) } ?: defaultDownloadDir()

            val metaResponse = doorayClient.getPostFileMeta(projectId, postId, fileId)
            if (!metaResponse.header.isSuccessful) {
                return@toolHandler apiErrorResult(metaResponse.header)
            }
            val meta = metaResponse.result
            val bytes = doorayClient.downloadPostFile(projectId, postId, fileId)

            val target = withContext(Dispatchers.IO) {
                outputDir.mkdirs()
                resolveTargetFile(outputDir, meta.name, fileId).also { it.writeBytes(bytes) }
            }

            successResult(
                data = PostFileDownloadResponseData(
                    fileId = meta.id,
                    name = meta.name,
                    mimeType = meta.mimeType,
                    size = meta.size,
                    savedPath = target.absolutePath
                ),
                message = "📎 첨부파일을 다운로드했습니다: ${target.absolutePath}"
            )
        }
    }
}

/** 경로 구분자를 제거한 파일명으로 저장 위치를 정하고, 같은 이름의 파일이 있으면 file_id를 붙여 덮어쓰기를 피합니다. */
private fun resolveTargetFile(outputDir: File, originalName: String, fileId: String): File {
    val safeName = originalName.substringAfterLast('/').substringAfterLast('\\').ifBlank { fileId }
    val target = File(outputDir, safeName)
    if (!target.exists()) return target

    val stem = safeName.substringBeforeLast('.', safeName)
    val extension = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    return File(outputDir, "$stem-$fileId$extension")
}
