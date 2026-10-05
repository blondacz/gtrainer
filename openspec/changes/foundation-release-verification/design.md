# Design

## Context

The former foundation task 6.1 remains unverified. CI source checks do not prove image publication, manual promotion, Pi rollout, private access, or end-to-end import behavior. Current operational documentation requires an explicit promotion merge and compatible backup/restore preparation.

## Goals / Non-Goals

**Goals:**
- Verify the existing release path and record evidence for each gate.
- Keep deployment, real imports, and model inference separately authorized.

**Non-Goals:**
- Change release automation, production database schema, model qualification, or coaching behavior.
- Treat passing CI as approval to publish, promote, deploy, or import personal data.

## Decisions

- Follow `docs/infrastructure/image-promotion.md` and `docs/infrastructure/backup-restore-rollback.md`; do not bypass their manual-merge or recovery requirements.
- Use synthetic data for end-to-end verification. Any live import or deployment needs explicit user approval at the time.
- Report each gate independently. A blocked or skipped stage remains unverified, not passed.

## Risks / Trade-offs

- A release may be blocked by missing backup readiness, workflow approval, or cluster health; record the blocker rather than weakening safeguards.
- End-to-end verification may remain incomplete without explicit live-operation approval.
