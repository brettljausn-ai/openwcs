# Agent Team Orchestration

> A generic, reusable model for producing deliverables with a hierarchy of AI
> agents. Project-agnostic — pair it with a project's own rules (build steps,
> naming conventions, risk gates) which live in that project's config, not here.
>
> Owner: Karl Friesenbichler · Last updated: 2026-07-01

## Model Catalogue
The models available to assign to agents. Environment-specific — update this table
when the available models change; the tiering below refers to these tiers, not to
hardcoded names, so the rest of the doc stays portable.

| Tier | Model | Model ID | Use for |
|---|---|---|---|
| **Light** | Haiku 4.5 | `claude-haiku-4-5-20251001` | Mechanical, high-volume, low-ambiguity work: search, file-finding, bulk edits |
| **Standard (default)** | **Sonnet 4.6** | `claude-sonnet-4-6` | The default workhorse — research, drafting, docs, routine well-specified implementation |
| **Mid (escalation)** | **Opus 4.8** | `claude-opus-4-8` | Moderately hard work: non-trivial implementation, integration, tricky bugs |
| **Heavy (top)** | **Fable 5** | `claude-fable-5` | Judgment-heavy work: design/architecture, adversarial QA, high-risk gates |

**Default policy: prefer Sonnet 4.6 for all roles; escalate to Opus 4.8 for
moderately hard implementation and integration, and to Fable 5 for deep-judgment
roles** (design/architecture, adversarial verification, and any money/safety/
security/data-integrity gate). Drop to Light for cheap mechanical tasks. If a
listed model is unavailable in the environment, fall back up one tier
(Light→Standard, Standard→Mid, Mid→Heavy) rather than down.

## Purpose
Produce deliverables (code, documents, designs, processes) using a hierarchy of agents. The top-level **Orchestrator** runs one or more **Teams**. Each Team has a **Team Lead** managing **Sub-Agents**.

## Hierarchy
```
Orchestrator (you, by default)
├── Team A — Team Lead → Sub-Agents
├── Team B — Team Lead → Sub-Agents
└── Team C — Team Lead → Sub-Agents
```

## When to use this — right-sizing
Match the machinery to the work. Standing up a full Team for a one-line change wastes effort and obscures the change.

- **Trivial / low-risk / reversible** (typo, copy tweak, isolated bug fix): do it directly, verify, done. Skip the pipeline.
- **Single self-contained feature or document:** one Team, mandatory roles only, minimal discretionary roles.
- **Large, multi-area, or parallelisable work:** multiple Teams under the Orchestrator with a shared-interface registry (see below).

When in doubt, start smaller and escalate — add a Team or a role only when the work in front of you needs it.

## Execution model (how the hierarchy maps to real agents)
This is the layer that makes the structure work in practice. Read it before delegating.

- **Sub-agents are single-shot and stateless.** They receive a task, do it, and return one artifact. They do not hold a running conversation and cannot see each other's context.
- **The Orchestrator is the only persistent memory.** It relays every artifact from one agent to the next. The "handoff contract" below is enforced by the Orchestrator inspecting each returned artifact before passing it on — not by agents talking to each other.
- **"Reject the handoff back up" means:** the Orchestrator (or Team Lead) re-spawns the *upstream* agent with the specific defect list, rather than letting the downstream agent guess and proceed.
- **Pick the right agent kind for each role:** read-only exploration/research agents for grounding and sourcing; planning/design agents for specs; general implementation agents for building; verification agents for QA and testing. Use whatever agent types the host environment provides.
- **Parallelise independent work; serialise shared work.** Run agents concurrently when their inputs and outputs don't overlap. For agents that mutate the same working tree in parallel, give each an **isolated workspace** (e.g. a separate branch/worktree) and integrate afterward — never let two agents edit the same file at once.
- **For deterministic fan-out → verify pipelines**, prefer a scripted workflow (fixed loops/stages) over free-form delegation, so coverage and verification are guaranteed rather than model-chosen.

## Model Tiering
Do not run the top model for every agent — cost and latency scale with the tier,
and most roles don't need it. **Match the tier to the blast radius: how badly, and
how silently, the task can go wrong.** Assign models per agent (both the `Agent`
and `Workflow` tooling take a per-agent model override; workflows also take a
per-agent effort level from low→max — raise effort for the hardest verify/judge
stages).

Guiding pattern — **fan out cheap, verify expensive:** run many Light/Standard
agents in parallel to explore and draft, then gate their output behind a small
number of Heavy, high-effort adversarial checkers before anything ships. The one
place never to downgrade is verification and high-risk gates — a weaker model
rubber-stamps plausible-but-wrong output.

Default tier per role (see the Model Catalogue for what each tier maps to):

| Role / task | Tier |
|---|---|
| Search, file-finding, bulk mechanical edits | **Light** |
| Deep Researcher, exploration, grounding | **Standard** |
| Documentation Engineer, copy, drafting | **Standard** |
| Software Developer (well-specified impl) | **Standard** |
| Hard implementation, integration, tricky bugs | **Mid** |
| Design / Process Designer, architecture | **Heavy** |
| QA Engineer, adversarial verification, high-risk gates | **Heavy** (high/max effort) |

Deviate when a specific task justifies it — a gnarly bug in "routine" code may
warrant Heavy; a trivial design tweak may not. Tier the *task*, not just the title.

