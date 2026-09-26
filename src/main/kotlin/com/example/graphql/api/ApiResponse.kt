package com.example.graphql.api

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse(
    val status: ApiStatus,
    val data: Any? = null,
)

data class ApiStatus(
    val code: String,
    val message: String,
)

/**
 * รหัสสถานะของระบบ — เพิ่มตัวใหม่ตรงนี้แล้วโยน exception ที่ map มา
 * (ดู BusinessException.kt) ไม่ต้องแตะ filter หรือ handler อีก
 */
enum class StatusCode(val code: String, val message: String) {
    SUCCESS("0000", "success"),
    BAD_REQUEST("4000", "bad request"),
    NO_DATA("4004", "no data"),
    CONFLICT("4009", "conflict"),
    FORBIDDEN("4030", "forbidden"),
    INTERNAL_ERROR("5000", "internal error");

    fun toStatus(message: String = this.message) = ApiStatus(code, message)
}
