// SPDX-License-Identifier: Apache-2.0
export { CatalogLoadError, loadCatalog, type CatalogIssue } from "./catalog/load";
export * from "./catalog/model";
export { findDrift, generateAll, writeGenerated, type Drift, type DriftKind } from "./generate";
export { OWNED, PATHS, type GeneratedFile, type OutputLayout } from "./generate/types";
export { domainTypeName } from "./generate/typescript";
export { javaConstantName } from "./generate/java";
export { findRepoRoot, runGenerate, type GenerateOptions } from "./commands/generate";
