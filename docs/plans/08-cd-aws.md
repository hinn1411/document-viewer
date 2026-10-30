# 08. CD and AWS (optional)

**Only do this if P0–P6 are done and at least a day is left.** The submission doesn't need it: the brief asks for a working service and a demo, which Docker Compose already gives. Its value is showing a walking skeleton, meaning the smallest real path from a merge to a running service on AWS, as the design doc's §5 describes.

> **Cost:** an ALB, RDS and Fargate charge for every hour they run. Create the stack just before the demo and destroy it straight after.

## T17: Production Docker image
Est 2 h · depends on T12

- A multi-stage `Dockerfile`: build with the Maven wrapper on a JDK 21 image, run on a JRE 21 image.
- Spring Boot layered jars, so dependency layers are cached.
- Runs as a **non-root** user. Heap is set as a percentage of the container memory (`-XX:MaxRAMPercentage=75`).
- Health check against `/actuator/health`.
- **Done when:** `docker build` works, the container starts against Compose's PostgreSQL and the mocks, and `docker run --rm <image> id -u` isn't 0.

## T18: Terraform walking skeleton
Est 4 h · depends on T17

A small single-environment stack in `infra/`, in one region:

| Resource | Purpose |
|---|---|
| ECR repository | Images tagged by git SHA, immutable |
| ECS cluster + Fargate service (1 task) behind an ALB | Runs the service. It's stateless, so scaling out means raising the task count (NFR4) |
| RDS PostgreSQL (smallest instance, single AZ for the demo) | Audit log and snapshots |
| Secrets Manager | The database password |
| WireMock as a second container in the same task | The mocked Sales and Service systems, as in local runs |
| IAM role for GitHub Actions through OIDC | The deploy workflow assumes it. No long-lived AWS keys |
| CloudWatch log group | Container logs |

- **Done when:** `terraform plan` is clean, `terraform apply` (which I run myself) brings up the stack, and `GET /actuator/health` through the ALB returns 200.
- **State:** stored locally for the demo, not committed. `.gitignore` already excludes `.env` files. Add `*.tfstate*` and `.terraform/` too.

## T19: Deploy workflow
Est 3 h · depends on T18 · simplified from devcool-core's `deploy.yml`

`.github/workflows/deploy.yml`, run on demand (`workflow_dispatch`) and optionally on push to `main`:

1. Assume the GitHub OIDC role (`aws-actions/configure-aws-credentials`).
2. Log in to ECR, then build and push `:<git-sha>`. Skip the build if that tag already exists.
3. Render the task definition with the new image, then deploy it and wait for the service to be stable (`aws-actions/amazon-ecs-deploy-task-definition`). Flyway runs migrations when the app starts.
4. Smoke test: poll `/actuator/health` through the ALB until it returns 200.
5. Write a job summary with the commit, the image and the result.

Left out on purpose (devcool-core has these, this project doesn't need them): environment sleep/hibernate states, a separate migration task, Trivy scanning and deregistering failed revisions.

**Done when:** a manual run deploys the current `main`, and the smoke test passes.

## Tear down

`terraform destroy`, which I run myself. The `guard-bash` hook blocks the agent from running it. Then check in the AWS console that the ALB and RDS are gone.
