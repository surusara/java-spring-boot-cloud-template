# Developer Guide — reports-scheduler

## Project Overview

Spring Batch application running as AKS CronJobs. Two jobs:

| Job | Tasklets | K8s Schedule (Europe/Zurich) |
|-----|----------|------------------------------|
| **reportsJob** | DataExtraction → DataTransformation → ReportGeneration | Hourly, Mon–Sat |
| **deleteJob** | DataCleanup | Daily 7:00 AM |

Both accept `--businessDate=YYYY-MM-DD` (defaults to yesterday).

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+
- Docker (for local image builds)

## Local Build & Run

```bash
# Build
mvn clean package -DskipTests

# Run reports job
java -jar target/reports-scheduler.jar \
  --spring.batch.job.name=reportsJob \
  --businessDate=2026-03-19

# Run delete job
java -jar target/reports-scheduler.jar \
  --spring.batch.job.name=deleteJob \
  --businessDate=2026-03-19

# Run tests
mvn test
```

## Project Structure

```
src/main/java/com/example/reportsscheduler/
├── ReportsSchedulerApplication.java   # Entry point (exits with batch status code)
├── config/
│   ├── ReportsJobConfig.java          # reportsJob: 3 steps
│   └── DeleteJobConfig.java           # deleteJob: 1 step
└── tasklet/
    ├── DataExtractionTasklet.java     # Step 1 of reports
    ├── DataTransformationTasklet.java # Step 2 of reports
    ├── ReportGenerationTasklet.java   # Step 3 of reports
    └── DataCleanupTasklet.java        # Step 1 of delete
```

## Adding a New Tasklet

1. Create class in `tasklet/` implementing `Tasklet`
2. Annotate with `@Component`
3. Inject `@Value("${businessDate:...}")` if needed
4. Add it as a `@Bean Step` in the appropriate `*JobConfig.java`
5. Wire the step into the job chain with `.next(yourNewStep)`

## Adding a New Job

1. Create `NewJobConfig.java` in `config/`
2. Define one or more `Step` beans and a `Job` bean
3. Run with `--spring.batch.job.name=newJobName`
4. Create a new CronJob Helm template (copy `cronjob-reports.yaml`, change schedule/jobName)

## Versioning — What Developers Need to Know

### Day-to-Day (nothing special)

- `pom.xml` has `<revision>1.0.0-SNAPSHOT</revision>` — this is your current dev version
- Push to `main` → CI auto-builds image with tag `main-<sha>` → deploys to dev
- No version edits needed for regular development

### When Releasing

```bash
# 1. Ready to release 1.0.0? Create a Git tag:
git tag v1.0.0
git push origin v1.0.0

# 2. CI builds image tagged "1.0.0", publishes to Artifactory
# 3. DevOps promotes "1.0.0" to test/prod via GitLab manual trigger

# 4. Start next development cycle — bump SNAPSHOT version:
#    Edit pom.xml: <revision>1.1.0-SNAPSHOT</revision>
git add pom.xml
git commit -m "Start 1.1.0 development"
git push
```

### Version Rules

| When | Action |
|------|--------|
| After tagging release | Change `<revision>` to next SNAPSHOT (e.g., `1.0.0-SNAPSHOT` → `1.1.0-SNAPSHOT`) |
| Bugfix on released version | Branch from tag, fix, tag `v1.0.1` |
| Breaking change | Major version bump (e.g., `2.0.0-SNAPSHOT`) |

### What you **never** need to do

- Edit `values-*.yaml` image tags — the pipeline handles this
- Edit `Chart.yaml` versions — CI sets these from the Git tag
- Manually push Docker images — CI does it
