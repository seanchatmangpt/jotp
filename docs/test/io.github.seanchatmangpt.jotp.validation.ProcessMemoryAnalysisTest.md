# io.github.seanchatmangpt.jotp.validation.ProcessMemoryAnalysisTest

## Table of Contents

- [100 Process Memory Analysis](#100processmemoryanalysis)
- [100K Process Memory Analysis](#100kprocessmemoryanalysis)
- [10K Process Memory Analysis](#10kprocessmemoryanalysis)
- [1K Process Memory Analysis](#1kprocessmemoryanalysis)
- [Empty Process Memory Analysis](#emptyprocessmemoryanalysis)
- [Process with Small State Memory Analysis](#processwithsmallstatememoryanalysis)
- [Process with Mailbox Messages Memory Analysis](#processwithmailboxmessagesmemoryanalysis)


## 100 Process Memory Analysis

Validating memory footprint for 100 processes



Expected: ~100-120 KB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `100` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## 100K Process Memory Analysis

Validating memory footprint for 100,000 processes



Expected: ~80-120 MB total (~1KB per process)



> [!NOTE]
> This test demonstrates virtual thread scalability at scale.



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `100,000` |



Phase 1: Creating processes...

## 10K Process Memory Analysis

Validating memory footprint for 10,000 processes



Expected: ~10-12 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `10,000` |



Phase 1: Creating processes...

  Created 10,000 processes (10.0%)

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



  Created 20,000 processes (20.0%)

  Created 30,000 processes (30.0%)

Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

  Created 40,000 processes (40.0%)

  Created 50,000 processes (50.0%)



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

  Created 60,000 processes (60.0%)

  Created 70,000 processes (70.0%)

## 1K Process Memory Analysis

Validating memory footprint for 1,000 processes



Expected: ~1-1.2 MB total (~1KB per process)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `1,000` |



Phase 1: Creating processes...



Phase 2: Forcing GC to stabilize measurement...

## Empty Process Memory Analysis

Validating memory footprint for 10,000 empty processes (no state, no messages)



Expected: ~3.5-4.5 KB total (virtual thread + mailbox overhead only)



Test Configuration:

| Key | Value |
| --- | --- |
| `JVM Max Heap` | `3.93 GB` |
| `Target Processes` | `10,000` |



Phase 1: Creating processes...

  Created 10,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...

## Process with Small State Memory Analysis

Validating memory footprint for 10,000 processes with small state objects



Expected: ~4-5 KB total (includes ~100-byte state object)



Phase 1: Creating processes with SmallState...



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

  Created 80,000 processes (80.0%)

  Created 90,000 processes (90.0%)



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

  Created 100,000 processes (100.0%)



Phase 2: Forcing GC to stabilize measurement...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Creation Time` | `1.55 sec` |
| `Heap Growth` | `51.56 MB` |
| `After Creation Heap` | `210.34 MB` |
| `State Type` | `SmallState (3 fields)` |
| `Bytes Per Process` | `5406.35 bytes` |
| `Baseline Heap` | `158.78 MB` |
| `Processes Created` | `10,000` |
| `KB Per Process` | `5.28 KB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Cleanup Time` | `4.00 sec` |
| `GC Stabilization Time` | `3.16 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `165.92 MB` |
| `KB Per Process` | `47.58 KB` |
| `Bytes Per Process` | `48718.03 bytes` |
| `Processes Created` | `1,000` |
| `Heap Growth` | `46.46 MB` |
| `Baseline Heap` | `119.46 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `119.46 MB` |
| `Final Heap` | `266.27 MB` |
| `Peak Heap` | `165.92 MB` |
| `Heap Growth` | `46.46 MB` |



Phase 3: Measuring memory footprint...



Phase 4: Cleaning up processes...

## Process with Mailbox Messages Memory Analysis

Validating memory footprint for 10,000 processes with messages in mailboxes



Expected: ~5-7 KB total (includes 10 messages per process)



Phase 1: Creating processes and populating mailboxes...



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Creation Time` | `0.75 sec` |
| `After Creation Heap` | `352.78 MB` |
| `KB Per Process` | `6.33 KB` |
| `Bytes Per Process` | `6478.40 bytes` |
| `Processes Created` | `10,000` |
| `Total Messages` | `100,000` |
| `Messages Per Process` | `10` |
| `Heap Growth` | `61.78 MB` |
| `Baseline Heap` | `291.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Cleanup Time` | `16.91 sec` |
| `GC Stabilization Time` | `2.17 sec` |
| `Creation Time` | `0.00 sec` |
| `After Creation Heap` | `113.35 MB` |
| `KB Per Process` | `-595.18 KB` |
| `Bytes Per Process` | `-609466.56 bytes` |
| `Processes Created` | `100` |
| `Heap Growth` | `-58.12 MB` |
| `Baseline Heap` | `171.47 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `171.47 MB` |
| `Final Heap` | `438.23 MB` |
| `Peak Heap` | `171.47 MB` |
| `Heap Growth` | `0.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Cleanup Time` | `244.68 sec` |
| `GC Stabilization Time` | `2.89 sec` |
| `Creation Time` | `0.03 sec` |
| `After Creation Heap` | `206.62 MB` |
| `KB Per Process` | `9.33 KB` |
| `Bytes Per Process` | `9558.89 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `91.16 MB` |
| `Baseline Heap` | `115.46 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `115.46 MB` |
| `Final Heap` | `1973.23 MB` |
| `Peak Heap` | `206.62 MB` |
| `Heap Growth` | `91.16 MB` |



Analysis:

> [!NOTE]
> Message overhead: 249.50 bytes per message



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `291.00 MB` |
| `Final Heap` | `1412.23 MB` |
| `Peak Heap` | `291.00 MB` |
| `Heap Growth` | `0.00 MB` |



Analysis:

> [!NOTE]
> State object overhead: 1.39 KB per process



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `158.78 MB` |
| `Final Heap` | `1440.23 MB` |
| `Peak Heap` | `158.78 MB` |
| `Heap Growth` | `0.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Cleanup Time` | `259.00 sec` |
| `GC Stabilization Time` | `1.90 sec` |
| `Creation Time` | `0.46 sec` |
| `After Creation Heap` | `235.46 MB` |
| `KB Per Process` | `15.36 KB` |
| `Bytes Per Process` | `15728.36 bytes` |
| `Processes Created` | `10,000` |
| `Heap Growth` | `150.00 MB` |
| `Baseline Heap` | `85.46 MB` |



Validation:



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `85.46 MB` |
| `Final Heap` | `1396.23 MB` |
| `Peak Heap` | `235.46 MB` |
| `Heap Growth` | `150.00 MB` |



Memory Footprint Results:

| Key | Value |
| --- | --- |
| `Cleanup Time` | `256.77 sec` |
| `GC Stabilization Time` | `3.58 sec` |
| `Creation Time` | `9.58 sec` |
| `After Creation Heap` | `262.69 MB` |
| `KB Per Process` | `1.73 KB` |
| `Bytes Per Process` | `1774.38 bytes` |
| `Processes Created` | `100,000` |
| `Heap Growth` | `169.22 MB` |
| `Baseline Heap` | `93.47 MB` |



Validation:

| Key | Value |
| --- | --- |
| `Actual` | `1.73 KB/process` |
| `Claim` | `~1KB per process` |
| `Note` | `Outside ideal range but within acceptable JVM variance` |
| `Status` | `PASS (with tolerance)` |



Memory Summary:

| Key | Value |
| --- | --- |
| `Baseline Heap` | `93.47 MB` |
| `Final Heap` | `1166.04 MB` |
| `Peak Heap` | `262.69 MB` |
| `Heap Growth` | `169.22 MB` |

---
*Generated by [DTR](http://www.dtr.org)*
