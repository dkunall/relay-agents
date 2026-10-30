package com.relayagents.workflow.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The dependency graph of a workflow. Built once with Kahn's algorithm, which also rejects cycles;
 * ties break by declaration order so the topological order is stable.
 */
public final class Dag {

    private final Map<String, StepDefinition> steps;
    private final List<String> order;
    private final Map<String, List<String>> dependents;
    private final Map<String, Set<String>> ancestors;

    private Dag(Map<String, StepDefinition> steps, List<String> order, Map<String, List<String>> dependents,
                Map<String, Set<String>> ancestors) {
        this.steps = steps;
        this.order = order;
        this.dependents = dependents;
        this.ancestors = ancestors;
    }

    public static Dag of(List<StepDefinition> definitions) {
        Map<String, StepDefinition> steps = new LinkedHashMap<>();
        for (StepDefinition step : definitions) {
            if (steps.putIfAbsent(step.id(), step) != null) {
                throw new WorkflowValidationException("Duplicate step id '" + step.id() + "'");
            }
        }
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        steps.keySet().forEach(id -> {
            dependents.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        });
        for (StepDefinition step : steps.values()) {
            for (String need : new LinkedHashSet<>(step.needs())) {
                if (need.equals(step.id())) {
                    throw new WorkflowValidationException("Step '" + step.id() + "' needs itself");
                }
                if (!steps.containsKey(need)) {
                    throw new WorkflowValidationException("Step '" + step.id() + "' needs unknown step '" + need + "'");
                }
                dependents.get(need).add(step.id());
                inDegree.merge(step.id(), 1, Integer::sum);
            }
        }

        Map<String, Integer> position = new HashMap<>();
        List<String> declared = new ArrayList<>(steps.keySet());
        for (int i = 0; i < declared.size(); i++) {
            position.put(declared.get(i), i);
        }
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator.comparingInt(position::get));
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        List<String> order = new ArrayList<>(steps.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String next : dependents.get(id)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != steps.size()) {
            List<String> stuck = declared.stream().filter(id -> !order.contains(id)).toList();
            throw new WorkflowValidationException("Workflow has a cycle through " + stuck);
        }

        Map<String, Set<String>> ancestors = new HashMap<>();
        for (String id : order) {
            Set<String> all = new LinkedHashSet<>();
            for (String need : steps.get(id).needs()) {
                all.add(need);
                all.addAll(ancestors.get(need));
            }
            ancestors.put(id, Collections.unmodifiableSet(all));
        }
        dependents.replaceAll((k, v) -> List.copyOf(v));
        return new Dag(Collections.unmodifiableMap(steps), List.copyOf(order), Collections.unmodifiableMap(dependents),
            Collections.unmodifiableMap(ancestors));
    }

    public StepDefinition step(String id) {
        StepDefinition step = steps.get(id);
        if (step == null) {
            throw new IllegalArgumentException("No step '" + id + "'");
        }
        return step;
    }

    public boolean contains(String id) {
        return steps.containsKey(id);
    }

    public List<String> topologicalOrder() {
        return order;
    }

    public List<String> dependents(String id) {
        return dependents.getOrDefault(id, List.of());
    }

    public Set<String> ancestors(String id) {
        return ancestors.getOrDefault(id, Set.of());
    }

    public List<String> roots() {
        return order.stream().filter(id -> steps.get(id).needs().isEmpty()).toList();
    }

    /**
     * Steps that no other step needs; their outputs are the default workflow output.
     */
    public List<String> leaves() {
        return order.stream().filter(id -> dependents.get(id).isEmpty()).toList();
    }

    public int size() {
        return steps.size();
    }

    /**
     * Longest chain of steps, a lower bound on sequential LLM latency.
     */
    public int depth() {
        Map<String, Integer> depth = new HashMap<>();
        int max = 0;
        for (String id : order) {
            int d = 1 + steps.get(id).needs().stream().mapToInt(depth::get).max().orElse(0);
            depth.put(id, d);
            max = Math.max(max, d);
        }
        return max;
    }
}
