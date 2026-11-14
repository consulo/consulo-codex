/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.codex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import consulo.ai.AIContent;
import consulo.ai.AIPlanEntry;
import consulo.ai.AIPlanEntryPriority;
import consulo.ai.AIPlanEntryStatus;
import consulo.ai.AIToolCallContent;
import consulo.ai.AIToolCallStatus;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The items of a Codex turn - the commands it ran, the files it changed, the tools it called - as tool calls of the
 * chat. {@code item/started} and {@code item/completed} tell the whole item, the tool call is made anew each time.
 *
 * @author VISTALL
 * @since 2026-11-14
 */
final class CodexItems {
    private static final int MAX_OUTPUT_LENGTH = 8000;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private CodexItems() {
    }

    /**
     * Null for the items which are no tool - the messages, the reasoning, the plan.
     *
     * @param output what the command printed so far, while it runs
     */
    static AIContent.@Nullable ToolCall toolCall(JsonObject item, @Nullable String output) {
        String id = string(item, "id");
        return switch (string(item, "type")) {
            case "commandExecution" -> command(id, item, output);
            case "fileChange" -> fileChange(id, item);
            case "mcpToolCall" -> new AIContent.ToolCall(id, null,
                "Tool: " + string(item, "server") + "/" + string(item, "tool"), "other", status(item),
                List.of(), mcpContent(item), json(item.get("arguments")), "");
            case "dynamicToolCall" -> new AIContent.ToolCall(id, null,
                "Tool: " + string(item, "tool"), "other", status(item), List.of(), List.of(), json(item.get("arguments")), "");
            case "webSearch" -> new AIContent.ToolCall(id, null,
                string(item, "query").isEmpty() ? "Search the web" : "Search the web for " + string(item, "query"), "fetch",
                item.has("results") && !item.get("results").isJsonNull() ? AIToolCallStatus.COMPLETED : AIToolCallStatus.IN_PROGRESS,
                List.of(), List.of(), item.toString(), "");
            case "imageView" -> new AIContent.ToolCall(id, null,
                "View image " + Path.of(string(item, "path")).getFileName(), "read", AIToolCallStatus.COMPLETED,
                List.of(string(item, "path")), image(string(item, "path")), item.toString(), "");
            case "imageGeneration" -> new AIContent.ToolCall(id, null,
                "Generate an image", "other", status(item), List.of(), image(string(item, "savedPath")), item.toString(), "");
            case "collabAgentToolCall" -> new AIContent.ToolCall(id, null,
                firstLine(string(item, "prompt")).isEmpty() ? "Agent" : firstLine(string(item, "prompt")), AIContent.ToolCall.KIND_TASK,
                status(item), List.of(), List.of(), item.toString(), "");
            default -> null;
        };
    }

    /**
     * The plan of {@code turn/plan/updated} - the whole of it every time.
     */
    static List<AIPlanEntry> plan(JsonObject params) {
        List<AIPlanEntry> entries = new ArrayList<>();
        JsonElement plan = params.get("plan");
        if (plan == null || !plan.isJsonArray()) {
            return entries;
        }
        for (JsonElement element : plan.getAsJsonArray()) {
            if (element.isJsonObject()) {
                JsonObject step = element.getAsJsonObject();
                AIPlanEntryStatus status = switch (string(step, "status")) {
                    case "inProgress" -> AIPlanEntryStatus.IN_PROGRESS;
                    case "completed" -> AIPlanEntryStatus.COMPLETED;
                    default -> AIPlanEntryStatus.PENDING;
                };
                entries.add(new AIPlanEntry(string(step, "step"), AIPlanEntryPriority.MEDIUM, status));
            }
        }
        return entries;
    }

    private static AIContent.ToolCall command(String id, JsonObject item, @Nullable String output) {
        String title = firstLine(string(item, "command"));
        String kind = "execute";
        List<String> locations = new ArrayList<>();
        JsonElement actions = item.get("commandActions");
        if (actions != null && actions.isJsonArray() && actions.getAsJsonArray().size() == 1 && actions.getAsJsonArray().get(0).isJsonObject()) {
            JsonObject action = actions.getAsJsonArray().get(0).getAsJsonObject();
            String path = string(action, "path");
            switch (string(action, "type")) {
                case "read" -> {
                    title = "Read " + (string(action, "name").isEmpty() ? path : string(action, "name"));
                    kind = "read";
                    if (!path.isEmpty()) {
                        locations.add(path);
                    }
                }
                case "listFiles" -> {
                    title = "List " + (path.isEmpty() ? "files" : path);
                    kind = "search";
                }
                case "search" -> {
                    title = "Search " + string(action, "query") + (path.isEmpty() ? "" : " in " + path);
                    kind = "search";
                }
                default -> title = "`" + firstLine(string(action, "command")) + "`";
            }
        }
        String printed = output != null ? output : string(item, "aggregatedOutput");
        JsonObject result = new JsonObject();
        if (!printed.isEmpty()) {
            result.addProperty("stdout", truncate(printed));
        }
        JsonElement exitCode = item.get("exitCode");
        if (exitCode != null && exitCode.isJsonPrimitive()) {
            result.addProperty("exitCode", exitCode.getAsInt());
        }
        JsonObject input = new JsonObject();
        input.addProperty("command", string(item, "command"));
        input.addProperty("cwd", string(item, "cwd"));
        return new AIContent.ToolCall(id, null, title, kind, status(item), locations, List.of(), input.toString(),
            result.isEmpty() ? "" : result.toString());
    }