## Sub-Agent Skill Sets
| Agent | Owns |
|---|---|
| Deep Researcher | Sourcing, evidence, prior art, constraints |
| Process Designer | Workflows, sequences, decision logic |
| Graphic Designer | Visual design, diagrams, layout, brand |
| Operational Researcher | User operations modelling, real-world usage |
| Software Developer | Implementation |
| QA Testing Engineer | Test strategy, defect tracking, sign-off — **mandatory** |
| Functional Tester | Does it do what the spec says — **mandatory** |
| Operational Tester | Does it survive real operating conditions — **mandatory** |
| Documentation Engineer | All docs (user, technical, handover) — **mandatory** |
| Communications / Marketing | Public-facing or announcement material — _discretionary_ |

## Mandatory Roles
Every Team, regardless of deliverable type, MUST include:
- QA Testing Engineer
- At least one of Functional / Operational Tester (both if the deliverable runs in production or is operationally used)
- Documentation Engineer

A deliverable without QA sign-off and documentation is **not done**. Do not mark it complete.

## Discretionary Roles
The Team Lead selects which of the remaining agents (Researcher, Process Designer, Graphic Designer, Operational Researcher, Software Developer, Communications/Marketing) are needed for the specific deliverable. Do not spin up agents that add no value to the task at hand.

## Human Approval Gates
Some steps are the human's to decide, not the Orchestrator's. Define these gates per project; a typical one is **design approval** — the human signs off the design/spec before any Planning or Development begins.

- At a gate, the Orchestrator **stops** and presents the artifact for approval. It does not proceed on assumption.
- Work downstream of an unapproved gate does not start.
- Record which artifact was approved (see the handoff table), so later work traces back to an approved baseline.

## Handoff Contract (enforce this — it is the point of the structure)
Each agent produces a defined artifact that the next consumes. No agent invents the interface ad hoc. The Orchestrator carries each artifact to the next consumer.

| From → To | Artifact |
|---|---|
| Deep Researcher → all | Findings brief: constraints, sources, open questions |
| Operational Researcher → Process/Dev | Usage model: who, what, edge cases, volumes |
| Process Designer → Developer/Designer | Process spec: steps, inputs, outputs, decision points |
| Design → **Human gate** → Planning | Approved design/spec (the baseline all later work traces to) |
| Graphic Designer → Dev/Docs | Design assets + usage notes |
| Software Developer → QA/Testers | Build + acceptance criteria it claims to meet |
| Testers → QA Engineer | Test results: pass/fail per criterion, defects |
| QA Engineer → Team Lead | Sign-off or rejection with defect list |
| Documentation Engineer → Team Lead | Docs covering what was built and how to use it |

If an upstream artifact is missing or ambiguous, the downstream agent rejects the handoff back up — it does not guess and proceed. In practice the Orchestrator re-spawns the upstream agent with the gap identified.

## Team Lead Responsibilities
1. Decompose the assigned workload into agent tasks.
2. Select discretionary agents; always include the mandatory ones.
3. Enforce the handoff contract — reject incomplete handoffs.
4. Do not release a deliverable until QA has signed off AND documentation exists.
5. Report status, blockers, and any cross-team dependencies up to the Orchestrator.

## Orchestrator Responsibilities
1. Split the overall job across Teams when scale or parallelism justifies it; otherwise run a single Team.
2. Maintain a **shared-interface registry**: any artifact, file, schema, API contract, naming convention, or data format touched by more than one Team. This is the conflict-prevention mechanism — not vague "coordination."
3. Before parallel work starts, define and freeze shared interfaces in the registry. Teams build against the registry, not against each other's in-progress work.
4. Detect and resolve conflicts: overlapping file edits, contradictory specs, divergent assumptions, duplicated work. When two Teams need the same shared item changed, the Orchestrator arbitrates — Teams do not negotiate it between themselves.
5. Relay artifacts between agents and enforce human approval gates — stop and surface the artifact rather than proceeding past a gate.
6. Integrate Team outputs into the final deliverable and confirm the integrated whole passes QA, not just each part in isolation.

## Conflict Rules
- Two Teams MUST NOT edit the same file in parallel. Either serialise it or assign sole ownership; for genuinely concurrent edits, isolate each agent in its own workspace and integrate afterward.
- Identify the **shared, collision-prone items** up front (config files touched by everyone, version-numbered artifacts, shared indexes/registries) and serialise changes to them.
- Any change to a registry item requires Orchestrator approval before it propagates.
- If Teams hold contradictory assumptions, the Orchestrator forces reconciliation before either ships.

## Definition of Done
A deliverable is complete only when:
- [ ] The build/artifact is produced and **actually runs/starts** — verified, not assumed (a process that launches is not proof it stayed healthy; check it holds).
- [ ] QA Engineer has signed off (defects resolved or explicitly accepted)
- [ ] Required testing (functional and/or operational) passed against stated criteria
- [ ] **High-risk paths** (money, safety, security/auth, data integrity, anything irreversible) are gated hard and verified before release
- [ ] Any human approval gates were passed against the approved baseline
- [ ] Documentation Engineer's docs are present and current
- [ ] Project-specific release rules are satisfied (e.g. all supported locales, demo/sandbox parity, downstream docs/wikis) — defined in the project's own config
- [ ] For multi-Team work: integrated output passes QA as a whole, and no registry conflicts remain open
