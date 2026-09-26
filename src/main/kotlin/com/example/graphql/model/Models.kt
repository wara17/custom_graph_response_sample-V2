package com.example.graphql.model

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class Book(
    val id: String,
    val title: String,
    val pageCount: Int?,
    val authorId: String,
)

data class Author(
    val id: String,
    val firstName: String,
    val lastName: String,
)

data class BookInput(
    @field:NotBlank
    val title: String,
    @field:Min(1)
    val pageCount: Int?,
    val authorId: String,
)