    /**
     * A change of files - each file as a diff of the hunks Codex applied: the context lines and the removed ones make
     * the text before, the context lines and the added ones the text after.
     */
    private static AIContent.ToolCall fileChange(String id, JsonObject item) {
        List<AIToolCallContent> content = new ArrayList<>();
        List<String> locations = new ArrayList<>();
        JsonElement changes = item.get("changes");
        if (changes != null && changes.isJsonArray()) {
            for (JsonElement element : changes.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject change = element.getAsJsonObject();
                JsonObject kind = object(change, "kind");
                String path = string(kind, "move_path").isEmpty() ? string(change, "path") : string(kind, "move_path");
                locations.add(path);
                String diff = string(change, "diff");
                switch (string(kind, "type")) {
                    case "add" -> content.add(new AIToolCallContent.Diff(path, null, added(diff)));
                    case "delete" -> content.add(new AIToolCallContent.Diff(path, diff, ""));
                    default -> {
                        StringBuilder before = new StringBuilder();
                        StringBuilder after = new StringBuilder();
                        for (String line : diff.split("\\n", -1)) {
                            if (line.startsWith("@@") || line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) {
                                continue;
                            }
                            if (line.startsWith("-")) {
                                before.append(line, 1, line.length()).append('\n');
                            }
                            else if (line.startsWith("+")) {
                                after.append(line, 1, line.length()).append('\n');
                            }
                            else if (line.startsWith(" ")) {
                                before.append(line, 1, line.length()).append('\n');
                                after.append(line, 1, line.length()).append('\n');
                            }
                        }
                        content.add(new AIToolCallContent.Diff(path, before.toString(), after.toString()));
                    }
                }
            }
        }
        String title = "Edit " + String.join(", ", locations.stream().map(path -> {
            Path name = Path.of(path).getFileName();
            return name == null ? path : name.toString();
        }).toList());
        return new AIContent.ToolCall(id, null, title, "edit", status(item), locations, content, json(changes), "");
    }

    /**
     * The text of an added file - Codex tells it as a diff of added lines, or as the text itself.
     */
    private static String added(String diff) {
        if (!diff.startsWith("@@") && !diff.startsWith("+")) {
            return diff;
        }
        StringBuilder text = new StringBuilder();
        for (String line : diff.split("\\n", -1)) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                text.append(line, 1, line.length()).append('\n');
            }
        }
        return text.toString();
    }

    private static List<AIToolCallContent> mcpContent(JsonObject item) {
        List<AIToolCallContent> content = new ArrayList<>();
        JsonObject result = object(item, "result");
        JsonElement blocks = result.get("content");
        if (blocks != null && blocks.isJsonArray()) {
            for (JsonElement block : blocks.getAsJsonArray()) {
                if (block.isJsonObject() && "text".equals(string(block.getAsJsonObject(), "type"))) {
                    content.add(new AIToolCallContent.Text(truncate(string(block.getAsJsonObject(), "text"))));
                }
            }
        }
        JsonObject error = object(item, "error");
        if (!string(error, "message").isEmpty()) {
            content.add(new AIToolCallContent.Text(string(error, "message")));
        }
        return content;
    }

    private static List<AIToolCallContent> image(String path) {
        if (path.isEmpty()) {
            return List.of();
        }
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_IMAGE_BYTES) {
                return List.of();
            }
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            String mediaType = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg" : name.endsWith(".gif") ? "image/gif"
                : name.endsWith(".webp") ? "image/webp" : "image/png";
            return List.of(new AIToolCallContent.Image(mediaType, Files.readAllBytes(file)));
        }
        catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static AIToolCallStatus status(JsonObject item) {
        return switch (string(item, "status")) {
            case "completed" -> AIToolCallStatus.COMPLETED;
            case "failed", "declined" -> AIToolCallStatus.FAILED;
            case "" -> AIToolCallStatus.COMPLETED;
            default -> AIToolCallStatus.IN_PROGRESS;
        };
    }

    private static String json(@Nullable JsonElement element) {
        return element == null || element.isJsonNull() ? "{}" : element.toString();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_OUTPUT_LENGTH ? text : text.substring(text.length() - MAX_OUTPUT_LENGTH);
    }

    private static String firstLine(String text) {
        String strip = text.strip();
        int newLine = strip.indexOf('\n');
        return newLine < 0 ? strip : strip.substring(0, newLine) + " \u2026";
    }

    static JsonObject object(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }
}
