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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import consulo.ai.AIChatInfo;
import consulo.ai.AIContent;
import consulo.ai.AIMessage;
import consulo.ai.AIQuestion;
import consulo.ai.AIQuestionOption;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStopReason;
import consulo.ai.AIStreamListener;
import consulo.ai.AIToolPermissionDecision;
import consulo.ai.AIToolPermissionRequest;
import consulo.ai.AIUsage;
import consulo.ai.acp.AcpConnection;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.process.cmd.GeneralCommandLine;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@code codex app-server} process - JSON-RPC over its standard input and output - and the threads loaded in it.
 * A thread is a chat; the turns of the threads are answered here, with the approvals and questions of a turn asked
 * through the listener of that turn.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
final class CodexAppServer {
    /**
     * How many threads of a directory the chat tree shows.
     */
    private static final int MAX_THREADS = 200;

    /**
     * The account the app server is signed in with, as {@code account/read} answers it.
     */
    record Account(boolean signedIn, String type, @Nullable String email, @Nullable String plan) {
    }

    /**
     * A model the account can use, as {@code model/list} answers it.
     */
    record Model(String id, String displayName, boolean isDefault) {
    }

    private static final class Turn {
        final @Nullable AIStreamListener myListener;
        final CompletableFuture<AIResponse> myResult = new CompletableFuture<>();
        final StringBuilder myText = new StringBuilder();
        final List<AIContent> myToolUses = new ArrayList<>();
        volatile @Nullable String myTurnId;

        Turn(@Nullable AIStreamListener listener) {
            myListener = listener;
        }
    }

    private final AcpConnection myConnection;
    private final Set<String> myLoaded = ConcurrentHashMap.newKeySet();
    /**
     * The threads this server started which had no turn yet - Codex keeps no history of them, and can not read it.
     */
    private final Set<String> myEmpty = ConcurrentHashMap.newKeySet();
    private final Map<String, Turn> myTurns = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> myLogins = new ConcurrentHashMap<>();
    private final Map<String, String> myTitles = new ConcurrentHashMap<>();

