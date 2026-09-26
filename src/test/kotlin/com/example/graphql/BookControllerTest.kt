package com.example.graphql

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.execution.ErrorType
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureGraphQlTester // เรียกระดับ service (ไม่ผ่าน HTTP filter)
class BookControllerTest(@Autowired private val tester: ExecutionGraphQlServiceTester) {

    @Test
    fun `query books with nested author`() {
        tester.document(
            """
            { books { id title author { fullName } } }
            """
        ).execute()
            .path("books").entityList(Any::class.java).hasSizeGreaterThan(2)
            .path("books[0].author.fullName").entity(String::class.java).isEqualTo("Joanne Rowling")
    }

    @Test
    fun `query bookById with variable`() {
        tester.document("query(\$id: ID!) { bookById(id: \$id) { title pageCount } }")
            .variable("id", "book-2")
            .execute()
            .path("bookById.title").entity(String::class.java).isEqualTo("Moby Dick")
            .path("bookById.pageCount").entity(Int::class.java).isEqualTo(635)
    }

    @Test
    fun `author with books`() {
        tester.document("{ authorById(id: \"author-3\") { fullName books { title } } }")
            .execute()
            .path("authorById.books[0].title").entity(String::class.java)
            .isEqualTo("Interview with the Vampire")
    }

    @Test
    fun `add, update and delete book`() {
        val id = tester.document(
            """
            mutation { addBook(input: {title: "New Book", pageCount: 100, authorId: "author-1"}) { id title } }
            """
        ).execute()
            .path("addBook.title").entity(String::class.java).isEqualTo("New Book")
            .path("addBook.id").entity(String::class.java).get()

        tester.document(
            "mutation(\$id: ID!) { updateBook(id: \$id, input: {title: \"Updated\", authorId: \"author-2\"}) { title author { lastName } } }"
        ).variable("id", id).execute()
            .path("updateBook.title").entity(String::class.java).isEqualTo("Updated")
            .path("updateBook.author.lastName").entity(String::class.java).isEqualTo("Melville")

        tester.document("mutation(\$id: ID!) { deleteBook(id: \$id) }")
            .variable("id", id).execute()
            .path("deleteBook").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `unknown author throws NotFoundException carrying code 4004 in extensions`() {
        // NotFoundException (BusinessException) จาก BookService ถูกจับที่ GraphQlExceptionHandlers
        // ตัวเดียว แล้วใส่ status code ไว้ใน extensions.code ให้ GraphQlResponseWrapFilter อ่านต่อ
        tester.document(
            """
            mutation { addBook(input: {title: "X", authorId: "nope"}) { id } }
            """
        ).execute()
            .errors()
            .satisfy { errors ->
                assertEquals(1, errors.size)
                assertEquals("4004", errors[0].extensions["code"])
                assertTrue(errors[0].message.contains("Author 'nope' not found"))
            }
    }

    @Test
    fun `invalid input returns BAD_REQUEST error`() {
        tester.document(
            """
            mutation { addBook(input: {title: "", pageCount: 0, authorId: "author-1"}) { id } }
            """
        ).execute()
            .errors()
            .satisfy { errors -> assertEquals(ErrorType.BAD_REQUEST, errors[0].errorType) }
    }
}
