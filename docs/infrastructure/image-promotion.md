# Protected automated image promotion

Status: task 2.4 prepared; live protection/promotion verification pending.
The currently running Pi image stays unchanged until the promotion PR passes.

## Release path

1. A trusted `main` build runs app/synthetic/privacy tests and release-provenance
   validation. Only successful jobs allow ARM64 publication and immutable-digest
   startup/UI/API-denial verification.
2. Publication records a tiny metadata-only artifact: repository, source commit,
   build run ID, and the verified image digest. No records, prompts, secrets,
   build directories, or reports are uploaded into this artifact.
3. The promotion job runs only from `main` in the `image-promotion` environment,
   whose deployment policy permits only protected branches. It has temporary
   repository/PR/workflow permissions, never SSH, kubeconfig, health-data keys,
   or access to the home network. No personal token/App private key is added.
4. It verifies the CI-visible active main rules/checks and successful prerequisite
   jobs, then matches the image to the original CI artifact. GitHub redacts the
   bypass list from CI's non-admin token: the operator separately verifies the
   actual empty bypass list during setup/rollout audit. CI rejects bypass actors
   if the API supplies them, but never treats omission as proof of no bypass.
   No administration token is supplied to CI. If `main` advanced
   during the build, it refuses to propose an obsolete release.
5. It creates a candidate branch/PR changing only the image line in
   `deploy/gtrainer/deployment.yaml` and `deploy/gtrainer/release.json`, explicitly
   dispatches checks on that branch, and enables squash auto-merge. It never
   pushes directly to `main` or force-updates an existing PR.
6. GitHub requires passing **Tests and build** and **Verify release provenance**
   checks from the verified GitHub Actions integration (ID 15368), an up-to-date
   branch, a PR, and resolved review threads. The ruleset has no bypass actors,
   including no administrator bypass. As a single-maintainer project, there is
   no required second-person review: protection is from enforced checks, not a
   claim of independent human approval.
7. After merge, Flux reads `main` outbound and applies the new immutable image.
   CI does not perform or observe the cluster rollout itself. Operator SSH
   verification confirms the expected digest and private access boundary.

## One-time GitHub configuration

The operator's existing authenticated GitHub CLI has repository administration
access. Use it outside CI to:

- Enable repository auto-merge and workflow PR creation. Although GitHub exposes
  PR creation/approval as one setting, this workflow never submits approval
  reviews and grants no bypass.
- Create the ruleset from `.github/promotion-ruleset.json`. Do not silently
  replace an existing ruleset; inspect any conflicts first.
- Create `image-promotion` with protected-branches-only deployment policy, no
  secrets, and no required reviewer (automatic promotion after tests).

Thereafter, normal code/document changes also use PRs rather than direct main
pushes. Do not use `[skip ci]` on a PR requiring checks. Automation-created PR
events may be approval-pending in GitHub; the explicit `workflow_dispatch` starts
the candidate checks without introducing a long-lived credential. No package
writes or promotion job runs on the candidate branch.

## Evidence lifetime, retries, and rollback

- Build artifacts are retained for 90 days. A newly proposed/changed release
  must match an unexpired artifact and successful original jobs. An unchanged
  descriptor already accepted on protected main remains trusted after artifact
  expiry; subsequent source builds do not get permanently stuck at day 91.
- The original task-2.2 bootstrap digest is the sole no-descriptor exception.
  Any different digest without release evidence fails verification.
- Artifact contents must have exactly the four public identity fields and match
  the Deployment image. Failed/skipped/cancelled prerequisites, fork/non-main
  builds, wrong workflow/source commit, missing/expired/oversized/mismatched
  artifacts, disabled protection, or a bypass rule are rejected.
- Each run gets a new candidate branch. If a promotion step fails after creating
  a PR, inspect that PR; do not overwrite it or relax protection. A fresh manual
  dispatch on current healthy `main` gives a new run ID. GitHub re-running a job
  can reuse its run/artifact ID, so is not the clean retry path.
- Rollback remains a reviewed Git image/evidence revert, not an SSH edit to a
  live Deployment. Task 2.6 must verify rollback and backup restore. If an old
  rollback artifact expired, stop and re-verify it rather than removing checks.

Network isolation, single-user authentication, and data/backup handling remain
independent requirements. Public software publication is not public-data consent.
