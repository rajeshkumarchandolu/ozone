# Ozone tracing proofs (Jaeger screenshots)

Evidence for the tracing PRs under [HDDS-13679](https://issues.apache.org/jira/browse/HDDS-13679). This branch only
holds images and is not meant to be merged.

Every image is a Jaeger trace view of one command on a docker compose cluster (3 datanodes, `monitoring.yaml`).
`before` is `origin/master` without any tracing additions, `after` is the PR branch. Commands, versions and span counts
are in each PR description.

| Folder | PR | Jira |
|---|---|---|
| `om/` | Tracing improvements for Ozone Manager | HDDS-17586 |
| `scm/` | Tracing improvements for Storage Container Manager | HDDS-17593 |
| `datanode/` | Tracing improvements for Ozone datanode | HDDS-17594 |
| `client/` | Tracing improvements for Ozone client | HDDS-17761 |
| `freon/` | Start a new trace for each Freon ockg key | to be created |
| `combined/` | all of the above together | - |

Files per command: `<step>-before.png`, `<step>-after.png`, `<step>-side-by-side.png`.
Commands that exist only with a feature on (ACLs enabled, streaming read) have an `-after.png` only.

Step names: `sh-*` is `ozone sh ...`, `fs-*` is `ozone fs ...`, `admin-*` is `ozone admin ...`, `freon-ockg` is
`ozone freon ockg -n 3 -t 1 -s 4MB`. The key put, get and fs commands use a 4 MB key.
