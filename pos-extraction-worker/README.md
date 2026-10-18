# pos-extraction-worker

The extraction worker (CAP:550, story S26, issue #2519): a stateless deployable that opens **untrusted bill
files** on pos-accounting's behalf, and (in later parts) holds the extraction provider's credentials. It
stores nothing, emits no events and has no datasource, no Kafka and no tenancy runtime (S26 ruling 13).
`pos-documents` is its precedent: a utility module under ADR-0044 §1, outside `ADOPTED_MODULES`.

This README describes **Part A**: the module, the shared-secret and tenant filters, the parse sandbox, and the
`inspect` and `render` operations. `tabulate`, `extract` and the Textract provider arrive in later parts of
#2519.

**Not in the gateway index or the SDKs.** The gateway has no route to `extraction-worker`, and the
`openapi.yaml` here is documentation only: `scripts/generate-openapi.sh` keeps it out of the aggregate
(`AGGREGATE_EXCLUDED_MODULES`), so it never reaches `openapi-aggregate.yaml` or an SDK.

## Contract

Base path `/extraction-worker/v1`, Eureka id `extraction-worker`, container port 8080. Every request carries

| Header | Meaning |
| ------ | ------- |
| `X-Extraction-Worker-Secret` | Shared secret, compared in constant time with `pos.extraction-worker.api-secret` |
| `X-Tenant-Id` | Tenant UUID. A log and metric label only: never authorisation, never stored |

All bodies are `multipart/form-data`; the file part is `file`, at most 25 MiB.

| Operation | Parts | 200 response |
| --------- | ----- | ------------ |
| `POST /inspections` | `file`, `declaredType` in `PDF JPEG PNG HEIC TIFF CSV XLSX` | JSON `{verdict: ACCEPTED \| REJECTED, reasonCode?, detectedType, pageCount, sha256}` |
| `POST /renderings` | `file`, `declaredType` in `PDF JPEG PNG HEIC TIFF`, `page` (1-based) | `image/png`, one page, metadata stripped, at most `render.max-pixels` |

`pageCount` is PDF pages, TIFF frames, 1 for a single image, and for CSV and XLSX the count of **data rows**
(non-empty rows after the first physical row). It is 0 when the file is rejected. A rejected file is still a
200 with `verdict: REJECTED`: the verdict is the answer, not an error. No pages and no copy of the file come
back from `inspect` (S25 ruling 1); a page is drawn on demand by `render`.

Both operations are idempotent and side-effect free; nothing is cached between calls.

### Error codes

| Status | Code | When |
| ------ | ---- | ---- |
| 400 | `VALIDATION_FAILED` | A part is missing, `declaredType` is unknown or (for render) not renderable, `page` is below 1. `fieldErrors` names the field |
| 401 | `EXTRACTION_WORKER_SECRET_MISSING` | The worker's own secret is blank: every request is refused |
| 401 | `INVALID_EXTRACTION_WORKER_SECRET` | The header is missing or wrong |
| 401 | `TENANT_REQUIRED` | `X-Tenant-Id` is missing or not a UUID (checked after the secret) |
| 413 | `FILE_TOO_LARGE` | The file is over `max-file-bytes` |
| 422 | `PAGE_NOT_RENDERABLE` | Render: the page is past the last page, the file is unreadable, a deadline or memory limit was hit, or the bytes do not match `declaredType` |
| 503 | `WORKER_BUSY` | Every parse slot is taken; `Retry-After: 5` |

Neither secret filter reads the request body. A caller without the secret makes the worker parse nothing and
write nothing beyond the multipart temp file the container would write anyway (deleted at once).

### Inspect reason codes

Checked in this order; the first failing check wins.

| Reason | Meaning |
| ------ | ------- |
| `TYPE_MISMATCH` | The first bytes do not match `declaredType` (CSV: not text, it holds NUL bytes) |
| `ENCRYPTED_PDF` | The PDF is encrypted, with or without an empty user password |
| `PAGE_LIMIT_EXCEEDED` | A PDF has more than `pdf.max-pages` pages, or a TIFF more than `image.max-tiff-frames` frames; decided before anything renders |
| `PDF_ACTIVE_CONTENT` | Any object in the file is JavaScript, a launch action, an embedded file, a file attachment, an `/OpenAction` or an `/AA` (the whole object graph is walked, not only the catalog) |
| `DECOMPRESSION_LIMIT_EXCEEDED` | An XLSX archive passes the zip limits below |
| `MACRO_CONTENT` | An XLSX has a `vbaProject` part or a macro-enabled content type |
| `EXTERNAL_ENTITY` | An XLSX XML part holds `<!DOCTYPE` or `<!ENTITY`, or starts with a UTF-16/32 marker that would hide one; checked before POI opens the file |
| `RESOURCE_LIMIT_EXCEEDED` | An image header declares more than `image.max-pixels`; a spreadsheet passes the row, column or cell limits; or the child hit its deadline or heap cap |
| `UNREADABLE` | Any other parse failure, an empty file, or a child that exited abnormally or wrote output that failed validation |

## The parse sandbox

A thread stuck inside PDFBox cannot be stopped safely, and an `OutOfMemoryError` leaves a shared heap in an
undefined state, so **the web process never parses file structure** (S26 ruling 5). It receives the upload,
streams it to a per-call scratch directory while hashing it, checks the size and the first sixteen bytes, and
admits the call to a bounded pool. Everything that parses runs in **a child JVM per call**:

- arguments `-Xmx<child-heap> -Xss2m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError
  -XX:ActiveProcessorCount=1 -XX:-UsePerfData -Djava.awt.headless=true -Djava.io.tmpdir=<callDir>`, main class
  `com.positivity.extractionworker.internal.sandbox.ParseMain`;
- an **emptied environment**: only `PATH`, `JAVA_HOME` and `LANG`. The child never holds the shared secret or
  any AWS variable;
- killed (with its descendants) when the per-operation deadline passes: a deadline or an out-of-memory exit
  (code 3) is `RESOURCE_LIMIT_EXCEEDED` for inspect and 422 for render; any other abnormal exit is
  `UNREADABLE`;
- stdout and stderr go to a file in the call directory that is **never read and never logged**, and is
  deleted with the directory: a library's exception message can quote file content;
- its result is **untrusted**: `result.json` must be at most 4 KiB and parse as the result record, and a
  rendered PNG must have a PNG signature, header dimensions within `render.max-pixels` and a bounded size
  (checked from the header bytes, without decoding it).

The child classpath is the web JVM's own `java.class.path`. The Dockerfile unpacks the Spring Boot jar
(`-Djarmode=tools extract`) so that classpath is a plain `app.jar` plus `lib/*`, and the child starts without
the Boot loader. Parsers of hostile input live only in `internal.sandbox`; `ArchitectureTest` fails the build
if the web process (controllers, services, config, security, DTOs) imports PDFBox, POI, `javax.imageio` or
commons-csv, and if the sandbox imports Spring, a provider or AWS.

Each call's scratch directory is created under `scratch-dir` (mode 0700), deleted recursively when the call
ends whatever the outcome, and leftovers named `xw-*` are swept at start-up.

**HEIC.** No pure-Java decoder exists. The child runs libheif's `heif-dec` (Alpine `libheif-tools`) as a
grandchild with its own timeout, writing a JPEG that then takes the ordinary image path. The declared
dimensions are read from the file's `ispe` boxes first, so an oversized HEIC is refused before the native
decoder runs. The stored source file stays HEIC. HEIC tests skip where `heif-dec` is not installed.

## Operating settings (`pos.extraction-worker.*`)

| Setting | Default | Meaning |
| ------- | ------- | ------- |
| `api-secret` (`POS_EXTRACTION_WORKER_API_SECRET`) | blank | Shared secret. Blank refuses every request (401 `EXTRACTION_WORKER_SECRET_MISSING`) |
| `scratch-dir` (`POS_EXTRACTION_WORKER_SCRATCH_DIR`) | `/scratch` | Parent of each call's private directory; a tmpfs in Compose |
| `max-concurrent-parses` | 2 | Parse slots; the next call is 503 `WORKER_BUSY` and is never queued |
| `max-file-bytes` | 26214400 | Largest upload (25 MiB) |
| `sandbox.child-heap` (`POS_EXTRACTION_WORKER_CHILD_HEAP`) | `768m` | Child `-Xmx` |
| `sandbox.child-classpath` | blank | Child classpath; blank means the web JVM's own |
| `sandbox.child-main-class` | `ParseMain` | Child entry point; only a test overrides it |
| `deadline.inspect` / `render` / `tabulate` / `extract` | 30 s / 30 s / 30 s / 120 s | Child kill deadlines (`tabulate` and `extract` arrive in a later part) |
| `pdf.max-pages` | 30 | Most PDF pages |
| `image.max-pixels` | 50,000,000 | Most pixels an image header may declare |
| `image.max-tiff-frames` | 30 | Most TIFF frames |
| `spreadsheet.max-rows` / `max-columns` / `max-cell-chars` | 10,000 / 100 / 1,000 | CSV and first-XLSX-sheet limits |
| `spreadsheet.max-zip-entries` | 10,000 | Most entries in an XLSX archive |
| `spreadsheet.max-zip-entry-bytes` / `max-zip-total-bytes` | 100 MiB / 200 MiB | Most bytes one entry, and the archive, may inflate to (counted as read, not as declared) |
| `spreadsheet.min-inflate-ratio` | 0.01 | Smallest compressed-to-inflated ratio an entry may show once past 1 MiB |
| `render.dpi` | 150 | PDF render resolution |
| `render.max-pixels` | 25,000,000 | Most pixels in a rendered page; the DPI (PDF) or the image (scaled down) is reduced to fit |
| `heif.binary` / `heif.timeout` | `heif-dec` / 20 s | HEIC decoder command and deadline |

Only the first sheet of an XLSX is read, and it is streamed (SAX), never loaded as a workbook. No formula is
evaluated. CSV is read as UTF-8 (byte-order mark stripped) falling back to ISO-8859-1, with the delimiter
sniffed among comma, semicolon and tab from the first 20 lines.

## Metrics and health

Micrometer, tags `tenant`, `operation`, `outcome`, `reason`: `extraction.calls`, the timer
`extraction.duration`, `extraction.limit_breaches`; the gauge `extraction.pool.in_use`. The `parsePool` health
indicator reports `inUse`, `capacity` and `saturated` and is always UP: a full pool tells callers to retry, it
does not make the worker unserviceable. Prometheus scrapes `/actuator/prometheus` with the shared scrape
credentials, as every other service.

## Log policy (ADR-0072)

Logs, metrics, exceptions and error messages never carry file bytes, file names, extracted values, child
stderr, the shared secret or credentials. The only fields logged are tenant id, sha256, size, declared and
detected type, page count, operation, outcome, reason code and duration. A library exception is logged by
class name only. `LogPolicyTest` captures every logger at DEBUG across a success, a rejection, a refused
secret and a type mismatch and asserts none of them leaks.

## Deployment

Compose and alpha run the worker with no `ports:`, a read-only root file system, a non-root user (10001), all
capabilities dropped, `no-new-privileges`, `mem_limit: 2g` (tmpfs counts against it), `cpus: 2.0`,
`pids_limit: 128`, tmpfs `/scratch` (512 MiB, mode 0700) and `/tmp`, and `AWS_EC2_METADATA_DISABLED=true`. It
joins only `extraction-net`, a Compose network with `internal: true`, shared with `eureka-server`,
`pos-accounting`, `prometheus` and `otel-collector`. The worker is not on `pos-network`, so it has no route to
the internet or to the instance metadata service (S26 ruling 11). The web JVM runs with `-Xmx256m`; each child
adds its own `child-heap`, so `max-concurrent-parses` times `child-heap` is the memory the container must
hold: raise `mem_limit` together with either.

Alpha keeps `POS_EXTRACTION_WORKER_PROVIDERS=none` until Security records the Textract retention check
(AW61).
