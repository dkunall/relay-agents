package com.relayagents.workflow.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A validated workflow: a DAG of steps plus an optional output template. Construction fails on anything
 * that would only blow up mid-run, such as a prompt reading a step that has not happened yet.
 */
public final class WorkflowDefinition {

    public static final int MAX_STEPS = 50;

    private final String name;
    private final List<StepDefinition> steps;
    private final String output;
    private final Dag dag;

    public WorkflowDefinition(String name, List<StepDefinition> steps, String output) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 100) {
            throw new WorkflowValidationException("name must be 1-100 characters");
        }
        steps = List.copyOf(steps);
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new WorkflowValidationException("A workflow needs between 1 and " + MAX_STEPS + " steps");
        }
        Dag dag = Dag.of(steps);
        for (StepDefinition step : steps) {
            checkTemplates(step.id(), step.config().templates(), dag.ancestors(step.id()));
            if (step.config() instanceof StepConfig.Branch branch) {
                checkBranch(step.id(), branch, dag);
            }
        }
        if (output != null) {
            checkTemplates("output", List.of(output), Set.copyOf(dag.topologicalOrder()));
        }
        this.name = name;
        this.steps = steps;
        this.output = output;
        this.dag = dag;
    }

    public String name() {
        return name;
    }

    public List<StepDefinition> steps() {
        return steps;
    }

    /**
     * Template for the run output, or null to use the outputs of the leaf steps.
     */
    public String output() {
        return output;
    }

    public Dag dag() {
        return dag;
    }

    private static void checkTemplates(String owner, List<String> templates, Set<String> readable) {
        for (String text : templates) {
            Template template = Template.parse(text);
            if (template.usesSecrets()) {
                throw new WorkflowValidationException("'" + owner + "' references a secret; secrets may only appear in "
                    + "HTTP tool headers so they never reach a prompt");
            }
            for (String ref : template.referencedSteps()) {
                if (!readable.contains(ref)) {
                    throw new WorkflowValidationException("'" + owner + "' reads steps." + ref
                        + " but that step is not one of its ancestors");
                }
            }
        }
    }

    private static void checkBranch(String id, StepConfig.Branch branch, Dag dag) {
        List<String> targets = new ArrayList<>(branch.defaultTargets());
        branch.cases().values().forEach(targets::addAll);
        List<String> dependents = dag.dependents(id);
        for (String target : targets) {
            if (!dependents.contains(target)) {
                throw new WorkflowValidationException("Branch '" + id + "' targets '" + target
                    + "', which does not list it in needs");
            }
        }
    }
}
