---
name: spring-graphql-wrap-and-upgrade
description: "Use when a Spring Boot + Kotlin + Spring GraphQL project needs its /graphql response wrapped in a custom {status,data} envelope via OncePerRequestFilter, or needs spring-graphql/graphql-java version upgraded without breaking current behavior."
---

# Spring GraphQL: Response Wrapping + Version Upgrade

This skill packages two related capabilities learned from building and evolving a
Spring Boot 3.3.7 + Kotlin + Spring for GraphQL 1.3.3 sample project. Apply either
part independently, or both, to another codebase that needs them.

**Hard rule for both parts: never change existing external behavior unless the user
explicitly asked for that specific change.** Wrapping the response format or bumping
a dependency version must be additive/isolated — existing endpoints, existing response
shapes for callers who don't opt in, and existing passing tests must keep working
exactly as before, unless the user asked you to change that particular thing.
When in doubt, do the work on a separate git branch and ask the user to run the build
themselves before merging — do not assume a build passes without them confirming it,
especially if you cannot reach the project's package registry (Maven Central, npm,
etc.) from your own environment.

## Part 1 — Wrap GraphQL HTTP response in a custom envelope

### When to use
The user wants every `POST /graphql` response reshaped, e.g. from the standard
`{ data, errors }` GraphQL-over-HTTP shape into something like:
```json
// success
{ "status": { "code": "0000", "message": "success" }, "data": { ... } }
// failed
{ "status": { "code": "4004", "message": "no data" } }
```
and wants it done at the servlet/filter level (`chain.doFilter(req, res)`, inspect
`res`, branch into `wrapSuccess`/`wrapFailed`) rather than by writing a new REST
controller that duplicates the GraphQL entrypoint.

### The core gotcha: Spring GraphQL (WebMVC) responds asynchronously
A filter that just does `chain.doFilter(req, res)` and then reads `res` will see an
**empty body**, because Spring GraphQL's handler returns immediately after starting
async processing. The servlet container dispatches the SAME request back into the
filter chain a second time once the handler has actually written the body. Handle
this correctly:

1. Restrict the filter to the GraphQL path only (`POST {spring.graphql.path}`,
   default `/graphql`) via `shouldNotFilter`.
2. Override `shouldNotFilterAsyncDispatch() = false` — Spring's default is `true`
   (skip the filter on the async dispatch), which is exactly the dispatch where the
   real body exists. Forgetting this override is the #1 cause of "the filter never
   sees any content to wrap".
3. Wrap both request and response with `ContentCachingRequestWrapper` /
   `ContentCachingResponseWrapper` so the body can be read without consuming the
   real stream. On each filter invocation, first check `WebUtils.getNativeRequest/
   Response(...)` for an existing wrapper from the prior dispatch — do not create a
   fresh wrapper on the async dispatch, or you lose everything captured so far.
4. On the first (REQUEST) dispatch, `request.isAsyncStarted` is `true` and the body
   is empty — do nothing and return.
5. On the second (ASYNC) dispatch, the body is populated — parse it, decide
   success/failure, replace the buffered content, then call
   `response.copyBodyToResponse()` in a `finally` block (always, even if wrapping
   throws) to actually flush bytes to the client. Forgetting this call means the
   client gets nothing back.
6. Let introspection queries (body contains `__schema` / `IntrospectionQuery`) pass
   through unwrapped, so GraphiQL/Postman/IDE tooling can still fetch the schema
   normally.
7. Decide success vs. failure by checking the standard response in this order:
   `errors` array non-empty → failed; `data` present but every field is `null`/an
   empty list → failed ("no data"); otherwise → success. Always check for errors
   **before** checking for empty data.

### Making the failure code extensible without touching the filter again
Don't hardcode a growing `when` block of exception types inside the filter. Instead:

1. Define one open/base exception (e.g. `BusinessException(message, statusCode)`)
   with a small set of subclasses for common cases (`NotFoundException`,
   `BadRequestException`, `ConflictException`, `ForbiddenException`, ...). Each
   subclass just supplies which status code it maps to.
2. Throw these from **any layer** — service, repository, controller. They flow up
   to the GraphQL engine like any other exception.
3. Add exactly **one** `@GraphQlExceptionHandler` (in a `@ControllerAdvice`) that
   catches the base exception type and puts the code/message into the GraphQL
   error's `extensions` map (e.g. `extensions["code"]`, `extensions["statusMessage"]`).
   Do not add a new handler method per exception subclass.
4. In the filter, read `extensions.code` first (exact, no guessing) and only fall
   back to guessing from Spring GraphQL's own `extensions.classification`
   (`NOT_FOUND`, `BAD_REQUEST`, `ValidationError`, `InvalidSyntax`, ...) for errors
   that never went through the custom exception hierarchy (bean validation, GraphQL
   syntax errors, etc).
5. Adding a new failure code going forward = add one enum entry + one exception
   subclass. No filter or handler changes needed.

