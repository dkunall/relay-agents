package com.relayagents.agent.application;

import com.relayagents.llm.application.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the system prompt, the original task and the most recent messages. A tool result is never kept
 * without the assistant message that asked for it, because providers reject orphaned tool results.
 */
public final class MemoryWindow {

    private MemoryWindow() {
    }

    public static List<ChatMessage> trim(List<ChatMessage> messages, int window) {
        if (messages.size() <= window + 2) {
            return messages;
        }
        int head = 0;
        while (head < messages.size() && head < 2 && messages.get(head).role() != ChatMessage.Role.ASSISTANT
            && messages.get(head).role() != ChatMessage.Role.TOOL) {
            head++;
        }
        int start = Math.max(head, messages.size() - window);
        while (start < messages.size() && messages.get(start).role() == ChatMessage.Role.TOOL) {
            start++;
        }
        List<ChatMessage> kept = new ArrayList<>(messages.subList(0, head));
        kept.addAll(messages.subList(start, messages.size()));
        return kept;
    }
}
