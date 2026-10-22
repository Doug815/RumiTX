package com.rumitx.app.agent;

/** One JSON tool call from the on-device model or POST /tool. Unknown tools do not run. */
public final class ToolCall {
    public final String tool;
    public final Json.Value args;
    public final String error;

    private ToolCall(String tool, Json.Value args, String error) {
        this.tool = tool;
        this.args = args;
        this.error = error;
    }

    public static ToolCall parse(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null;
        Json.Value root;
        try { root = Json.parse(trimmed); }
        catch (RuntimeException e) { return null; }
        if (!root.isObject() || !root.has("tool")) return null;
        if (Json.hasSecret(root)) return new ToolCall(null, null, "encryption fields are not accepted");
        Json.Value toolValue = root.get("tool");
        if (toolValue == null || toolValue.type != Json.Type.STRING || toolValue.string.trim().isEmpty()) {
            return new ToolCall(null, null, "tool name is missing");
        }
        Json.Value args = root.obj("args");
        if (root.has("args") && args == null) return new ToolCall(null, null, "args must be an object");
        if (args == null) args = Json.parse("{}");
        return new ToolCall(toolValue.string.trim(), args, null);
    }
}
