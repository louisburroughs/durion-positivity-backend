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

Base path `/extraction-worker/v1`, container port 8080. The worker does not register with Eureka: callers use the fixed URL of `extraction-proxy` (`http://extraction-proxy:8080`, see Deployment). Every request carries

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
| 503 | `WORKER_UNAVAILABLE` | The worker's own machinery failed: the child JVM could not start (broken classpath, no process), the call directory could not be written, or `heif-dec` is missing. It is never a verdict on the file and never a rejection (S26 ruling 2); `Retry-After: 30` |

Neither secret filter reads the request body. A caller without the secret makes the worker parse nothing and
write nothing beyond the multipart temp file the container would write anyway (deleted at once).

### Inspect reason codes

Checked in this order; the first failing check wins.

| Reason | Meaning |
| ------ | ------- |
| `TYPE_MISMATCH` | The first bytes do not match `declaredType` (CSV: not text, it holds NUL bytes) |
| `ENCRYPTED_PDF` | The PDF is encrypted, with or without an empty user password |
| `PAGE_LIMIT_EXCEEDED` | A PDF has more than `pdf.max-pages` pages, or a TIFF more than `image.max-tiff-frames` frames; decided before anything renders |
| `PDF_ACTIVE_CONTENT` | Any object in the file is an action of type JavaScript, Launch, SubmitForm, ImportData, GoToR or URI (wherever it sits, `/Next` chains included), has a `/JS` entry or a `/JavaScript` name tree, an `/XFA` form, an embedded file or a file attachment. The whole object graph is walked, not only the catalog. A plain go-to-page `/OpenAction` or `/AA` is **not** active content, so generated invoices that carry one are accepted. URI actions are refused on purpose: a hyperlink is outbound navigation from a file the worker cannot trust |
| `DECOMPRESSION_LIMIT_EXCEEDED` | An XLSX archive passes the zip limits below |
| `MACRO_CONTENT` | An XLSX has a `vbaProject` part or a macro-enabled content type |
| `EXTERNAL_ENTITY` | An XLSX XML part holds `<!DOCTYPE` or `<!ENTITY`, or starts with a UTF-16/32 marker that would hide one; checked before POI opens the file |
| `RESOURCE_LIMIT_EXCEEDED` | An image header declares more than `image.max-pixels`; a spreadsheet passes the row, column or cell limits; a PDF passes `pdf.max-stream-cache-bytes`; a HEIC declares more than `image.max-pixels` or declares no size at all (fail closed); or the child (or `heif-dec`) hit its deadline, heap or address-space cap |
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
- stdout and stderr are **discarded** (no file, nothing to fill the tmpfs or to read) and the child's logging is
  switched off with a bundled `child-logback.xml`: a library's exception message can quote file content;
- it asks the kernel to kill it first when memory runs out (`/proc/self/oom_score_adj` = 1000), so the web JVM
  outlives a runaway parse; exit 137 is read as an out-of-memory;
- a child that cannot start (JVM exit 1), a request that cannot be written and a missing decoder are
  **infrastructure faults**: 503 `WORKER_UNAVAILABLE`, never a rejection of the file. A start-up self-test
  runs the real child on a bundled PNG and probes `heif-dec --version`; a failure flips the `workerSelfTest`
  health indicator DOWN and never fails start-up;
- its result is **untrusted**: `result.json` must be at most 4 KiB and parse as the result record, and a
  rendered PNG must have a PNG signature, header dimensions within `render.max-pixels` and a bounded size
  (checked from the header bytes, without decoding it).

The web process reaches the sandbox only through `SandboxRequest`, `SandboxResult` and `SandboxLimits`; an ArchUnit rule (with a failing-fixture test) forbids it from calling `ParseMain` or a parser in process.

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
decoder runs. The decoder runs as `sh -c 'ulimit -v <heif.max-memory-mb> && exec heif-dec ...'`, so its address space is capped. The stored source file stays HEIC. HEIC tests skip where `heif-dec` is not installed.

## Operating settings (`pos.extraction-worker.*`)

