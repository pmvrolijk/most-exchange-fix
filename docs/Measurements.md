# Measurements

Every figure that reaches a document has a row here, with what makes two numbers comparable: the
machine, its state, the build, the load and the rate (`perf-claim` skill). **None yet.**

Quote **both** latencies:
- **service time**, measured from the actual send;
- **response time**, measured from the scheduled send.

Quoting only the first hides the queueing at exactly the rates worth measuring.

| id | date | commit | machine | idle? | JVM/native | exchange config (placement, egress, driver threading) | offered rate | achieved | service p50/p99 | response p50/p99 | notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
