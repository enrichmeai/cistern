# Commercial model

Cistern is free for any use, including commercial, under the Apache License 2.0 —
**that will not change.** This page records how the company behind it earns revenue,
so that contributors, partners and customers never discover the boundary by surprise.

## The principle

The software is free; **money moves where liability and convenience move** — to whoever
must *prove* what their AI touched, and whoever does not want to *run* infrastructure.
The person whose data it is never pays.

## The four revenue lines

| Line | Who pays | What they are buying | Status |
|---|---|---|---|
| **1 · Support & SLA** | Any organisation running Cistern in production (a firm, an application company, a public body) | Accountability: response times, upgrades, security patches, someone to call when the audit is due. Annual, per deployment. | Available from the first production deployment |
| **2 · Integration engineering** | The organisation adopting Cistern | The wiring: their identity provider → WebIDs, their application → the vault, their estate → the posture in `docs/deploy.md`. Project fee; waived or discounted for design partners in exchange for the public case study. | Available now |
| **3 · Sponsored features** | Whoever needs a roadmap item first (see `docs/BACKLOG.md`) | Priority, not exclusivity — the feature ships open, for everyone. | Available now |
| **4 · Hosted vaults** | An organisation whose customers get vaults (a firm for its clients; an application for its users; a hoster reselling under its own brand) | No operations at all: a vault per client or user, run as a service. | Later — the design decision is issue #103 |

AI-assistant vendors pay nothing: assistants connect over MCP, and it is *their customers*
— the organisations above — who buy one of the four lines.

## What is open, and what is not

Everything in this repository is and stays Apache 2.0: the server, the CLI, the
integration kit, the deployment tooling, the documentation. **Not open:** the hosted
service's multi-tenant machinery — console, billing, tenancy — which lives in a separate,
all-rights-reserved repository (`cistern-cloud`). The boundary is drawn there so that a
self-hoster gets a complete, production-capable server, and the hosted *product* remains
the company's to sell.

## The first revenue path

The first commercial realisation runs **through the first application**:
[Valuedocs Legal](https://legal.valuedocs.co.in) — AI legal research for Indian lawyers,
built by Valuedocs Private Limited (a separate company; Cistern is consumed as an open
component at arm's length, no shared IP) — is deploying Cistern as the authority layer
under client matter files, and shipping an **Indian-law connector (MCP)** so firms can use
that research inside the AI they already run (Claude for Legal, Harvey and other MCP
clients), with every matter access governed by a Cistern grant and recorded as a receipt.

Revenue flows in that path:

- **Valuedocs Pvt Ltd** earns per-seat subscription and connector fees from lawyers and
  firms — its revenue, its product.
- **Cistern's revenue** is realised as lines 1, 2 and (later) 4 sold to Valuedocs and to
  the firms that follow it: support for the deployment, integration where a firm wants the
  vault in its own estate, hosted vaults when #103 lands. The Valuedocs deployment is also
  the public proof (tracking issue #106) that makes those sales possible.

The sequence is deliberate: **proof first, revenue through the proof, hosted product from
what the proof teaches.**

## Contact

Via [enrichmeai.com](https://enrichmeai.com), or open a GitHub issue labelled `commercial`.

Cistern is developed by **EnrichMeAI**, a trading name of Good Shepherd Software
Consultancy Ltd (registered in England & Wales, Company No. 09702990).
