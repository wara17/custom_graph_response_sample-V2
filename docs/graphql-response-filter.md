# GraphQlResponseWrapFilter — วิธีทำงานและเหตุผล

`GraphQlResponseWrapFilter` (`src/main/kotlin/com/example/graphql/filter/GraphQlResponseWrapFilter.kt`)
เป็น `OncePerRequestFilter` ที่ห่อ response ของ `POST /graphql` จากรูปแบบ GraphQL มาตรฐาน
`{ data, errors }` ให้กลายเป็นรูปแบบของระบบ:

```json
// success
{ "status": { "code": "0000", "message": "success" }, "data": { "bookById": { "title": "Moby Dick" } } }

// failed
{ "status": { "code": "4004", "message": "no data" } }
```

## แนวคิดหลัก

ปล่อยให้ Spring GraphQL ประมวลผลตามปกติจนได้ response `{data, errors}` มาตรฐานก่อน แล้วค่อย
"แกะ" response นั้นออกมาห่อใหม่ก่อนส่งออกจริง — ตัว controller/service ไม่ต้องรู้เรื่องนี้เลย

## ทำไมทำใน filter ไม่ตรงไปตรงมาแบบ filter ทั่วไป

Spring GraphQL (WebMVC) ตอบกลับแบบ **async request** — พอ handler เริ่มทำงาน
`chain.doFilter()` จะ `return` ออกมาทันทีโดยที่ body ยังไม่ถูกเขียน (execution ยังไม่เสร็จ)
แล้ว container จะ dispatch request เดิมกลับเข้ามาอีกรอบตอนงานเสร็จจริง

ผลคือ filter ตัวนี้ถูกเรียก **2 รอบ** ต่อ 1 request:

| รอบ | `request.isAsyncStarted` | body |
|---|---|---|
| 1. REQUEST dispatch | `true` | ว่างเปล่า — ห้าม wrap |
| 2. ASYNC dispatch | `false` (ของ dispatch นี้) | เขียนจริงแล้ว — wrap ได้ |

```kotlin
override fun shouldNotFilterAsyncDispatch(): Boolean = false
```

บรรทัดนี้สำคัญมาก ค่า default ของ Spring คือ `true` (ข้าม filter ตอน ASYNC dispatch) ถ้าไม่ override
filter จะทำงานแค่รอบแรกที่ body ยังว่าง แล้ว wrap อะไรไม่ได้เลย

## เดินตามโค้ดทีละส่วน

### 1) กรอง path

```kotlin
override fun shouldNotFilter(request: HttpServletRequest): Boolean =
    !(request.method == "POST" && request.requestURI == request.contextPath + graphQlPath)
```

ให้ filter ทำงานเฉพาะ `POST /graphql` เท่านั้น endpoint อื่นผ่านไปตามปกติ

### 2) ห่อ request/response ด้วย ContentCaching wrapper

```kotlin
val request = WebUtils.getNativeRequest(req, ContentCachingRequestWrapper::class.java)
    ?: ContentCachingRequestWrapper(req)
val response = WebUtils.getNativeResponse(res, ContentCachingResponseWrapper::class.java)
    ?: ContentCachingResponseWrapper(res)

chain.doFilter(request, response)
```

- `ContentCachingResponseWrapper` เก็บ byte ที่ handler เขียนไว้ในบัฟเฟอร์ ไม่ปล่อยออกไปจริงจนกว่าจะ
  เรียก `copyBodyToResponse()` — ทำให้เรา "แอบอ่าน" body ก่อนแล้วเปลี่ยนมันได้
- `ContentCachingRequestWrapper` เก็บ body ของ request (query ที่ client ส่งมา) ไว้ให้อ่านซ้ำได้
  เพราะ `InputStream` ปกติอ่านได้ครั้งเดียว
- เช็ค `WebUtils.getNative...` ก่อนสร้างใหม่เสมอ เพราะรอบ ASYNC dispatch ต้องใช้ wrapper
  **ตัวเดิม** จากรอบ REQUEST dispatch (ถ้าสร้างใหม่จะไม่มีข้อมูลอะไรอยู่ในนั้นเลย)

### 3) รอจนกว่า body จะมาจริง

```kotlin
if (request.isAsyncStarted) {
    return // response ยังไม่มา → รอ ASYNC dispatch
}

try {
    wrapResponse(request, response)
} finally {
    response.copyBodyToResponse() // เขียน body จริงออกไป + set Content-Length
}
```

