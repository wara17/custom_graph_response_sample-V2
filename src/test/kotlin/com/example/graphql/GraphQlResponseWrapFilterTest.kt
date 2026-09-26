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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class GraphQlResponseWrapFilterTest(@Autowired private val mockMvc: MockMvc) {

    /** ยิง /graphql แบบ GraphQL ปกติ — ถ้า handler ตอบ async ก็ asyncDispatch ต่อให้ */
    private fun call(json: String): ResultActions {
        val first = mockMvc.perform(
            post("/graphql").contentType(MediaType.APPLICATION_JSON).content(json)
        )
        val result = first.andReturn()
        return if (result.request.isAsyncStarted) mockMvc.perform(asyncDispatch(result)) else first
    }

    @Test
    fun `success is wrapped with status 0000`() {
        call("""{"query":"query(${'$'}id: ID!) { bookById(id: ${'$'}id) { title } }","variables":{"id":"book-2"}}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status.code").value("0000"))
            .andExpect(jsonPath("$.status.message").value("success"))
            .andExpect(jsonPath("$.data.bookById.title").value("Moby Dick"))
            .andExpect(jsonPath("$.errors").doesNotExist())
    }

    @Test
    fun `null result is wrapped as 4004 no data`() {
        call("""{"query":"{ bookById(id: \"nope\") { title } }"}""")
            .andExpect(jsonPath("$.status.code").value("4004"))
            .andExpect(jsonPath("$.status.message").value("no data"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `NotFoundException (BusinessException) from service is wrapped as 4004 with its own message`() {
        call("""{"query":"mutation { addBook(input: {title: \"X\", authorId: \"nope\"}) { id } }"}""")
            .andExpect(jsonPath("$.status.code").value("4004"))
            .andExpect(jsonPath("$.status.message").value("Author 'nope' not found"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `bean validation error is wrapped as 4000`() {
        call("""{"query":"mutation { addBook(input: {title: \"\", authorId: \"author-1\"}) { id } }"}""")
            .andExpect(jsonPath("$.status.code").value("4000"))
    }

    @Test
    fun `invalid graphql syntax is wrapped as 4000`() {
        call("""{"query":"{ unknownField }"}""")
            .andExpect(jsonPath("$.status.code").value("4000"))
    }

    @Test
    fun `introspection is not wrapped`() {
        call("""{"query":"{ __schema { queryType { name } } }"}""")
            .andExpect(jsonPath("$.data.__schema.queryType.name").value("Query"))
            .andExpect(jsonPath("$.status").doesNotExist())
    }
}
