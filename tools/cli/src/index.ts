// SPDX-License-Identifier: Apache-2.0
export { CatalogLoadError, loadCatalog, type CatalogIssue } from "./catalog/load";
export * from "./catalog/model";
export { findDrift, generateAll, writeGenerated, type Drift, type DriftKind } from "./generate";
export { OWNED, PATHS, type GeneratedFile, type OutputLayout } from "./generate/types";
export { domainTypeName } from "./generate/typescript";
export { javaConstantName } from "./generate/java";
export { findRepoRoot, runGenerate, type GenerateOptions } from "./commands/generate";
export { runInit, detectFramework, snippet, PLAN_TEMPLATE, type Framework, type InitOptions } from "./commands/init";
export { runValidate, type ValidateOptions } from "./commands/validate";
export { runProjectGenerate, type ProjectGenerateOptions } from "./commands/project-generate";
export { startDevServer, type DevOptions, type DevServer, type ReceivedEvent } from "./commands/dev";
export { parsePlan, loadPlanFile, indexWithPlan, PlanError, type Plan } from "./project/plan";
export { diffPlans, unapproved, type PlanChange, type ChangeKind } from "./project/diff";
export { checkEvent, type EventCheck, type EventProblem } from "./project/validate-event";
export { EventIndex, STANDARD, type RuntimeCatalog, type RuntimeEvent, type RuntimeField } from "./project/runtime";
export { pluginTypes, javaConstants } from "./project/typegen";
