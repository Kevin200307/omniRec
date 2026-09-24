# Security policy

## Supported versions

Omnirec is at version 0.1.0 and has not yet had a stable release. Security fixes
are applied to the latest release and to `main`.

| Version | Supported |
| --- | --- |
| 0.1.x | Yes |

## Reporting a vulnerability

Do not report security vulnerabilities through public issues, pull requests, or
discussions.

Report them privately using GitHub's private vulnerability reporting for this
repository:

https://github.com/Kevin200307/omniRec/security/advisories/new

Please include:

- a description of the vulnerability and its potential impact;
- the affected component (for example the Event API gateway, the browser SDK, or
  a destination adapter) and version;
- steps to reproduce, or a proof of concept;
- any suggested mitigation.

Do not include real credentials, real customer data, or production payloads in a
report. Redact them, or use synthetic values.

The maintainers aim to acknowledge a report within seven days and to keep the
reporter informed of progress. This is a volunteer-run project, so these are
targets rather than guarantees. Please allow a reasonable period to develop and
release a fix before any public disclosure. Reporters who wish to be credited in
the advisory will be.

## Scope

In scope are vulnerabilities in the code in this repository. Examples of issues
the project treats as security-relevant:

- exposure or leakage of provider credentials, API keys, or secrets;
- bypass of API key authentication, tenant isolation, payload limits, or rate
  limiting in the Event API;
- collection or transmission of sensitive data (card numbers, security codes,
  passwords, authentication tokens) that the validators are meant to reject;
- personal data written to logs or event payloads that the sanitizers are meant
  to remove;
- cross-tenant reads or writes of events, deduplication state, or identity links;
- injection or denial-of-service defects reachable from an unauthenticated
  request.

Out of scope are vulnerabilities in third-party services and dependencies
themselves (report those to their maintainers), findings that require access
already granted to the operator, and deployments that deliberately disable a
protection, for example by enabling `allow-anonymous-ingestion` outside a
development profile.

## Security model

The trust boundaries, the handling of publishable keys, the request controls, and
the rules for sensitive data are described in
[docs/security.md](docs/security.md). The central guarantee is that provider
credentials are never present in the browser; a report that demonstrates a way to
defeat it is of the highest priority.

## Operator guidance

Operators are responsible for the security of their own deployment. See the
production checklist in [docs/configuration.md](docs/configuration.md#production-checklist)
and [docs/guide.md](docs/guide.md#production-checklist). In particular, use a
distinct API key per tenant, restrict CORS origins, supply provider credentials
through an IAM role or workload identity rather than environment variables, and
keep the Event API behind TLS.