รอบแรก (`isAsyncStarted == true`) แค่ปล่อยผ่าน ไม่ทำอะไร รอบสอง (body มาแล้วจริง) ค่อยเรียก
`wrapResponse` แล้วปิดท้ายด้วย `copyBodyToResponse()` **เสมอ** (แม้ wrap error) เพื่อดันบัฟเฟอร์
ที่ cache ไว้ออกไปเป็น response จริง — ถ้าลืมบรรทัดนี้ client จะไม่ได้ response อะไรเลย

### 4) เช็ค error ก่อน แยก success/failed

```kotlin
val errors = graphQlResponse.path("errors")
val data = graphQlResponse.path("data")

val wrapped = when {
    errors.isArray && !errors.isEmpty -> wrapFailed(errors)
    isNoData(data) -> wrapFailed(StatusCode.NO_DATA)
    else -> wrapSuccess(data)
}
```

1. มี `errors` ไหม → มีไป `wrapFailed(errors)`
2. ไม่มี error แต่ `data` เป็น null/ว่างทั้งหมด → `wrapFailed(NO_DATA)`
3. ไม่เข้าเงื่อนไขไหนเลย → `wrapSuccess(data)`

ก่อนถึงจุดนี้มีการกันไว้ 2 อย่าง:
- ถ้า response ไม่ใช่ JSON (`body.isEmpty()` หรือ content-type ไม่ใช่ json) → return เฉยๆ ไม่ยุ่ง
- ถ้า query เป็น **introspection** (`__schema`) → ปล่อยผ่านไม่ wrap เพื่อให้ GraphiQL/Postman
  ดึง schema ได้ตามปกติ (สอง tool นี้ parse response แบบมาตรฐานเท่านั้น)

### 5) แปลง error → status code

```kotlin
val explicitCode = extensions.path("code")
if (explicitCode.isTextual) {
    val code = StatusCode.entries.find { it.code == explicitCode.asText() } ?: StatusCode.INTERNAL_ERROR
    ...
}
```

อ่าน `extensions.code` ก่อน — ค่านี้มาจาก `GraphQlExceptionHandlers.handleBusinessException`
ที่ใส่ `code`/`statusMessage` ไว้ตอน throw `BusinessException` (เช่น `NotFoundException`,
`ConflictException`) วิธีนี้แม่นยำ 100% เพราะ code มาจาก exception ตรงๆ ไม่ต้องเดา

```kotlin
val classification = extensions.path("classification").asText("")
val code = when (classification) {
    "NOT_FOUND" -> StatusCode.NO_DATA
    "FORBIDDEN" -> StatusCode.FORBIDDEN
    "BAD_REQUEST", "ValidationError", "InvalidSyntax" -> StatusCode.BAD_REQUEST
    else -> StatusCode.INTERNAL_ERROR
}
```

ถ้าไม่มี `extensions.code` (เช่น error จาก bean validation หรือ query ผิด syntax ที่ไม่ได้ผ่าน
`BusinessException`) จะ fallback ไปดู `classification` ที่ Spring GraphQL ใส่มาให้อัตโนมัติแทน

## สรุป flow เป็นภาพ

```
Client → POST /graphql
   │
   ▼
Filter (รอบ 1: REQUEST) ── chain.doFilter() ──▶ Spring GraphQL เริ่มประมวลผล async
   │                                                      │
   │ isAsyncStarted=true → return ทันที                   │ (ทำงานเบื้องหลัง)
   ▼                                                      ▼
Filter (รอบ 2: ASYNC) ◀── container dispatch กลับมา ── เขียน {data,errors} ลง buffer
   │
   ▼
wrapResponse() → เช็ค errors ก่อน → wrapFailed / wrapSuccess
   │
   ▼
copyBodyToResponse() → ส่ง {status, data} กลับไปจริง
```

## ตารางอ้างอิง status code

| code | เงื่อนไข |
|---|---|
| `0000` | ไม่มี error และมีข้อมูล |
| `4004` | `NotFoundException` หรือทุก field คืน `null`/list ว่าง |
| `4000` | `BadRequestException`, bean validation, หรือ query ผิด syntax |
| `4009` | `ConflictException` |
| `4030` | `ForbiddenException` |
| `5000` | error อื่นๆ ที่ไม่รู้จัก |

เพิ่ม error ใหม่ → เพิ่ม `StatusCode` enum ใหม่ใน `api/ApiResponse.kt` + สร้าง subclass ของ
`BusinessException` ใน `exception/BusinessException.kt` เท่านั้น ไม่ต้องแตะ filter หรือ handler
(ดูรายละเอียดที่ `README.md` หัวข้อ "Custom exception จาก service layer")
