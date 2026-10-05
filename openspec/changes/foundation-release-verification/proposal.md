# Proposal

## Why

The foundation implementation passed source and synthetic checks, but its full release path to the Pi was never verified. Track former foundation task 6.1 independently from connected-insights model work.

## What Changes

- Verify the protected ARM64 image publication and manual promotion flow, compatible backup/restore readiness, Flux rollout, private dashboard access, and read-only Intervals.icu history flow.
- Record release evidence and remaining limitations without importing personal records or enabling model inference.

## Capabilities

### New Capabilities

None. This is verification of existing release behavior.

### Modified Capabilities

None. No user-visible system behavior changes.

## Impact

Operational verification of the existing CI, image registry, GitHub promotion PR, backup/restore procedure, Flux and Raspberry Pi deployment. Requires separate explicit approval before any live rollout, import, or migration.
