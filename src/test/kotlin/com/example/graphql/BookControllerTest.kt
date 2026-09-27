package com.example.graphql

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath

/**
 * Schema/resolver-level test — เปลี่ยนมาใช้ MockMvc POST /graphql ธรรมดา แทน
 * ExecutionGraphQlServiceTester (มาจาก spring-graphql-test) ด้วยเหตุผล 2 ข้อ:
 *
 * 1. spring-graphql-test ต้อง version ตรงกับ spring-graphql เป๊ะเสมอ (release เป็นคู่)
 *    ยิ่งอัปเกรด spring-graphql บ่อยเท่าไหร่ ยิ่งลดจุดที่ผูกกับ version นี้ได้ยิ่งดี
 * 2. ExecutionGraphQlServiceTester เรียก GraphQL engine ตรงๆ ข้าม servlet filter chain
 *    ไปเลย เทสต์แบบนั้นจะไม่มีทางรู้ว่า GraphQlResponseWrapFilter ทำงานถูกไหม
 *    ส่วนเทสต์การ wrap เอง อยู่ที่ GraphQlResponseWrapFilterTest.kt (ใช้ MockMvc เหมือนกัน)
 *
 * เพราะทุก response ผ่าน filter (สังเกต path เป็น $.data.xxx ไม่ใช่ $.xxx) และ pom.xml
 * ไม่มี spring-graphql-test เป็น dependency แล้ว — ไฟล์นี้พึ่งพาแค่ spring-boot-starter-test
 * ซึ่งไม่ขยับตามเวอร์ชันของ spring-graphql เลย
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookControllerTest(@Autowired private val mockMvc: MockMvc) {

    /** ยิง /graphql แบบ GraphQL ปกติ — ถ้า handler ตอบ async ก็ asyncDispatch ต่อให้ */
    private fun call(json: String): ResultActions {
        val first = mockMvc.perform(
            post("/graphql").contentType(MediaType.APPLICATION_JSON).content(json)
        )
        val result = first.andReturn()
        return if (result.request.isAsyncStarted) mockMvc.perform(asyncDispatch(result)) else first
    }

    @Test
    fun `query books with nested author`() {
        call("""{"query":"{ books { id title author { fullName } } }"}""")
            .andExpect(jsonPath("$.data.books.length()").value(org.hamcrest.Matchers.greaterThan(2)))
            .andExpect(jsonPath("$.data.books[0].author.fullName").value("Joanne Rowling"))
    }

    @Test
    fun `query bookById with variable`() {
        call(
            """{"query":"query(${'$'}id: ID!) { bookById(id: ${'$'}id) { title pageCount } }","variables":{"id":"book-2"}}"""
        )
            .andExpect(jsonPath("$.data.bookById.title").value("Moby Dick"))
            .andExpect(jsonPath("$.data.bookById.pageCount").value(635))
    }

    @Test
    fun `author with books`() {
        call("""{"query":"{ authorById(id: \"author-3\") { fullName books { title } } }"}""")
            .andExpect(jsonPath("$.data.authorById.books[0].title").value("Interview with the Vampire"))
    }

    @Test
    fun `add, update and delete book`() {
        val addResult = call(
            """{"query":"mutation { addBook(input: {title: \"New Book\", pageCount: 100, authorId: \"author-1\"}) { id title } }"}"""
        )
            .andExpect(jsonPath("$.data.addBook.title").value("New Book"))
            .andReturn()

        val body = addResult.response.contentAsString
        val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)!!.groupValues[1]

        call(
            """{"query":"mutation(${'$'}id: ID!) { updateBook(id: ${'$'}id, input: {title: \"Updated\", authorId: \"author-2\"}) { title author { lastName } } }","variables":{"id":"$id"}}"""
        )
            .andExpect(jsonPath("$.data.updateBook.title").value("Updated"))
            .andExpect(jsonPath("$.data.updateBook.author.lastName").value("Melville"))

        call("""{"query":"mutation(${'$'}id: ID!) { deleteBook(id: ${'$'}id) }","variables":{"id":"$id"}}""")
            .andExpect(jsonPath("$.data.deleteBook").value(true))
    }

    @Test
    fun `unknown author throws NotFoundException carrying code 4004`() {
        // NotFoundException (BusinessException) จาก BookService ถูกจับที่ GraphQlExceptionHandlers
        // ตัวเดียว แล้วใส่ status code ไว้ใน extensions.code ให้ GraphQlResponseWrapFilter อ่านต่อ
        call("""{"query":"mutation { addBook(input: {title: \"X\", authorId: \"nope\"}) { id } }"}""")
            .andExpect(jsonPath("$.status.code").value("4004"))
            .andExpect(jsonPath("$.status.message").value("Author 'nope' not found"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `invalid input is wrapped as 4000`() {
        call("""{"query":"mutation { addBook(input: {title: \"\", pageCount: 0, authorId: \"author-1\"}) { id } }"}""")
            .andExpect(jsonPath("$.status.code").value("4000"))
    }
}
