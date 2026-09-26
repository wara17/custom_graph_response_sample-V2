package com.example.graphql.controller

import com.example.graphql.model.Author
import com.example.graphql.model.Book
import com.example.graphql.model.BookInput
import com.example.graphql.service.AuthorService
import com.example.graphql.service.BookService
import jakarta.validation.Valid
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * Controller บางๆ — ไม่มี try/catch หรือ error handling เลย
 * exception ที่ service throw จะไหลขึ้นไปให้ GraphQlExceptionHandlers จับเอง
 */
@Controller
class BookController(
    private val bookService: BookService,
    private val authorService: AuthorService,
) {

    // ---------- Query ----------

    @QueryMapping
    fun books(): List<Book> = bookService.findAll()

    @QueryMapping
    fun bookById(@Argument id: String): Book? = bookService.findById(id)

    @QueryMapping
    fun authors(): List<Author> = authorService.findAll()

    @QueryMapping
    fun authorById(@Argument id: String): Author? = authorService.findById(id)

    // ---------- Mutation ----------

    @MutationMapping
    fun addBook(@Argument @Valid input: BookInput): Book = bookService.create(input)

    @MutationMapping
    fun updateBook(@Argument id: String, @Argument @Valid input: BookInput): Book =
        bookService.update(id, input)

    @MutationMapping
    fun deleteBook(@Argument id: String): Boolean = bookService.delete(id)

    // ---------- Field resolvers ----------

    /** Book.author — ใช้ @BatchMapping เพื่อแก้ปัญหา N+1 (DataLoader) */
    @BatchMapping
    fun author(books: List<Book>): Map<Book, Author> = bookService.authorsOf(books)

    /** Author.books — batch เหมือนกัน */
    @BatchMapping(typeName = "Author", field = "books")
    fun booksOfAuthors(authors: List<Author>): Map<Author, List<Book>> = authorService.booksOf(authors)

    /** Author.fullName — computed field ด้วย @SchemaMapping */
    @SchemaMapping(typeName = "Author", field = "fullName")
    fun fullName(author: Author): String = "${author.firstName} ${author.lastName}"
}
