package com.example.graphql.controller

import com.example.graphql.exception.BusinessException
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import jakarta.validation.ConstraintViolationException
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler
import org.springframework.graphql.execution.ErrorType
import org.springframework.web.bind.annotation.ControllerAdvice

/**
 * แปลง exception เป็น GraphQL error ตัวเดียวครอบจักรวาล:
 *   - BusinessException (และ subclass ทั้งหมด — NotFoundException, ConflictException, ...)
 *     จาก service ไหนก็ตาม จะถูกจับที่นี่ที่เดียว โดย code/message มาจากตัว exception เอง
 *   - ไม่ต้องเพิ่ม handler ใหม่ทุกครั้งที่มี custom exception เพิ่ม แค่ extend BusinessException
 *
 * code/message ถูกใส่ไว้ใน `extensions` ของ GraphQL error ด้วย
 * เพื่อให้ GraphQlResponseWrapFilter อ่านไปสร้าง { status: {code, message} } ได้ตรงๆ
 * โดยไม่ต้องเดาจาก errorType/classification
 */
@ControllerAdvice
class GraphQlExceptionHandlers {

    @GraphQlExceptionHandler
    fun handleBusinessException(ex: BusinessException, env: DataFetchingEnvironment): GraphQLError =
        GraphqlErrorBuilder.newError(env)
            .errorType(ErrorType.BAD_REQUEST) // ใช้เป็นค่ากลาง ตัวที่ใช้จริงคือ extensions.code ด้านล่าง
            .message(ex.message)
            .extensions(
                mapOf(
                    "code" to ex.status.code,
                    "statusMessage" to (ex.message ?: ex.status.message),
                )
            )
            .build()

    @GraphQlExceptionHandler
    fun handleValidation(ex: ConstraintViolationException, env: DataFetchingEnvironment): GraphQLError =
        GraphqlErrorBuilder.newError(env)
            .errorType(ErrorType.BAD_REQUEST)
            .message(ex.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" })
            .build()
}
