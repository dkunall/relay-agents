package com.relayagents.run.domain;

import com.relayagents.workflow.domain.Dag;
import com.relayagents.workflow.domain.StepType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which pending steps can start. A step starts once every need has settled and at least one need
 * actually feeds it: it succeeded, and if it is a branch, it chose this step. A step none of whose needs
 * feed it is skipped, and the skip cascades. This is OR-join semantics, which lets a step after a branch
 * run whichever arm was taken.
 */
public final class Scheduler {

    private Scheduler() {
    }

    public static Map<String, StepStatus> advance(Dag dag, Map<String, StepStatus> statuses,
                                                  Map<String, List<String>> branchChoices) {
        Map<String, StepStatus> current = new HashMap<>(statuses);
        Map<String, StepStatus> changes = new LinkedHashMap<>();
        for (String id : dag.topologicalOrder()) {
            if (current.get(id) != StepStatus.PENDING) {
                continue;
            }
            List<String> needs = dag.step(id).needs();
            if (needs.isEmpty()) {
                changes.put(id, StepStatus.READY);
                current.put(id, StepStatus.READY);
                continue;
            }
            if (!needs.stream().allMatch(n -> current.get(n).isSettled())) {
                continue;
            }
            boolean fed = needs.stream().anyMatch(n -> feeds(dag, n, id, current, branchChoices));
            StepStatus next = fed ? StepStatus.READY : StepStatus.SKIPPED;
            changes.put(id, next);
            current.put(id, next);
        }
        return changes;
    }

    public static boolean allSettled(Map<String, StepStatus> statuses) {
        return statuses.values().stream().allMatch(StepStatus::isSettled);
    }

    private static boolean feeds(Dag dag, String need, String step, Map<String, StepStatus> statuses,
                                 Map<String, List<String>> branchChoices) {
        if (statuses.get(need) != StepStatus.SUCCEEDED) {
            return false;
        }
        if (dag.step(need).type() != StepType.BRANCH) {
            return true;
        }
        return branchChoices.getOrDefault(need, List.of()).contains(step);
    }
}
