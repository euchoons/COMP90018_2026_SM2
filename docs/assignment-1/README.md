# Assignment 1 workspace

This folder is the team's working area for **COMP90018 Assignment 1 — Project Plan**, due **Tuesday, 1 September 2026 at 23:59 Melbourne time**. Canvas remains the final source of truth; recheck it before submission.

## Required report content

The submitted PDF must contain:

1. the group number;
2. every member's full name, student number and email;
3. a **one-page itemised breakdown** of each student's potential contributions;
4. a section of **up to 10 pages** explaining how the group plans to meet **every Assignment 2 rubric criterion**, including why the plan satisfies each criterion;
5. an acknowledgement of AI and other software tools used, with examples of use.

Assignment 1's own rubric rewards:

| Criterion | Points | What excellent work requires |
|---|---:|---|
| Group and member identification | 10 | Complete and clearly formatted group/member information. |
| Coverage of Assignment 2 criteria | 10 | All 19 criteria are explicitly and traceably addressed. |
| Contribution breakdown | 30 | One page, specific per member, credible and balanced. |
| Justification and feasibility | 40 | Each criterion explains how, why and realistic delivery. |

The two highest-leverage tasks are therefore the contribution page and the quality of the criterion-by-criterion reasoning. A feature list alone is not sufficient.

## Files in this folder

- [`REPORT_OUTLINE.md`](REPORT_OUTLINE.md) — recommended report order and page-budget guidance.
- [`RUBRIC_PLAN.md`](RUBRIC_PLAN.md) — a working English draft covering all 19 Assignment 2 criteria.
- [`CONTRIBUTION_PLAN_TEMPLATE.md`](CONTRIBUTION_PLAN_TEMPLATE.md) — a concrete one-page contribution structure.
- [`AI_USE_LOG.md`](AI_USE_LOG.md) — a log and acknowledgement draft for AI-assisted work.

## Recommended team workflow

### 1. Agree on scope before polishing prose

The team must decide the target species group, model source, cloud backend, single showcase extension, evaluation approach and privacy rules. Record those decisions in `docs/PROJECT_STATUS_AND_ROADMAP.md`.

### 2. Assign a rubric owner and reviewer

Every one of the 19 Assignment 2 criteria should have a primary owner responsible for the plan and a second member responsible for checking feasibility. Ownership of a rubric paragraph does not replace ownership of implementation work.

### 3. Edit the rubric plan

For every criterion, preserve four distinct elements:

- **How:** the concrete feature, component, data flow or process.
- **Why:** why that work meets the wording of the criterion rather than merely mentioning it.
- **Feasibility:** existing baseline, remaining work, risks, fallback and owner.
- **Evidence plan:** what the team will later show, measure or test.

Replace provisional wording and placeholders. Remove any planned feature the group cannot credibly deliver.

### 4. Complete the one-page contribution plan

Use names rather than “Member A”. Every member should own concrete implementation, tests/evidence and documentation or demo work. Avoid descriptions such as “help with coding”, “work on report” or “assist everyone”.

### 5. Check internal consistency

The report, contribution page and roadmap must agree. For example, do not promise Firebase under Connectivity if no member owns cloud implementation, and do not claim advanced sensor use if there is no calibration or physical-device test plan.

### 6. Run a claim review

Mark each statement as one of:

- implemented in the current baseline;
- planned and supported by a feasibility spike;
- planned but still risky;
- stretch goal.

The final report should not present the deterministic classifier, local persistence or demo data as the finished AI/cloud system.

## Suggested review roles

- **Technical reviewer:** checks architecture, APIs, sensors, algorithms and feasibility.
- **Rubric reviewer:** confirms all 19 criteria are explicit and easy to trace.
- **Contribution reviewer:** checks balance, specificity and ownership.
- **Language reviewer:** removes vague claims and keeps terminology consistent.
- **Submission reviewer:** checks page limits, member details, citations and AI acknowledgement.

One person may hold more than one review role, but every final section should be reviewed by someone other than its primary author.