    private CodexAppServer(AcpConnection connection) {
        myConnection = connection;

        connection.onNotification("item/agentMessage/delta", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            String delta = string(params, "delta");
            if (turn != null && !delta.isEmpty()) {
                synchronized (turn) {
                    turn.myText.append(delta);
                }
                if (turn.myListener != null) {
                    turn.myListener.onTextDelta(delta);
                }
            }
        });
        connection.onNotification("item/reasoning/summaryTextDelta", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            if (turn != null && turn.myListener != null) {
                turn.myListener.onThinkingDelta(string(params, "delta"));
            }
        });
        connection.onNotification("item/started", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            JsonObject item = object(params, "item");
            AIContent.ToolUse toolUse = item == null ? null : toolUse(item);
            if (turn != null && toolUse != null) {
                synchronized (turn) {
                    turn.myToolUses.add(toolUse);
                }
                if (turn.myListener != null) {
                    turn.myListener.onToolUse(toolUse);
                }
            }
        });
        connection.onNotification("turn/completed", params -> onTurnCompleted(string(params, "threadId"), object(params, "turn")));
        connection.onNotification("thread/name/updated", params -> {
            String threadId = string(params, "threadId");
            String name = string(params, "threadName");
            if (name.isBlank()) {
                return;
            }
            myTitles.put(threadId, name);
            Turn turn = myTurns.get(threadId);
            if (turn != null && turn.myListener != null) {
                turn.myListener.onTitle(name);
            }
        });
        connection.onNotification("account/login/completed", params -> {
            CompletableFuture<Boolean> login = myLogins.remove(string(params, "loginId"));
            if (login == null) {
                return;
            }
            if (bool(params, "success")) {
                login.complete(true);
            }
            else {
                String error = string(params, "error");
                login.completeExceptionally(new IOException(error.isEmpty() ? "The sign-in did not finish" : error));
            }
        });

        connection.onRequest("item/commandExecution/requestApproval", params -> approve(params, "shell", string(params, "command")));
        connection.onRequest("item/fileChange/requestApproval", params -> approve(params, "apply_patch", ""));
        connection.onRequest("item/tool/requestUserInput", this::askUser);
    }

    static CompletableFuture<CodexAppServer> start(ProcessHandlerBuilderFactory factory, GeneralCommandLine commandLine) {
        AcpConnection connection;
        try {
            connection = AcpConnection.start(factory, commandLine);
        }
        catch (ExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
        CodexAppServer server = new CodexAppServer(connection);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "consulo");
        clientInfo.addProperty("title", "Consulo");
        clientInfo.addProperty("version", "3");
        JsonObject params = new JsonObject();
        params.add("clientInfo", clientInfo);
        return connection.request("initialize", params).thenApply(result -> {
            connection.notify("initialized", new JsonObject());
            return server;
        }).whenComplete((value, error) -> {
            if (error != null) {
                connection.close();
            }
        });
    }

    boolean isAlive() {
        return myConnection.isAlive();
    }

    void close() {
        myConnection.close();
    }

    // region account

    CompletableFuture<Account> readAccount() {
        return myConnection.request("account/read", new JsonObject()).thenApply(result -> {
            JsonObject account = result.isJsonObject() ? object(result.getAsJsonObject(), "account") : null;
            if (account == null) {
                return new Account(false, "", null, null);
            }
            return new Account(true, string(account, "type"), nonEmpty(string(account, "email")), nonEmpty(string(account, "planType")));
        });
    }

    /**
     * Starts the sign-in with ChatGPT: the future of the url to open in the browser, and the future of the sign-in
     * itself, done once the browser finished it.
     */
    CompletableFuture<Map.Entry<String, CompletableFuture<Boolean>>> loginWithChatGpt() {
        JsonObject params = new JsonObject();
        params.addProperty("type", "chatgpt");
        return myConnection.request("account/login/start", params).thenApply(result -> {
            JsonObject login = result.getAsJsonObject();
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            myLogins.put(string(login, "loginId"), done);
            return Map.entry(string(login, "authUrl"), done);
        });
    }

    CompletableFuture<?> loginWithApiKey(String apiKey) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "apiKey");
        params.addProperty("apiKey", apiKey);
        return myConnection.request("account/login/start", params);
    }

    CompletableFuture<?> logout() {
        return myConnection.request("account/logout", new JsonObject());
    }

    CompletableFuture<List<Model>> listModels() {
        return myConnection.request("model/list", new JsonObject()).thenApply(result -> {
            List<Model> models = new ArrayList<>();
            JsonElement data = result.isJsonObject() ? result.getAsJsonObject().get("data") : null;
            if (data != null && data.isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray()) {
                    if (element.isJsonObject() && !bool(element.getAsJsonObject(), "hidden")) {
                        JsonObject model = element.getAsJsonObject();
                        String id = string(model, "model");
                        String name = string(model, "displayName");
                        models.add(new Model(id.isEmpty() ? string(model, "id") : id, name.isEmpty() ? id : name, bool(model, "isDefault")));
                    }
                }
            }
            return models;
        });
    }

    // endregion

    // region threads

    CompletableFuture<String> startThread(Path workingDirectory, @Nullable String model, CodexPermissionMode mode) {
        JsonObject params = threadSettings(workingDirectory, model, mode);
        return myConnection.request("thread/start", params).thenApply(result -> {
            JsonObject thread = result.isJsonObject() ? object(result.getAsJsonObject(), "thread") : null;
            String id = thread == null ? "" : string(thread, "id");
            if (id.isEmpty()) {
                throw new IllegalStateException("Codex started a thread without an id");
            }
            myLoaded.add(id);
            myEmpty.add(id);
            return id;
        });
    }

    /**
     * Loads the thread into the app server unless it is loaded already - a turn needs it loaded.
     */
    CompletableFuture<?> resumeThread(String threadId, Path workingDirectory, @Nullable String model, CodexPermissionMode mode) {
        if (myLoaded.contains(threadId)) {
            return CompletableFuture.completedFuture(null);
        }
        JsonObject params = threadSettings(workingDirectory, model, mode);
        params.addProperty("threadId", threadId);
        params.addProperty("excludeTurns", true);
        return myConnection.request("thread/resume", params).thenRun(() -> myLoaded.add(threadId));
    }

    void unloadThread(String threadId) {
        if (myLoaded.remove(threadId)) {
            JsonObject params = new JsonObject();
            params.addProperty("threadId", threadId);
            myConnection.request("thread/unsubscribe", params);
        }
    }

    CompletableFuture<List<AIChatInfo>> listThreads(Path workingDirectory) {
        List<AIChatInfo> threads = new ArrayList<>();
        return listPage(workingDirectory, null, threads).thenApply(ignored -> threads);
    }

    private CompletableFuture<?> listPage(Path workingDirectory, @Nullable String cursor, List<AIChatInfo> threads) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toString());
        params.addProperty("limit", 50);
        if (cursor != null) {
            params.addProperty("cursor", cursor);
        }
        return myConnection.request("thread/list", params).thenCompose(result -> {
            JsonObject page = result.isJsonObject() ? result.getAsJsonObject() : new JsonObject();
            JsonElement data = page.get("data");
            if (data != null && data.isJsonArray()) {
                for (JsonElement element : data.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        threads.add(info(element.getAsJsonObject()));
                    }
                }
            }
            String next = string(page, "nextCursor");
            if (next.isEmpty() || threads.size() >= MAX_THREADS) {
                return CompletableFuture.completedFuture(null);
            }
            return listPage(workingDirectory, next, threads);
        });
    }

    CompletableFuture<@Nullable AIChatInfo> readThreadInfo(String threadId) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("includeTurns", false);
        return myConnection.request("thread/read", params).thenApply(result -> {
            JsonObject thread = result.isJsonObject() ? object(result.getAsJsonObject(), "thread") : null;
            return thread == null ? null : info(thread);
        });
    }

    /**
     * The history of the thread - what the user said, what Codex answered and the commands and changes it made.
     */
    CompletableFuture<List<AIMessage>> readThread(String threadId) {
        if (myEmpty.contains(threadId)) {
            return CompletableFuture.completedFuture(List.of());
        }
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("includeTurns", true);
        return myConnection.request("thread/read", params).thenApply(result -> {
            List<AIMessage> messages = new ArrayList<>();
            JsonObject thread = result.isJsonObject() ? object(result.getAsJsonObject(), "thread") : null;
            JsonElement turns = thread == null ? null : thread.get("turns");
            if (turns == null || !turns.isJsonArray()) {
                return messages;
            }
            for (JsonElement turn : turns.getAsJsonArray()) {
                JsonElement items = turn.isJsonObject() ? turn.getAsJsonObject().get("items") : null;
                if (items == null || !items.isJsonArray()) {
                    continue;
                }
                for (JsonElement element : items.getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        addItem(messages, element.getAsJsonObject());
                    }
                }
            }
            return messages;
        });
    }

    CompletableFuture<?> renameThread(String threadId, String name) {
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.addProperty("name", name);
        return myConnection.request("thread/name/set", params);
    }

    /**
     * Archives the thread rather than deleting it - {@code codex unarchive} brings it back.
     */
    CompletableFuture<?> archiveThread(String threadId) {
        myLoaded.remove(threadId);
        myEmpty.remove(threadId);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        return myConnection.request("thread/archive", params);
    }

    // endregion

    // region turns

    /**
     * Sends the text as a turn of the thread and completes once Codex finished the turn. Cancelling the future
     * interrupts the turn.
     */
    CompletableFuture<AIResponse> startTurn(String threadId, String text, @Nullable String model, @Nullable AIStreamListener listener) {
        Turn turn = new Turn(listener);
        if (myTurns.putIfAbsent(threadId, turn) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The chat is still answering"));
        }

        JsonObject input = new JsonObject();
        input.addProperty("type", "text");
        input.addProperty("text", text);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        myEmpty.remove(threadId);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", inputs);
        if (model != null) {
            params.addProperty("model", model);
        }

        myConnection.request("turn/start", params).whenComplete((result, error) -> {
            if (error != null) {
                myTurns.remove(threadId, turn);
                turn.myResult.completeExceptionally(error);
                return;
            }
            JsonObject started = result.isJsonObject() ? object(result.getAsJsonObject(), "turn") : null;
            turn.myTurnId = started == null ? null : string(started, "id");
        });

        turn.myResult.whenComplete((response, error) -> {
            if (turn.myResult.isCancelled()) {
                myTurns.remove(threadId, turn);
                String turnId = turn.myTurnId;
                if (turnId != null) {
                    JsonObject interrupt = new JsonObject();
                    interrupt.addProperty("threadId", threadId);
                    interrupt.addProperty("turnId", turnId);
                    myConnection.request("turn/interrupt", interrupt);
                }
            }
        });
        return turn.myResult;
    }

    /**
     * The title Codex generated for the thread while it answered, if it did.
     */
    @Nullable String getGeneratedTitle(String threadId) {
        return myTitles.get(threadId);
    }

    private void onTurnCompleted(String threadId, @Nullable JsonObject completed) {
        Turn turn = myTurns.remove(threadId);
        if (turn == null) {
            return;
        }
        String status = completed == null ? "" : string(completed, "status");
        if ("failed".equals(status)) {
            JsonObject error = completed == null ? null : object(completed, "error");
            String message = error == null ? "" : string(error, "message");
            turn.myResult.completeExceptionally(new IOException(message.isEmpty() ? "Codex failed the turn" : message));
            return;
        }
        List<AIContent> content = new ArrayList<>();
        synchronized (turn) {
            content.add(AIContent.text(turn.myText.toString()));
            content.addAll(turn.myToolUses);
        }
        AIStopReason stopReason = "interrupted".equals(status) ? AIStopReason.CANCELLED : AIStopReason.END_TURN;
        turn.myResult.complete(new AIResponse(new AIMessage(AIRole.ASSISTANT, content), stopReason, AIUsage.UNKNOWN));
    }

    private CompletableFuture<JsonElement> approve(JsonObject params, String toolName, String command) {
        Turn turn = myTurns.get(string(params, "threadId"));
        AIStreamListener listener = turn == null ? null : turn.myListener;
        if (listener == null) {
            return CompletableFuture.completedFuture(decision("decline"));
        }
        String reason = string(params, "reason");
        JsonObject arguments = new JsonObject();
        if (!command.isEmpty()) {
            arguments.addProperty("command", command);
        }
        String cwd = string(params, "cwd");
        if (!cwd.isEmpty()) {
            arguments.addProperty("cwd", cwd);
        }
        AIToolPermissionRequest request = new AIToolPermissionRequest(
            string(params, "itemId"),
            toolName,
            command.isEmpty() ? toolName : command,
            reason.isEmpty() ? null : reason,
            arguments.toString()
        );
        return listener.onToolPermission(request).handle((value, error) -> {
            if (value == null) {
                return decision("cancel");
            }
            AIToolPermissionDecision decision = value;
            return decision(decision.allowed() ? (decision.always() ? "acceptForSession" : "accept") : "decline");
        });
    }

    private CompletableFuture<JsonElement> askUser(JsonObject params) {
        Turn turn = myTurns.get(string(params, "threadId"));
        AIStreamListener listener = turn == null ? null : turn.myListener;
        JsonElement questionsElement = params.get("questions");
        if (listener == null || questionsElement == null || !questionsElement.isJsonArray()) {
            return CompletableFuture.completedFuture(answers(Map.of()));
        }

        Map<String, String> ids = new LinkedHashMap<>();
        List<AIQuestion> questions = new ArrayList<>();
        for (JsonElement element : questionsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject question = element.getAsJsonObject();
            List<AIQuestionOption> options = new ArrayList<>();
            JsonElement optionsElement = question.get("options");
            if (optionsElement != null && optionsElement.isJsonArray()) {
                for (JsonElement option : optionsElement.getAsJsonArray()) {
                    if (option.isJsonObject()) {
                        options.add(new AIQuestionOption(string(option.getAsJsonObject(), "label"), string(option.getAsJsonObject(), "description")));
                    }
                }
            }
            String text = string(question, "question");
            ids.put(text, string(question, "id"));
            questions.add(new AIQuestion(text, string(question, "header"), options, false));
        }

        return listener.onQuestion(questions).handle((replies, error) -> {
            Map<String, String> byId = new LinkedHashMap<>();
            if (replies != null) {
                replies.forEach((question, reply) -> {
                    String id = ids.get(question);
                    if (id != null) {
                        byId.put(id, reply);
                    }
                });
            }
            return answers(byId);
        });
    }

    private static JsonObject decision(String decision) {
        JsonObject result = new JsonObject();
        result.addProperty("decision", decision);
        return result;
    }

    private static JsonObject answers(Map<String, String> byId) {
        JsonObject answers = new JsonObject();
        byId.forEach((id, reply) -> {
            JsonArray values = new JsonArray();
            values.add(reply);
            JsonObject answer = new JsonObject();
            answer.add("answers", values);
            answers.add(id, answer);
        });
        JsonObject result = new JsonObject();
        result.add("answers", answers);
        return result;
    }

    // endregion

    private static JsonObject threadSettings(Path workingDirectory, @Nullable String model, CodexPermissionMode mode) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().toString());
        if (model != null) {
            params.addProperty("model", model);
        }
        params.addProperty("approvalPolicy", mode.getApprovalPolicy());
        params.addProperty("sandbox", mode.getSandbox());
        return params;
    }

    private static AIChatInfo info(JsonObject thread) {
        String name = string(thread, "name");
        if (name.isBlank()) {
            name = string(thread, "preview").strip().lines().findFirst().orElse("");
        }
        String cwd = string(thread, "cwd");
        JsonElement updated = thread.get("updatedAt");
        Instant instant = updated != null && updated.isJsonPrimitive() ? Instant.ofEpochSecond(updated.getAsLong()) : Instant.EPOCH;
        return new AIChatInfo(string(thread, "id"), name, instant, cwd.isEmpty() ? null : Path.of(cwd));
    }

    private static void addItem(List<AIMessage> messages, JsonObject item) {
        switch (string(item, "type")) {
            case "userMessage" -> {
                StringBuilder text = new StringBuilder();
                JsonElement content = item.get("content");
                if (content != null && content.isJsonArray()) {
                    for (JsonElement input : content.getAsJsonArray()) {
                        if (input.isJsonObject() && "text".equals(string(input.getAsJsonObject(), "type"))) {
                            text.append(string(input.getAsJsonObject(), "text"));
                        }
                    }
                }
                if (!text.isEmpty()) {
                    messages.add(AIMessage.user(text.toString()));
                }
            }
            case "agentMessage" -> append(messages, AIContent.text(string(item, "text")));
            default -> {
                AIContent.ToolUse toolUse = toolUse(item);
                if (toolUse != null) {
                    append(messages, toolUse);
                }
            }
        }
    }

    private static void append(List<AIMessage> messages, AIContent content) {
        AIMessage last = messages.isEmpty() ? null : messages.getLast();
        if (last != null && last.role() == AIRole.ASSISTANT) {
            List<AIContent> merged = new ArrayList<>(last.content());
            merged.add(content);
            messages.set(messages.size() - 1, new AIMessage(AIRole.ASSISTANT, merged));
        }
        else {
            messages.add(new AIMessage(AIRole.ASSISTANT, List.of(content)));
        }
    }

    /**
     * What Codex ran - a command, a change of files, a tool of an MCP server - as a tool use of the chat.
     */
    private static AIContent.@Nullable ToolUse toolUse(JsonObject item) {
        String id = string(item, "id");
        return switch (string(item, "type")) {
            case "commandExecution" -> {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("command", string(item, "command"));
                yield AIContent.toolUse(id, "shell", arguments.toString());
            }
            case "fileChange" -> {
                JsonElement changes = item.get("changes");
                yield AIContent.toolUse(id, "apply_patch", changes == null ? "{}" : changes.toString());
            }
            case "mcpToolCall" -> {
                JsonElement arguments = item.get("arguments");
                yield AIContent.toolUse(id, string(item, "server") + "/" + string(item, "tool"), arguments == null ? "{}" : arguments.toString());
            }
            case "webSearch" -> {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("query", string(item, "query"));
                yield AIContent.toolUse(id, "web_search", arguments.toString());
            }
            default -> null;
        };
    }

    private static @Nullable JsonObject object(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static @Nullable String nonEmpty(String value) {
        return value.isEmpty() ? null : value;
    }
}
