package com.relayagents.tool.adapter.builtin;

import com.relayagents.shared.json.Json;
import com.relayagents.tool.application.Tool;
import com.relayagents.tool.application.ToolDescriptor;
import com.relayagents.tool.application.ToolException;
import com.relayagents.tool.application.ToolInvocation;
import com.relayagents.tool.application.ToolResult;
import com.relayagents.tool.domain.Arithmetic;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class CalculatorTool implements Tool {

    private final ToolDescriptor descriptor;
    private final Json json;

    CalculatorTool(Json json) {
        this.json = json;
        this.descriptor = new ToolDescriptor("calculator", "Evaluates an arithmetic expression with + - * / % and parentheses.",
            json.read("""
                {"type": "object", "additionalProperties": false, "required": ["expression"],
                 "properties": {"expression": {"type": "string", "minLength": 1, "maxLength": 200}}}
                """), Set.of(), false, false, "builtin");
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        try {
            String value = Arithmetic.evaluate(invocation.arguments().path("expression").asText()).toPlainString();
            return ToolResult.of(json.object().put("value", value));
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new ToolException(e.getMessage(), false);
        }
    }
}
