package com.example.graphql.controller

import com.example.graphql.exception.ConflictException
import com.example.graphql.exception.NotFoundException
import graphql.schema.DataFetchingEnvironment
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit test ด้วย Mockito สำหรับ GraphQlExceptionHandlers ล้วนๆ
 * mock แค่ DataFetchingEnvironment (Spring GraphQL ต้องการ argument นี้เพื่อ build GraphQLError
 * ที่ผูกกับ path/location ของ query — แต่ค่าที่ handler ใช้จริงมีแค่ตัว exception เอง)
 */
@ExtendWith(MockitoExtension::class)
class GraphQlExceptionHandlersTest {

    @Mock
    private lateinit var env: DataFetchingEnvironment

    private val handlers = GraphQlExceptionHandlers()

    @Test
    fun `NotFoundException is mapped to code 4004 in extensions`() {
        val error = handlers.handleBusinessException(NotFoundException("Author 'nope' not found"), env)

        assertEquals("Author 'nope' not found", error.message)
        assertEquals("4004", error.extensions["code"])
        assertEquals("Author 'nope' not found", error.extensions["statusMessage"])
    }

    @Test
    fun `ConflictException is mapped to code 4009 in extensions`() {
        val error = handlers.handleBusinessException(ConflictException("Book 'Dune' already exists"), env)

        assertEquals("4009", error.extensions["code"])
    }

    @Test
    fun `ConstraintViolationException joins every violation message`() {
        val violation = mock<ConstraintViolation<*>>()
        val path = mock<Path>()
        whenever(path.toString()).thenReturn("title")
        whenever(violation.propertyPath).thenReturn(path)
        whenever(violation.message).thenReturn("must not be blank")

        val ex = ConstraintViolationException(setOf(violation))

        val error = handlers.handleValidation(ex, env)

        assertEquals("title: must not be blank", error.message)
    }
}
