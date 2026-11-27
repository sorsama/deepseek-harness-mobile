# iOS M7 (#141): Android before/after

Before: origin/main 8f0c3a08. After: ios/m7-approvals-questions 73d8a054. DSH_Sheet37, 1080x2340, demo-mode status bar, animations off,
the demo harness of ios/m7-ios-requests for both. Every differing pixel is either the status bar's signal icon (x 880-913, y 54-86)
or the composer caret's blink box (x 121-125, or 954-959 right to left; y 1983-2029). diff/ holds a diff image for every pair
that differs anywhere, the status bar included.

| State | Status bar px | Caret px | Elsewhere |
|---|---:|---:|---|
| 3btn--a1-approval-pending | 161 | 0 | 0 |
| 3btn--b1-error-banner | 216 | 0 | 0 |
| 3btn--q1-question-open | 258 | 0 | 0 |
| ar--a1-approval-pending | 377 | 0 | 0 |
| ar--a2-approved | 474 | 0 | 0 |
| ar--a3-command-pending | 258 | 0 | 0 |
| ar--a4-denied | 258 | 0 | 0 |
| ar--b1-error-banner | 474 | 0 | 0 |
| ar--p1-plan-review | 635 | 0 | 0 |
| ar--p2-plan-approved | 635 | 240 | 0 |
| ar--q1-question-open | 377 | 0 | 0 |
| ar--q2-question-picked | 377 | 0 | 0 |
| ar--q3-answered | 161 | 240 | 0 |
| ar--q4-multi-picked | 258 | 268 | 0 |
| ar--q5-batch-answered | 216 | 240 | 0 |
| dark--a1-approval-pending | 635 | 0 | 0 |
| dark--a2-approved | 635 | 209 | 0 |
| dark--a3-command-pending | 258 | 0 | 0 |
| dark--a4-denied | 0 | 209 | 0 |
| dark--b1-error-banner | 0 | 0 | 0 |
| dark--p1-plan-review | 0 | 0 | 0 |
| dark--p2-plan-approved | 0 | 209 | 0 |
| dark--q1-question-open | 0 | 0 | 0 |
| dark--q2-question-picked | 0 | 223 | 0 |
| dark--q3-answered | 0 | 209 | 0 |
| dark--q4-multi-picked | 258 | 223 | 0 |
| dark--q5-batch-answered | 0 | 0 | 0 |
| light--a1-approval-pending | 161 | 0 | 0 |
| light--a2-approved | 0 | 0 | 0 |
| light--a3-command-pending | 0 | 0 | 0 |
| light--a4-denied | 0 | 0 | 0 |
| light--b1-error-banner | 0 | 0 | 0 |
| light--p1-plan-review | 0 | 0 | 0 |
| light--p2-plan-approved | 0 | 209 | 0 |
| light--q1-question-open | 0 | 0 | 0 |
| light--q2-question-picked | 0 | 0 | 0 |
| light--q3-answered | 0 | 0 | 0 |
| light--q4-multi-picked | 0 | 223 | 0 |
| light--q5-batch-answered | 258 | 209 | 0 |

39 states. Identical whole frame: 12. Identical below the status bar: 26. Identical below the status bar apart from the caret's blink: 39. Different elsewhere: 0.