| Setting | Default | Meaning |
| ------- | ------- | ------- |
| `api-secret` (`POS_EXTRACTION_WORKER_API_SECRET`) | blank | Local-development fallback only (used when no `api-secret-file` is set); Compose and alpha pass the file and no variable. Blank refuses every request (401 `EXTRACTION_WORKER_SECRET_MISSING`) |
| `scratch-dir` (`POS_EXTRACTION_WORKER_SCRATCH_DIR`) | `/scratch` | Parent of each call's private directory; a tmpfs in Compose |
| `max-concurrent-parses` | 2 | Parse slots; the next call is 503 `WORKER_BUSY` and is never queued |
| `max-file-bytes` | 26214400 | Largest upload (25 MiB) |
| `api-secret-file` (`POS_EXTRACTION_WORKER_API_SECRET_FILE`) | blank | File holding the secret (Compose mounts it at `/run/secrets/extraction_worker_secret`); wins over `api-secret`. Unreadable or blank refuses every call |
| `sandbox.child-heap` (`POS_EXTRACTION_WORKER_CHILD_HEAP`) | `512m` | Child `-Xmx` |
| `sandbox.child-classpath` | blank | Child classpath; blank means the web JVM's own |
| `sandbox.child-main-class` | `ParseMain` | Child entry point; only a test overrides it |
| `deadline.inspect` / `render` / `tabulate` / `extract` | 30 s / 30 s / 30 s / 120 s | Child kill deadlines (`tabulate` and `extract` arrive in a later part) |
| `pdf.max-pages` | 30 | Most PDF pages |
| `pdf.max-stream-cache-bytes` | 268435456 | Most bytes PDFBox may spill to its temp-file stream cache; a breach is `RESOURCE_LIMIT_EXCEEDED` |
| `image.max-pixels` | 50,000,000 | Most pixels an image header may declare |
| `image.max-tiff-frames` | 30 | Most TIFF frames |
| `spreadsheet.max-rows` / `max-columns` / `max-cell-chars` | 10,000 / 100 / 1,000 | CSV and first-XLSX-sheet limits |
| `spreadsheet.max-zip-entries` | 10,000 | Most entries in an XLSX archive |
| `spreadsheet.max-zip-entry-bytes` / `max-zip-total-bytes` | 100 MiB / 200 MiB | Most bytes one entry, and the archive, may inflate to (counted as read, not as declared) |
| `spreadsheet.min-inflate-ratio` | 0.01 | Smallest compressed-to-inflated ratio an entry may show once past 1 MiB |
| `render.dpi` | 150 | PDF render resolution |
| `render.max-pixels` | 25,000,000 | Most pixels in a rendered page; the DPI (PDF) or the image (scaled down) is reduced to fit |
| `heif.binary` / `heif.timeout` / `heif.max-memory-mb` | `heif-dec` / 20 s / 1024 | HEIC decoder command, deadline and address-space limit |
| `server.tomcat.threads.max` | 16 | Request threads (the container's `pids_limit` is 128) |

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

## Deployment and network topology

Compose and alpha run the worker with no `ports:`, a read-only root file system, a non-root user (10001), all
capabilities dropped, `no-new-privileges`, `cpus: 2.0`, `pids_limit: 128`, tmpfs `/scratch` (512 MiB, mode
0700) and `/tmp`, and `AWS_EC2_METADATA_DISABLED=true`. It does **not** register with Eureka.

```
pos-accounting --(pos-network)--> extraction-proxy --(extraction-net, internal)--> pos-extraction-worker
                                                        prometheus ------(extraction-net)------^ scrape
                                                        otel-collector --(extraction-net)------^ spans
```

- `extraction-net` is a Compose network with `internal: true`: no route to the internet or to the instance
  metadata service. Its members are the worker, `extraction-proxy`, `prometheus` and `otel-collector`. The
  worker is not on `pos-network`.
- **pos-accounting and eureka-server are not on `extraction-net`.** A compromised parser could otherwise reach
  pos-accounting's gateway-trusting chain (which believes `X-Authorities`, `X-User` and `X-Tenant-Id` from any
  caller) and Eureka's unauthenticated REST API.
- `extraction-proxy` (nginx, read-only, unprivileged, 128 MiB) is on both networks and forwards
  `/extraction-worker/v1/` to the worker and nothing else. pos-accounting calls the fixed URL
  `pos.accounting.bill-intake.extraction.base-url` (default `http://extraction-proxy:8080`), which Part D wires.
- `otel-collector` receives spans only. `prometheus` scrapes `/actuator/prometheus` with the shared scrape
  credentials. **Residual risk for Security sign-off:** prometheus's read-only HTTP API is reachable from a
  compromised parser.

### Secret handling and residual risks (for Security sign-off)

- The shared secret is a mounted file (`api-secret-file`), not an environment variable: `/proc/<pid>/environ`
  is readable by every process of the same uid, parse children included. At start-up the web JVM also marks
  itself non-dumpable (`prctl(PR_SET_DUMPABLE, 0)`, best effort, logged at INFO when unavailable), so its
  `/proc/self/*` files are root-owned. The child gets an emptied environment.
- **Residual: same uid.** The children run as the worker's uid, so a compromised child can still read another
  concurrent call's scratch files (each call's directory is mode 0700, but that does not separate processes of
  one uid) and signal other processes of that uid. Running children under a second uid is a follow-up, not part
  of this change.
- **Platform follow-up:** downstream modules trust gateway headers (`X-Authorities` and friends) from any
  in-network caller. Any future service that must share a network with untrusted-input code needs that trust
  closed first.

### Memory and process sizing

| Part | Budget |
| ---- | ------ |
| Web JVM (`-Xmx256m` heap, metaspace, threads, OpenTelemetry agent) | about 0.45 GiB |
| 2 slots x child JVM (`-Xmx512m` heap + up to 256 MiB metaspace cap, stacks, code) | 2 x 0.75 = 1.5 GiB |
| `/scratch` tmpfs (512 MiB) + `/tmp` tmpfs (64 MiB), counted against the limit | up to 0.56 GiB |
| Total, every slot and the tmpfs full | about 2.5 GiB |
| `mem_limit` | **3 GiB**, leaving about 0.5 GiB for `heif-dec` (it runs before the child decodes anything large, under `ulimit -v 1024`) |

Raise `mem_limit` together with `max-concurrent-parses` or `child-heap`. Process count: 16 request threads, about
30 for the web JVM's own threads, about 30 per child and a few for `heif-dec` stay under `pids_limit: 128`.

Alpha keeps `POS_EXTRACTION_WORKER_PROVIDERS=none` until Security records the Textract retention check
(AW61). The Compose secret reads the `POS_EXTRACTION_WORKER_API_SECRET` variable of the Compose environment
(`.env`); set it, or the worker refuses every call.
