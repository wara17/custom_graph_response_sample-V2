package com.example.graphql.service

import com.example.graphql.exception.NotFoundException
import com.example.graphql.model.Author
import com.example.graphql.model.Book
import com.example.graphql.model.BookInput
import com.example.graphql.repository.AuthorRepository
import com.example.graphql.repository.BookRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/**
 * Unit test ล้วนๆ ด้วย Mockito — ไม่มี Spring context, ไม่มี GraphQL engine
 * mock แค่ repository สองตัว แล้วเทส business logic ของ BookService ตรงๆ
 *
 * ข้อดี: รันเร็วมาก (ไม่ boot อะไรเลย), fail แล้วชี้จุดตรงเป๊ะว่า service พังตรงไหน
 * ข้อเสีย: ไม่รู้ว่า @QueryMapping ผูกกับ schema ถูกไหม, filter wrap ถูกไหม
 *          (ส่วนนั้นต้องพึ่ง BookControllerTest / GraphQlResponseWrapFilterTest แทน)
 */
@ExtendWith(MockitoExtension::class)
class BookServiceTest {

    @Mock
    private lateinit var bookRepository: BookRepository

    @Mock
    private lateinit var authorRepository: AuthorRepository

    @InjectMocks
    private lateinit var bookService: BookService

    private val author1 = Author("author-1", "Joanne", "Rowling")
    private val book1 = Book("book-1", "Harry Potter", 223, "author-1")

    @Test
    fun `create throws NotFoundException when author does not exist`() {
        whenever(authorRepository.findById("nope")).thenReturn(null)

        val ex = assertThrows(NotFoundException::class.java) {
            bookService.create(BookInput("New Book", 100, "nope"))
        }

        assertEquals("Author 'nope' not found", ex.message)
        // ต้องไม่เรียก save เลยถ้า author ไม่มีจริง
        verify(bookRepository, never()).save(any())
    }

    @Test
    fun `create saves book when author exists`() {
        whenever(authorRepository.findById("author-1")).thenReturn(author1)
        whenever(bookRepository.save(any())).thenAnswer { it.arguments[0] as Book }

        val result = bookService.create(BookInput("New Book", 100, "author-1"))

        assertEquals("New Book", result.title)
        assertEquals("author-1", result.authorId)
        verify(bookRepository).save(any())
    }

    @Test
    fun `update throws NotFoundException when book does not exist`() {
        whenever(bookRepository.findById("nope")).thenReturn(null)

        val ex = assertThrows(NotFoundException::class.java) {
            bookService.update("nope", BookInput("X", null, "author-1"))
        }

        assertEquals("Book 'nope' not found", ex.message)
        // เช็ค book ก่อน ไม่ควรไปเช็ค author เลยด้วยซ้ำ
        verify(authorRepository, never()).findById(any())
    }

    @Test
    fun `update throws NotFoundException when new authorId does not exist`() {
        whenever(bookRepository.findById("book-1")).thenReturn(book1)
        whenever(authorRepository.findById("nope")).thenReturn(null)

        assertThrows(NotFoundException::class.java) {
            bookService.update("book-1", BookInput("Updated", null, "nope"))
        }
    }

    @Test
    fun `delete delegates to repository`() {
        whenever(bookRepository.deleteById("book-1")).thenReturn(true)

        val result = bookService.delete("book-1")

        assertEquals(true, result)
        verify(bookRepository).deleteById(eq("book-1"))
    }

    @Test
    fun `authorsOf batches lookup and maps each book to its author`() {
        whenever(authorRepository.findAllById(setOf("author-1"))).thenReturn(listOf(author1))

        val result = bookService.authorsOf(listOf(book1))

        assertEquals(author1, result[book1])
    }
}
