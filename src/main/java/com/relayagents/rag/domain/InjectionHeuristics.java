package com.relayagents.rag.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flags text that looks like it is trying to instruct a model. This is a tripwire for review and metrics,
 * not a defence: phrasing is endless. The defence is that retrieved text is always treated as untrusted
 * (see ToolPolicy).
 */
public final class InjectionHeuristics {

    public static final String FLAG = "possible-injection";

    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("\\b(ignore|disregard|forget)\\b.{0,30}\\b(previous|prior|above|earlier|all)\\b.{0,20}\\b(instructions?|rules|prompts?)\\b"),
        Pattern.compile("\\byou are now\\b"),
        Pattern.compile("\\b(reveal|print|show|leak)\\b.{0,40}\\b(system prompt|secret|password|api key|token)s?\\b"),
        Pattern.compile("</?\\s*(system|untrusted|assistant)\\s*>"),
        Pattern.compile("\\b(call|use|invoke)\\b.{0,20}\\btool\\b.{0,40}\\b(refund|transfer|delete)\\b"));

    private InjectionHeuristics() {
    }

    public static Set<String> flags(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> flags = new LinkedHashSet<>();
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(lower).find()) {
                flags.add(FLAG);
                break;
            }
        }
        return flags;
    }
}
