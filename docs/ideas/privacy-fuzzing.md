# Idea: pluggable privacy-fuzzing policy ("controlled distortion")

Parked — do not build before Milestone 3.

Inrupt's Charlie markets deliberate data distortion: personal data sent to an LLM is
fuzzed (ages bucketed, locations coarsened, identifiers dropped) so the user trades a
little answer accuracy for privacy. In Cistern this belongs at the pod boundary as an
open, auditable policy layer:

- A `DisclosurePolicy` SPI applied in `cistern-mcp` (and optionally HTTP) on read:
  given (agent, resource, graph) → transformed graph.
- Policies as data in the pod itself (the user edits their own policy resource),
  e.g. "agents in class X see my birth *year* only, never my address".
- Open implementation = the distortion is inspectable, unlike a closed intermediary.

Prior art to research when picked up: differential privacy budgets, SPARQL-based
redaction, ODRL policy vocabularies.
