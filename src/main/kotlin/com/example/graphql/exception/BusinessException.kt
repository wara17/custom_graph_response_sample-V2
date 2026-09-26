package com.example.graphql.exception

import com.example.graphql.api.StatusCode

/**
 * Base exception สำหรับ business error ทั้งหมด — throw ได้จากทุก layer
 * (service, repository, controller) แล้ว `GraphQlExceptionHandlers` จะจับแค่ตัวเดียว
 * จบ ไม่ต้องเพิ่ม @GraphQlExceptionHandler ทุกครั้งที่มี exception ใหม่
 *
 * ใช้งาน: throw subclass ที่มีอยู่ หรือสร้างเองโดย extend BusinessException ตรงๆ
 */
open class BusinessException(
    message: String,
    val status: StatusCode,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** ไม่พบข้อมูล → wrap เป็น {code: "4004", message: ...} */
class NotFoundException(message: String) : BusinessException(message, StatusCode.NO_DATA)

/** input ไม่ถูกต้องตาม business rule (ไม่ใช่ @Valid ปกติ) → {code: "4000", message: ...} */
class BadRequestException(message: String) : BusinessException(message, StatusCode.BAD_REQUEST)

/** ข้อมูลขัดแย้งกับสถานะปัจจุบัน เช่น ซ้ำ, ผูกอยู่กับของอื่น → {code: "4009", message: ...} */
class ConflictException(message: String) : BusinessException(message, StatusCode.CONFLICT)

/** ไม่มีสิทธิ์ทำรายการนี้ → {code: "4030", message: ...} */
class ForbiddenException(message: String) : BusinessException(message, StatusCode.FORBIDDEN)
