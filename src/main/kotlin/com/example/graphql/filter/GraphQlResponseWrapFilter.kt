package com.example.graphql.filter

import com.example.graphql.api.ApiResponse
import com.example.graphql.api.StatusCode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.WebUtils

/**
 * ห่อ response ของ POST /graphql เป็น
 *   success → { status: {code: "0000", message: "success"}, data: {...} }
 *   failed  → { status: {code: "4004", message: "no data"} }
 *
 * ข้อควรรู้: Spring GraphQL (WebMVC) ตอบแบบ async
 *   1) REQUEST dispatch : chain.doFilter() กลับมาทันที → request.isAsyncStarted = true, body ยังว่าง
 *   2) ASYNC dispatch   : handler เขียน body จริง → ตรงนี้ถึงค่อย wrap
 * จึงต้อง override shouldNotFilterAsyncDispatch() = false ให้ filter ทำงานรอบที่ 2 ด้วย
 */
@Component
class GraphQlResponseWrapFilter(
    private val objectMapper: ObjectMapper,
    @Value("\${spring.graphql.path:/graphql}") private val graphQlPath: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !(request.method == "POST" && request.requestURI == request.contextPath + graphQlPath)

    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        // ใช้ wrapper ตัวเดิมถ้ามีแล้ว (กรณี async dispatch)
        val request = WebUtils.getNativeRequest(req, ContentCachingRequestWrapper::class.java)
            ?: ContentCachingRequestWrapper(req)
        val response = WebUtils.getNativeResponse(res, ContentCachingResponseWrapper::class.java)
            ?: ContentCachingResponseWrapper(res)

        chain.doFilter(request, response)

        // เก็บ request body ไว้ใน attribute (ใช้ตอน async dispatch ซึ่งอ่าน body ซ้ำไม่ได้)
        if (request.getAttribute(REQUEST_BODY_ATTR) == null && request.contentAsByteArray.isNotEmpty()) {
            request.setAttribute(REQUEST_BODY_ATTR, String(request.contentAsByteArray, Charsets.UTF_8))
        }

        if (request.isAsyncStarted) {
            return // response ยังไม่มา → รอ ASYNC dispatch
        }

        try {
            wrapResponse(request, response)
        } finally {
            response.copyBodyToResponse() // เขียน body จริงออกไป + set Content-Length
        }
    }

    // ------------------------------------------------------------------

    private fun wrapResponse(request: HttpServletRequest, response: ContentCachingResponseWrapper) {
        val body = response.contentAsByteArray
        if (body.isEmpty() || response.contentType?.contains("json") != true) return

        // ปล่อย introspection ผ่าน (Postman/GraphiQL ใช้ดึง schema)
        val requestBody = request.getAttribute(REQUEST_BODY_ATTR) as? String
        if (requestBody != null && isIntrospection(requestBody)) return

        val graphQlResponse = try {
            objectMapper.readTree(body)
        } catch (ex: Exception) {
            log.warn("Cannot parse GraphQL response, skip wrapping", ex)
            return
        }

        // ---- check error ก่อน แล้วค่อยแยก success / failed ----
        val errors = graphQlResponse.path("errors")
        val data = graphQlResponse.path("data")

        val wrapped = when {
            errors.isArray && !errors.isEmpty -> wrapFailed(errors)
            isNoData(data) -> wrapFailed(StatusCode.NO_DATA)
            else -> wrapSuccess(data)
        }

        response.resetBuffer()
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.outputStream.write(objectMapper.writeValueAsBytes(wrapped))
    }

    private fun wrapSuccess(data: JsonNode): ApiResponse =
        ApiResponse(StatusCode.SUCCESS.toStatus(), data)

    private fun wrapFailed(code: StatusCode, message: String = code.message): ApiResponse =
        ApiResponse(code.toStatus(message))

    private fun wrapFailed(errors: JsonNode): ApiResponse {
        val first = errors[0]
        val extensions = first.path("extensions")

        // 1) BusinessException (จาก GraphQlExceptionHandlers) ใส่ code/statusMessage มาตรงๆ ใน
        //    extensions แล้ว — ใช้ค่านั้นก่อนเลย ไม่ต้องเดา
        val explicitCode = extensions.path("code")
        if (explicitCode.isTextual) {
            val code = StatusCode.entries.find { it.code == explicitCode.asText() } ?: StatusCode.INTERNAL_ERROR
            val message = extensions.path("statusMessage").asText(first.path("message").asText(code.message))
            return wrapFailed(code, message)
        }

        // 2) fallback: error ที่ไม่ผ่าน BusinessException (validation, syntax error, exception
        //    ที่ไม่ได้ throw เป็น BusinessException ฯลฯ) เดาจาก classification ของ Spring GraphQL
        val classification = extensions.path("classification").asText("")
        val code = when (classification) {
            "NOT_FOUND" -> StatusCode.NO_DATA
            "FORBIDDEN" -> StatusCode.FORBIDDEN
            "BAD_REQUEST", "ValidationError", "InvalidSyntax" -> StatusCode.BAD_REQUEST
            else -> StatusCode.INTERNAL_ERROR
        }
        val message = first.path("message").asText(code.message)
        return wrapFailed(code, message)
    }

    /** data เป็น null หรือทุก field เป็น null / list ว่าง */
    private fun isNoData(data: JsonNode): Boolean {
        if (data.isMissingNode || data.isNull || data.isEmpty) return true
        return data.all { it.isNull || (it.isArray && it.isEmpty) }
    }

    private fun isIntrospection(body: String): Boolean =
        body.contains("__schema") || body.contains("IntrospectionQuery")

    companion object {
        private val REQUEST_BODY_ATTR = GraphQlResponseWrapFilter::class.java.name + ".REQUEST_BODY"
    }
}