### Testing this without depending on `spring-graphql-test`
Prefer testing the wrapped response with plain `MockMvc` doing a real
`POST /graphql` with a JSON body, then asserting on the wrapped JSON with
`jsonPath(...)`, over using `GraphQlTester`/`ExecutionGraphQlServiceTester`. Reasons:
- `ExecutionGraphQlServiceTester` calls the GraphQL engine in-process and never goes
  through the servlet filter chain, so it cannot verify the wrapping filter at all.
- A plain MockMvc POST exercises exactly what a real client (Postman, frontend,
  another service) experiences, filter included.
- It has zero dependency on the `spring-graphql-test` artifact's version, which
  matters when the runtime `spring-graphql` version is being changed (see Part 2).

MockMvc caveat: the controller handler is async, so a plain
`mockMvc.perform(post(...))` returns before the body is written. Check
`result.request.isAsyncStarted` and, if true, follow up with
`mockMvc.perform(asyncDispatch(result))` before asserting.

## Part 2 — Upgrading spring-graphql / graphql-java version

### When to use
The user wants to bump `spring-graphql` (and/or the `graphql-java` it pulls in) to a
newer minor/major line, typically **without** also bumping the Spring Boot parent
version (they want to stay on their current Boot line).

### Step 1 — Find out exactly what's pinned today
Spring Boot's own dependency BOM (`spring-boot-dependencies`) hard-pins both
`graphql-java` and `spring-graphql` as a single "Spring GraphQL" library entry
covering the `org.springframework.graphql` group (`spring-graphql` AND
`spring-graphql-test` together — they always move as a pair, never split them).
Find the exact pinned values for the project's current Boot version by reading
`spring-boot-dependencies/build.gradle` at the matching Boot git tag (raw GitHub URL,
e.g. `https://raw.githubusercontent.com/spring-projects/spring-boot/v<BOOT_VERSION>/spring-boot-project/spring-boot-dependencies/build.gradle`).

### Step 2 — Find out what the target version actually needs
Read the target `spring-graphql` git tag's own `build.gradle`
(`https://raw.githubusercontent.com/spring-projects/spring-graphql/v<TARGET_VERSION>/build.gradle`)
for its `graphQlJavaVersion`, `springFrameworkVersion`, and `springBootVersion`
properties — these tell you what GraphQL Java baseline it needs and, importantly,
which Spring Framework/Boot version the maintainers actually built and tested it
with. Also check the project's GitHub "wiki" page for that minor version
(`https://github.com/spring-projects/spring-graphql/wiki/Spring-for-GraphQL-<X.Y>`)
for stated baseline bumps (e.g. "requires GraphQL Java 24+") and any documented
behavioral/breaking changes. Check the spring.io blog release announcements too —
they sometimes call out security fixes (CVEs) or say a line is the "last OSS release"
of its generation.

### Step 3 — Compare and identify the real risk
- If target's required `graphql-java` major version is higher than what the
  current Boot pins → override is needed, but this is **officially supported**:
  Spring Boot exposes a `<graphql-java.version>` and `<spring-graphql.version>`
  Maven/Gradle property specifically for this (see Boot's "Version Properties"
  appendix page for the current property names). Setting both properties is the
  correct, low-risk fix — it is not a hack.
- If the target's tested Spring Framework version is a minor version ahead of what
  the current Boot line ships (e.g. target tested with 6.2.x, current Boot line
  ships 6.1.x) → **this is the one risk that cannot be resolved by reading
  documentation.** There is usually no stated hard minimum, and Spring Framework
  minor versions are usually backward compatible, but it is genuinely unverified
  until the full test suite actually runs against the unchanged Framework version.
  Say this plainly to the user as the one remaining unknown, don't downplay it and
  don't claim it will definitely work or definitely break.
- Do **not** try to keep `spring-graphql-test` pinned to the old version while
  bumping `spring-graphql` (main) to the new one. They are released as a matched
  pair; forcing them apart is a combination nobody tested and adds risk instead of
  removing it. If the goal is to reduce exposure to `spring-graphql-test`'s API
  surface changing across versions, do that by testing via plain MockMvc (Part 1's
  testing approach) instead of pinning mismatched versions.

### Step 4 — Apply and verify
1. Do the version bump on a separate branch, never directly on the branch the user
   is currently shipping from.
2. Only change the two version properties (`spring-graphql.version`,
   `graphql-java.version`) — do not touch the Spring Boot parent version unless the
   user asked for that too.
3. If you cannot reach the project's package registry from your own environment to
   compile/test, say so explicitly and ask the user to run the full test suite
   (`mvn clean test` / `./gradlew test`) themselves. Never claim or imply a build
   passed without that confirmation.
4. Report back precisely what was changed (which properties, which files) and what
   remains unverified, so the user can decide whether to merge.

## Reporting back
At the end of applying either part, tell the user in plain terms:
- Exactly which files changed and what changed in each (not just "updated X").
- Which parts you verified yourself vs. which parts still need the user to run a
  build/test locally because you couldn't reach the registry or the target
  environment.
- Any behavior that is different from before, even if intentional and asked for —
  never let a behavior change slip in silently.
