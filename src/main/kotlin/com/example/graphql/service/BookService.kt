package com.example.graphql.service

import com.example.graphql.exception.NotFoundException
import com.example.graphql.model.Author
import com.example.graphql.model.Book
import com.example.graphql.model.BookInput
import com.example.graphql.repository.AuthorRepository
import com.example.graphql.repository.BookRepository
import org.springframework.stereotype.Service

/**
 * ตัวอย่าง service layer ที่ throw custom exception เอง —
 * controller ไม่ต้องรู้เรื่อง error handling เลย แค่เรียก service เฉยๆ
 */
@Service
class BookService(
    private val bookRepository: BookRepository,
    private val authorRepository: AuthorRepository,
) {

    fun findAll(): List<Book> = bookRepository.findAll()

    fun findById(id: String): Book? = bookRepository.findById(id)

    fun create(input: BookInput): Book {
        requireAuthorExists(input.authorId)
        return bookRepository.save(Book("", input.title, input.pageCount, input.authorId))
    }

    fun update(id: String, input: BookInput): Book {
        bookRepository.findById(id)
            ?: throw NotFoundException("Book '$id' not found")
        requireAuthorExists(input.authorId)
        return bookRepository.save(Book(id, input.title, input.pageCount, input.authorId))
    }

    fun delete(id: String): Boolean = bookRepository.deleteById(id)

    fun authorsOf(books: List<Book>): Map<Book, Author> {
        val authors = authorRepository.findAllById(books.map { it.authorId }.toSet()).associateBy { it.id }
        return books.associateWith { authors.getValue(it.authorId) }
    }

    private fun requireAuthorExists(authorId: String) {
        authorRepository.findById(authorId)
            ?: throw NotFoundException("Author '$authorId' not found")
    }
}

@Service
class AuthorService(
    private val authorRepository: AuthorRepository,
    private val bookRepository: BookRepository,
) {
    fun findAll(): List<Author> = authorRepository.findAll()
    fun findById(id: String): Author? = authorRepository.findById(id)

    fun booksOf(authors: List<Author>): Map<Author, List<Book>> {
        val byAuthor = bookRepository.findByAuthorIds(authors.map { it.id }).groupBy { it.authorId }
        return authors.associateWith { byAuthor[it.id].orEmpty() }
    }
}
