package com.example.graphql.repository

import com.example.graphql.model.Author
import com.example.graphql.model.Book
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory repository จำลองแทน database */
@Repository
class AuthorRepository {
    private val store = ConcurrentHashMap<String, Author>(
        listOf(
            Author("author-1", "Joanne", "Rowling"),
            Author("author-2", "Herman", "Melville"),
            Author("author-3", "Anne", "Rice"),
        ).associateBy { it.id }
    )

    fun findAll(): List<Author> = store.values.sortedBy { it.id }
    fun findById(id: String): Author? = store[id]
    fun findAllById(ids: Collection<String>): List<Author> = ids.mapNotNull { store[it] }
}

@Repository
class BookRepository {
    private val seq = AtomicLong(3)
    private val store = ConcurrentHashMap<String, Book>(
        listOf(
            Book("book-1", "Harry Potter and the Philosopher's Stone", 223, "author-1"),
            Book("book-2", "Moby Dick", 635, "author-2"),
            Book("book-3", "Interview with the Vampire", 371, "author-3"),
        ).associateBy { it.id }
    )

    fun findAll(): List<Book> = store.values.sortedBy { it.id }
    fun findById(id: String): Book? = store[id]
    fun findByAuthorIds(authorIds: Collection<String>): List<Book> =
        store.values.filter { it.authorId in authorIds }.sortedBy { it.id }

    fun save(book: Book): Book {
        val toSave = if (book.id.isBlank()) book.copy(id = "book-${seq.incrementAndGet()}") else book
        store[toSave.id] = toSave
        return toSave
    }

    fun deleteById(id: String): Boolean = store.remove(id) != null
}
