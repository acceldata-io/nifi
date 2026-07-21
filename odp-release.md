---
type: Cherry-pick Log
title: NiFi2 Ported Commits
description: Commits ported from rel/3.3.6.4-1, manually reviewed ODP changes except for CVE/OSV or general version bump-ups.
resource: https://docs.google.com/spreadsheets/d/19auGdoHyq0gDv_XZA6-uq-0ujYVHRBBagEjdPOpcVFk/edit?gid=1429944655#gid=1429944655
tags: [nifi2, cherry-pick]
timestamp: 2026-07-17T00:00:00Z
---

| hash       | date       | author          | message                                                                                    |
| ---------- | ---------- | --------------- | ------------------------------------------------------------------------------------------ |
| 7958d0e315 | 2026-04-09 | Prabhjyot Singh | ODP-6359: Enable CORS params, and enable Database fix (#62)                                |
| cc659c5997 | 2026-04-09 | Prabhjyot Singh | ODP-6357: Nifi Registry does not start due to logback jar packaged in war file (#60)       |
| 3f59e4d172 | 2026-03-27 | Shubham Sharma  | OCR-2401 Reverting DriveService scope changes                                              |
| 174161645  | 2026-03-22 | Shubham Sharma  | OCR-2401 NIFI-15734 Fix GCP PubSub/BigQuery scope for Workload Identity with Impersonation |
| 814e05410f | 2026-04-01 | Prabhjyot Singh | NIFI-8843: ZooKeeper leader election for NiFi Registry HA (#47)                            |
| 047720ed84 | 2026-03-16 | araika          | ODP-6078 Point hadoop and ozone to 3.3.6.4-1                                               |
| f7c3872e13 | 2026-02-19 | araika          | ODP-6078 Prepare for 3.3.6.4-1 release                                                     |