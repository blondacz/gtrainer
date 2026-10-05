export type Model = { providerId: string; modelId: string; label: string; tag: string; manifestDigest: string;
  ollamaVersion: string; contextWindow: number; tokenLimit: number; threads: number; seed: number; temperature: number }
export type Policy = { profile: string; attemptTimeoutMillis: number; totalTimeoutMillis: number }
export type Catalogue = { syntheticOnly: boolean; qualified: boolean; executionEnabled: boolean; reason: string;
  cases: string[]; models: Model[]; policy: Policy }
export type Prompt = { systemInstructions: string; userPacketJson: string }
export type Preview = { id: string; caseId: string; model: Model; policy: Policy; promptDigest: string; exactPrompt: Prompt }
export type Task = { id: string; previewId: string; queueOrder: number; caseId: string; slotKey: string; inputRevision: number;
  inputDigest: string; model: Model; policy: Policy; createdUtc: string; expiresUtc: string; outcome: string; execution: string;
  retention: string; replacementId: string | null; reason: string | null; latestInput: boolean }
export type Maintenance = { id: string; state: string; createdUtc: string; expiresUtc: string; reason: string | null; coalescedSignal: boolean }
export type Queue = { tasks: Task[]; maintenance: Maintenance[] }
export type Claim = { text: string; uncertainty: string; scope: { from: string; until: string; sport: string | null };
  sources: { evidenceIds: string[]; context: { contextId: string; revision: number }[] } }
export type Run = { task: Task; attempts: { number: number; phase: string; reason: string | null; createdUtc: string; updatedUtc?: string }[];
  inspection: { packetAvailable: boolean; exactPrompt: Prompt; review: { interpretations: Claim[]; questions: Claim[] } | null } }
export type Diagnostics = { worker: { configured: boolean; executingRunId: string | null; blocked: boolean; reason: string;
  unresolvedBoundaries: number; applianceMemoryLimitBytes: number | null; minimumHostHeadroomBytes: number;
  health: { generation: number; containerId: string; runtimeReady: boolean; availableHostBytes: number;
    observedApplianceLimitBytes: number; oldestMeasurementAgeMillis: number } | null };
  summaries: { eventId: number; trigger: string; createdUtc: string; reasons: string[]; affectedIds: string[];
    deleted: number; executed: boolean; blocked: boolean }[] }
