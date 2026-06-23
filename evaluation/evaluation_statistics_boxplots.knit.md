---
title: "Evaluation Statistics"
output:
  pdf_document:
    toc: true
    number_sections: true
    keep_tex: false
geometry: margin=1in
fontsize: 11pt
header-includes:
  - \usepackage{float}
---



# Setup

## Import and Filter Data






Table: Entries excluded by timeout, minimum-duration, and dependency filtering.

|phase   |removal_reason                                                   | removed_entries|
|:-------|:----------------------------------------------------------------|---------------:|
|Phase 5 |Elapsed time exceeded configured timeout plus grace period       |               5|
|R3      |Recovery execution below 10 seconds; treated as not run          |               2|
|R4      |Elapsed time exceeded configured timeout plus grace period       |               1|
|R5      |Dependent recovery or stability entry removed with primary phase |               3|
|R5      |Recovery execution below 10 seconds; treated as not run          |              89|
|SR      |Dependent recovery or stability entry removed with primary phase |               3|

## Theme Settings



# Success Rates


Table: Number of Valid Runs Recorded for Each Evaluation Phase

|Phase     | Number of runs|
|:---------|--------------:|
|Phase 1   |            236|
|S1        |            106|
|R1        |            130|
|Phase 2.1 |            235|
|Phase 2.2 |            234|
|Phase 3   |            233|
|S3        |            222|
|R3        |              9|
|Phase 4   |            228|
|S4        |            133|
|R4        |             94|
|Phase 5   |            221|
|S5        |            107|
|R5        |             27|
|SR        |            238|

## Final Outcome by Main Phase


\begin{center}\includegraphics{figures/final-success-rate-chart-1} \end{center}

## Direct, Recovered, Failed, and Not-Run Outcomes


\begin{center}\includegraphics{figures/detailed-outcome-chart-1} \end{center}


Table: Run counts by phase outcome, including phases that were not executed.

|phase_group | Failure (no recovery)| Failure (with recovery)| Not run| Success (no recovery)| Success (with recovery)|
|:-----------|---------------------:|-----------------------:|-------:|---------------------:|-----------------------:|
|Phase 1     |                     0|                      15|       0|                   106|                     115|
|Phase 2     |                     9|                       0|       2|                   225|                       0|
|Phase 3     |                     2|                       3|       3|                   222|                       6|
|Phase 4     |                     1|                       2|       8|                   133|                      92|
|Phase 5     |                    87|                       2|      15|                   107|                      25|

# Final Success Rate by Input Variables

## Phase 1: Initial Network Formation


\begin{center}\includegraphics{figures/phase1-input-chart-1} \end{center}

## Phase 3: Single-Node Failure Repair


\begin{center}\includegraphics{figures/phase3-input-chart-1} \end{center}

## Phase 4: Adding Nodes


\begin{center}\includegraphics{figures/phase4-input-chart-1} \end{center}

## Phase 5: Removing Multiple Nodes


\begin{center}\includegraphics{figures/phase5-input-chart-1} \end{center}

![](http://127.0.0.1:39058/chunk_output/F9C83C15a463b8af/641E88A1/cgoaeoc4gw66d/000010.png)

## Combined Input-Variable Comparison


\begin{center}\includegraphics{figures/combined-input-chart-1} \end{center}

# Stability Checks


\begin{center}\includegraphics{figures/stability-success-chart-1} \end{center}

# Failure Detection Time


\begin{center}\includegraphics{figures/phase2-time-chart-1} \end{center}

# Main-Phase Completion Time and Input Effects

## Phase 1: Initial Network Formation


\begin{center}\includegraphics{figures/phase1-time-impact-chart-1} \end{center}

## Phase 3: Repair After One Node Failure


\begin{center}\includegraphics{figures/phase3-time-impact-chart-1} \end{center}

## Phase 4: Adding Nodes


\begin{center}\includegraphics{figures/phase4-time-impact-chart-1} \end{center}

## Phase 5: Repair After Multiple Node Failures


\begin{center}\includegraphics{figures/phase5-time-impact-chart-1} \end{center}

# Replacement-Based Recovery Completion Time


\begin{center}\includegraphics{figures/recovery-time-impact-chart-1} \end{center}

# Problematic Nodes in Failed Main Phases

## Phase 1: Initial Network Formation


\begin{center}\includegraphics{figures/phase1-problem-count-chart-1} \end{center}

## Phase 3: Repair After One Node Failure


\begin{center}\includegraphics{figures/phase3-problem-count-chart-1} \end{center}

## Phase 4: Adding Nodes


\begin{center}\includegraphics{figures/phase4-problem-count-chart-1} \end{center}

## Phase 5: Repair After Multiple Node Failures


\begin{center}\includegraphics{figures/phase5-problem-count-chart-1} \end{center}

# Replacement-Based Recovery Success Rates


\begin{center}\includegraphics{figures/recovery-success-chart-1} \end{center}


Table: Valid replacement-based recovery attempts after timeout filtering.

|phase | attempts| passes| timeouts| failures| success_rate|
|:-----|--------:|------:|--------:|--------:|------------:|
|R1    |      130|    115|       15|        0|        0.885|
|R3    |        9|      6|        3|        0|        0.667|
|R4    |       94|     92|        2|        0|        0.979|
|R5    |       27|     25|        2|        0|        0.926|
