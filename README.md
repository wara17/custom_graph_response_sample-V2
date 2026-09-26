# graphql-demo

Spring Boot **3.3.7** + Spring for GraphQL **1.3.3** + Kotlin 1.9.25 + Java **17** (Maven)

## Run

```bash
mvn spring-boot:run
```

- GraphiQL UI: http://localhost:8080/graphiql
- Endpoint:    POST http://localhost:8080/graphql
- Schema:      http://localhost:8080/graphql/schema

## Test

```bash
mvn test
```

## ตัวอย่าง query

```graphql
query {
  books { id title pageCount author { fullName } }
  authorById(id: "author-1") { fullName books { title } }
}
```

```graphql
mutation {
  addBook(input: { title: "Dune", pageCount: 412, authorId: "author-2" }) {
    id title author { fullName }
  }
}
```

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ books { id title author { fullName } } }"}'
```

## สิ่งที่สาธิต

| ฟีเจอร์ | ที่อยู่ |
|---|---|
| `@QueryMapping` / `@MutationMapping` | `BookController` |
| `@Argument` + Bean Validation (`@Valid`) | `addBook`, `updateBook` |
| `@BatchMapping` (แก้ N+1 ด้วย DataLoader) | `Book.author`, `Author.books` |
| `@SchemaMapping` (computed field) | `Author.fullName` |
| Custom exception กลาง (`BusinessException`) | `exception/BusinessException.kt` |
| `@GraphQlExceptionHandler` จับ `BusinessException` ตัวเดียวครอบจักรวาล | `GraphQlExceptionHandlers` |
| Service layer ที่ throw exception เอง | `service/BookService.kt` |
| `ExecutionGraphQlServiceTester` test | `BookControllerTest` |
| Wrap response ด้วย `OncePerRequestFilter` | `GraphQlResponseWrapFilter` |

ข้อมูลเป็น in-memory (`ConcurrentHashMap`) จำลองแทน database

## Wrapped response (Filter)

Client เรียก `POST /graphql` แบบ GraphQL ปกติ แล้ว `GraphQlResponseWrapFilter` (OncePerRequestFilter)
จะดัก response หลัง `chain.doFilter()` → เช็ค `errors` ก่อน → แยกไป `wrapSuccess` / `wrapFailed`

> อธิบาย flow แบบละเอียด (ทำไมต้องเช็ค async, ทำไมต้องมี ContentCachingWrapper, เดินโค้ดทีละบรรทัด)
> ดูที่ [`docs/graphql-response-filter.md`](docs/graphql-response-filter.md)

```json
// success
{ "status": { "code": "0000", "message": "success" }, "data": { "bookById": { "title": "Moby Dick" } } }

// failed
{ "status": { "code": "4004", "message": "no data" } }
```

| code | เงื่อนไข |
|---|---|
| `0000` | ไม่มี error และมีข้อมูล |
| `4004` | `NotFoundException` หรือทุก field คืน `null`/list ว่าง |
| `4000` | `BadRequestException`, bean validation, หรือ query ผิด syntax |
| `4009` | `ConflictException` |
| `4030` | `ForbiddenException` |
| `5000` | error อื่นๆ ที่ไม่รู้จัก |

หมายเหตุ
- Spring GraphQL (WebMVC) ตอบแบบ **async** → `chain.doFilter()` รอบแรกกลับมาก่อนมี body
  filter จึงตั้ง `shouldNotFilterAsyncDispatch() = false` แล้วไป wrap ตอน ASYNC dispatch
- introspection query (`__schema`) ไม่ถูก wrap เพื่อให้ Postman / GraphiQL ดึง schema ได้
- `BookControllerTest` ใช้ `ExecutionGraphQlServiceTester` (ไม่ผ่าน HTTP) จึงได้ response มาตรฐาน

## Custom exception จาก service layer

โยน exception ได้จาก **ทุก layer** (service, repository, ฯลฯ) ไม่ต้องเขียน handler ใหม่ทุกครั้ง
แค่ extend `BusinessException` (หรือใช้ subclass ที่มีให้แล้ว):

```kotlin
// exception/BusinessException.kt
class NotFoundException(message: String) : BusinessException(message, StatusCode.NO_DATA)
class BadRequestException(message: String) : BusinessException(message, StatusCode.BAD_REQUEST)
class ConflictException(message: String) : BusinessException(message, StatusCode.CONFLICT)
class ForbiddenException(message: String) : BusinessException(message, StatusCode.FORBIDDEN)

// ใน service
if (bookRepository.existsByTitle(input.title)) {
    throw ConflictException("Book '${input.title}' already exists")
}
```

`GraphQlExceptionHandlers.handleBusinessException` จับ `BusinessException` ตัวเดียว ใส่
`code`/`statusMessage` ไว้ใน `extensions` ของ GraphQL error แล้ว `GraphQlResponseWrapFilter`
อ่านค่านั้นมาสร้าง `{ status: {code, message} }` ตรงๆ โดยไม่ต้องเดาจาก error type

เพิ่ม error ใหม่ → เพิ่ม `StatusCode` enum ใหม่ + subclass ของ `BusinessException` เท่านั้น
ไม่ต้องแตะ handler หรือ filter
