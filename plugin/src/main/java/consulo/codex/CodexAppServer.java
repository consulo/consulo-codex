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
import com.google.gson.JsonPrimitive;
import consulo.ai.AIChatHiddenContext;
import consulo.ai.AIChatInfo;
import consulo.ai.AIContent;
import consulo.ai.AIContextUsage;
import consulo.ai.AIMcpServer;
import consulo.ai.AIMcpServerProvider;
import consulo.ai.AIMessage;
import consulo.ai.AIQuestion;
import consulo.ai.AIQuestionOption;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStopReason;
import consulo.ai.AIStreamListener;
import consulo.ai.AIToolCallContent;
import consulo.ai.AIToolPermissionDecision;
import consulo.ai.AIToolPermissionOption;
import consulo.ai.AIToolPermissionOptionKind;
import consulo.ai.AIToolPermissionRequest;
import consulo.ai.AIUsage;
import consulo.ai.acp.AcpConnection;
import consulo.application.Application;
import consulo.codex.localize.CodexLocalize;
import consulo.logging.Logger;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.process.cmd.GeneralCommandLine;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code codex app-server} process - JSON-RPC over its standard input and output - and the threads loaded in it.
 * A thread is a chat; the turns of the threads are answered here, with the approvals and questions of a turn asked
 * through the listener of that turn.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
final class CodexAppServer {
    private static final Logger LOG = Logger.getInstance(CodexAppServer.class);
    private static final String PLAN_DIRECTORY = ".consulo/ai-plans";

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
        final StringBuilder myThinking = new StringBuilder();
        final Map<String, AIContent.ToolCall> myToolCalls = new LinkedHashMap<>();
        /**
         * The last state of every item which is a tool call, and what its command printed so far.
         */
        final Map<String, JsonObject> myItems = new ConcurrentHashMap<>();
        final Map<String, StringBuilder> myOutputs = new ConcurrentHashMap<>();
        volatile @Nullable String myTurnId;
        /**
         * Where the plan of a turn in plan mode is saved - null for a turn of the default mode.
         */
        final @Nullable Path myPlanDirectory;
        volatile boolean myPlanSaved;

        Turn(@Nullable AIStreamListener listener, @Nullable Path planDirectory) {
            myListener = listener;
            myPlanDirectory = planDirectory;
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
            if (turn != null) {
                String delta = string(params, "delta");
                synchronized (turn) {
                    turn.myThinking.append(delta);
                }
                if (turn.myListener != null) {
                    turn.myListener.onThinkingDelta(delta);
                }
            }
        });
        connection.onNotification("item/started", params -> onItem(string(params, "threadId"), object(params, "item")));
        connection.onNotification("item/completed", params -> onItem(string(params, "threadId"), object(params, "item")));
        connection.onNotification("item/commandExecution/outputDelta", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            String itemId = string(params, "itemId");
            if (turn == null) {
                return;
            }
            turn.myOutputs.computeIfAbsent(itemId, id -> new StringBuilder()).append(string(params, "delta"));
            JsonObject item = turn.myItems.get(itemId);
            if (item != null) {
                onItem(string(params, "threadId"), item);
            }
        });
        connection.onNotification("turn/plan/updated", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            if (turn != null && turn.myListener != null) {
                turn.myListener.onPlan(CodexItems.plan(params));
            }
        });
        connection.onNotification("thread/tokenUsage/updated", params -> {
            Turn turn = myTurns.get(string(params, "threadId"));
            JsonObject usage = object(params, "tokenUsage");
            if (turn == null || turn.myListener == null || usage == null) {
                return;
            }
            JsonObject last = object(usage, "last");
            JsonElement window = usage.get("modelContextWindow");
            if (last != null && window != null && window.isJsonPrimitive()) {
                turn.myListener.onContextUsage(new AIContextUsage(last.get("totalTokens").getAsLong(), window.getAsLong(), null, null));
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
        connection.onRequest("item/permissions/requestApproval", this::approvePermissions);
        connection.onRequest("mcpServer/elicitation/request", this::elicit);
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
        // the plan mode of a turn - its collaboration mode - is an experimental part of the protocol
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("experimentalApi", true);
        params.add("capabilities", capabilities);
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
    CompletableFuture<AIResponse> startTurn(String threadId,
                                            List<AIContent> content,
                                            @Nullable String model,
                                            @Nullable Path planDirectory,
                                            @Nullable AIStreamListener listener) {
        CompletableFuture<@Nullable String> collaborationModel = model != null
            ? CompletableFuture.completedFuture(model)
            : listModels().<@Nullable String>thenApply(CodexAppServer::defaultModel).exceptionally(error -> null);
        return collaborationModel.thenCompose(resolved -> startTurn(threadId, content, model, resolved, planDirectory, listener));
    }

    private static @Nullable String defaultModel(List<Model> models) {
        for (Model model : models) {
            if (model.isDefault()) {
                return model.id();
            }
        }
        return models.isEmpty() ? null : models.getFirst().id();
    }

    private CompletableFuture<AIResponse> startTurn(String threadId,
                                                    List<AIContent> content,
                                                    @Nullable String model,
                                                    @Nullable String collaborationModel,
                                                    @Nullable Path planDirectory,
                                                    @Nullable AIStreamListener listener) {
        Turn turn = new Turn(listener, planDirectory);
        if (myTurns.putIfAbsent(threadId, turn) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The chat is still answering"));
        }

        JsonArray inputs = inputs(content);
        myEmpty.remove(threadId);
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId);
        params.add("input", inputs);
        if (model != null) {
            params.addProperty("model", model);
        }
        // the mode stays with the thread - every turn tells it, so leaving plan mode works too
        if (collaborationModel != null) {
            JsonObject settings = new JsonObject();
            settings.addProperty("model", collaborationModel);
            JsonObject collaboration = new JsonObject();
            collaboration.addProperty("mode", planDirectory != null ? "plan" : "default");
            collaboration.add("settings", settings);
            params.add("collaborationMode", collaboration);
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
            if (!turn.myThinking.isEmpty()) {
                content.add(AIContent.thinking(turn.myThinking.toString()));
            }
            content.add(AIContent.text(turn.myText.toString()));
            content.addAll(turn.myToolCalls.values());
        }
        AIStopReason stopReason = "interrupted".equals(status) ? AIStopReason.CANCELLED : AIStopReason.END_TURN;
        turn.myResult.complete(new AIResponse(new AIMessage(AIRole.ASSISTANT, content), stopReason, AIUsage.UNKNOWN));
    }

    /**
     * An item started or changed - the tool calls among them go to the chat whole, with what their command printed.
     */
    private void onItem(String threadId, @Nullable JsonObject item) {
        Turn turn = myTurns.get(threadId);
        if (turn == null || item == null) {
            return;
        }
        if ("plan".equals(string(item, "type"))) {
            onPlan(turn, string(item, "text"));
            return;
        }
        String id = string(item, "id");
        StringBuilder output = turn.myOutputs.get(id);
        boolean finished = "completed".equals(string(item, "status")) || "failed".equals(string(item, "status"));
        AIContent.ToolCall call = CodexItems.toolCall(item, output == null || finished ? null : output.toString());
        if (call == null) {
            return;
        }
        turn.myItems.put(id, item);
        synchronized (turn) {
            turn.myToolCalls.put(id, call);
        }
        if (turn.myListener != null) {
            turn.myListener.onToolCall(call);
        }
    }

    /**
     * The plan of a turn in plan mode - shown in the answer, and saved as a file the chat opens for review.
     */
    private static void onPlan(Turn turn, String text) {
        Path directory = turn.myPlanDirectory;
        if (text.isBlank() || turn.myPlanSaved) {
            return;
        }
        turn.myPlanSaved = true;
        String shown = "\n\n" + text.strip() + "\n";
        synchronized (turn) {
            turn.myText.append(shown);
        }
        AIStreamListener listener = turn.myListener;
        if (listener == null) {
            return;
        }
        listener.onTextDelta(shown);
        if (directory == null) {
            return;
        }
        try {
            Path file = directory.resolve(PLAN_DIRECTORY).resolve(planName(text) + "-" + Integer.toHexString(text.hashCode()) + ".plan.md");
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            listener.onPlanFile(file);
        }
        catch (IOException e) {
            LOG.warn("Cannot save the plan", e);
        }
    }

    /**
     * The first heading of the plan as a file name.
     */
    private static String planName(String text) {
        String title = text.lines().filter(line -> line.startsWith("#")).findFirst().orElse("plan").replaceFirst("^#+", "").strip();
        String name = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.length() > 48) {
            name = name.substring(0, 48);
        }
        return name.isEmpty() ? "plan" : name;
    }

    /**
     * Cuts the history of the thread before the turn of the {@code keptPrompts + 1}-th prompt - the chat goes on from
     * an earlier message. The files stay as they are.
     */
    CompletableFuture<?> revert(String threadId, int keptPrompts) {
        JsonObject read = new JsonObject();
        read.addProperty("threadId", threadId);
        read.addProperty("includeTurns", true);
        return myConnection.request("thread/read", read).thenCompose(result -> {
            JsonObject thread = result.isJsonObject() ? object(result.getAsJsonObject(), "thread") : null;
            JsonElement turns = thread == null ? null : thread.get("turns");
            if (turns == null || !turns.isJsonArray() || turns.getAsJsonArray().size() <= keptPrompts) {
                return CompletableFuture.completedFuture(null);
            }
            JsonElement turn = turns.getAsJsonArray().get(keptPrompts);
            JsonObject params = new JsonObject();
            params.addProperty("threadId", threadId);
            params.addProperty("beforeTurnId", turn.isJsonObject() ? string(turn.getAsJsonObject(), "id") : "");
            return myConnection.request("thread/revert", params);
        });
    }

    private static final String NETWORK_OPTION_PREFIX = "network:";
    private static final Pattern MCP_TOOL = Pattern.compile("tool \"([^\"]+)\"");

    private CompletableFuture<JsonElement> approve(JsonObject params, String toolName, String command) {
        Turn turn = myTurns.get(string(params, "threadId"));
        if (turn == null || turn.myListener == null) {
            return CompletableFuture.completedFuture(decision("decline"));
        }
        AIStreamListener listener = turn.myListener;
        String reason = string(params, "reason");
        JsonObject arguments = new JsonObject();
        if (!command.isEmpty()) {
            arguments.addProperty("command", command);
        }
        String cwd = string(params, "cwd");
        if (!cwd.isEmpty()) {
            arguments.addProperty("cwd", cwd);
        }
        // the changes the approval is about, from the item Codex told before it asked
        JsonObject item = turn.myItems.get(string(params, "itemId"));
        AIContent.ToolCall call = item == null ? null : CodexItems.toolCall(item, null);
        List<AIToolCallContent> content = call == null ? List.of() : call.content();

        JsonElement amendment = params.get("proposedExecpolicyAmendment");
        List<AIToolPermissionOption> options = new ArrayList<>();
        options.add(new AIToolPermissionOption("accept", CodexLocalize.approvalAccept().get(), AIToolPermissionOptionKind.ALLOW_ONCE));
        options.add(new AIToolPermissionOption("acceptForSession", CodexLocalize.approvalAcceptForSession().get(), AIToolPermissionOptionKind.ALLOW_ALWAYS));
        if (amendment != null && amendment.isJsonArray() && !amendment.getAsJsonArray().isEmpty()) {
            List<String> prefix = new ArrayList<>();
            amendment.getAsJsonArray().forEach(element -> prefix.add(element.getAsString()));
            options.add(new AIToolPermissionOption("acceptWithExecpolicyAmendment",
                CodexLocalize.approvalAcceptPrefix(String.join(" ", prefix)).get(), AIToolPermissionOptionKind.ALLOW_ALWAYS));
        }
        // a command which reaches a host asks for a rule for the host too
        List<JsonObject> networkAmendments = new ArrayList<>();
        JsonElement network = params.get("proposedNetworkPolicyAmendments");
        if (network != null && network.isJsonArray()) {
            for (JsonElement element : network.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject networkAmendment = element.getAsJsonObject();
                String host = string(networkAmendment, "host");
                boolean allow = "allow".equals(string(networkAmendment, "action"));
                options.add(new AIToolPermissionOption(
                    NETWORK_OPTION_PREFIX + networkAmendments.size(),
                    allow ? CodexLocalize.approvalNetworkAllow(host).get() : CodexLocalize.approvalNetworkDeny(host).get(),
                    allow ? AIToolPermissionOptionKind.ALLOW_ALWAYS : AIToolPermissionOptionKind.REJECT_ALWAYS
                ));
                networkAmendments.add(networkAmendment);
            }
        }
        options.add(new AIToolPermissionOption("decline", CodexLocalize.approvalDecline().get(), AIToolPermissionOptionKind.REJECT_ONCE));
        options.add(new AIToolPermissionOption("cancel", CodexLocalize.approvalCancel().get(), AIToolPermissionOptionKind.REJECT_ONCE));

        JsonObject networkContext = params.get("networkApprovalContext") instanceof JsonObject context ? context : null;
        if (networkContext != null && reason.isEmpty()) {
            reason = CodexLocalize.approvalNetworkReason(string(networkContext, "host")).get();
        }

        AIToolPermissionRequest request = new AIToolPermissionRequest(
            string(params, "itemId"),
            toolName,
            command.isEmpty() ? (call == null ? toolName : call.title()) : command,
            reason.isEmpty() ? null : reason,
            arguments.toString(),
            options,
            content,
            false
        );
        return listener.onToolPermission(request).handle((value, error) -> {
            if (value == null) {
                return decision("cancel");
            }
            String optionId = value.optionId();
            if (optionId == null) {
                return decision(value.allowed() ? (value.always() ? "acceptForSession" : "accept") : "decline");
            }
            if (optionId.startsWith(NETWORK_OPTION_PREFIX)) {
                int index = Integer.parseInt(optionId.substring(NETWORK_OPTION_PREFIX.length()));
                JsonObject amended = new JsonObject();
                amended.add("network_policy_amendment", networkAmendments.get(index));
                JsonObject choice = new JsonObject();
                choice.add("applyNetworkPolicyAmendment", amended);
                JsonObject result = new JsonObject();
                result.add("decision", choice);
                return result;
            }
            if ("acceptWithExecpolicyAmendment".equals(optionId) && amendment != null) {
                JsonObject amended = new JsonObject();
                amended.add("execpolicy_amendment", amendment);
                JsonObject choice = new JsonObject();
                choice.add("acceptWithExecpolicyAmendment", amended);
                JsonObject result = new JsonObject();
                result.add("decision", choice);
                return result;
            }
            return decision(optionId);
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

    /**
     * Codex asks for more than its sandbox allows - the network, paths to read or write. Allowing grants what it asked
     * for, for the turn or the rest of the chat.
     */
    private CompletableFuture<JsonElement> approvePermissions(JsonObject params) {
        Turn turn = myTurns.get(string(params, "threadId"));
        JsonObject requested = params.get("permissions") instanceof JsonObject permissions ? permissions : new JsonObject();
        if (turn == null || turn.myListener == null) {
            return CompletableFuture.completedFuture(grant(new JsonObject(), "turn"));
        }
        String reason = string(params, "reason");
        List<AIToolPermissionOption> options = List.of(
            new AIToolPermissionOption("turn", CodexLocalize.permissionsAllowTurn().get(), AIToolPermissionOptionKind.ALLOW_ONCE),
            new AIToolPermissionOption("session", CodexLocalize.permissionsAllowSession().get(), AIToolPermissionOptionKind.ALLOW_ALWAYS),
            new AIToolPermissionOption("decline", CodexLocalize.approvalDecline().get(), AIToolPermissionOptionKind.REJECT_ONCE)
        );
        AIToolPermissionRequest request = new AIToolPermissionRequest(
            string(params, "itemId"),
            "permissions",
            CodexLocalize.permissionsTitle(describe(requested)).get(),
            reason.isEmpty() ? null : reason,
            requested.toString(),
            options,
            List.of(),
            false
        );
        return turn.myListener.onToolPermission(request).handle((value, error) -> {
            String optionId = value == null ? "decline" : value.optionId();
            if (optionId == null) {
                optionId = value.allowed() ? (value.always() ? "session" : "turn") : "decline";
            }
            return "decline".equals(optionId) ? grant(new JsonObject(), "turn") : grant(requested, optionId);
        });
    }

    private static JsonObject grant(JsonObject permissions, String scope) {
        JsonObject result = new JsonObject();
        result.add("permissions", permissions);
        result.addProperty("scope", scope);
        return result;
    }

    /**
     * What a permission profile asks for, in a few words - the network, the paths and how.
     */
    private static String describe(JsonObject permissions) {
        List<String> parts = new ArrayList<>();
        if (permissions.get("network") instanceof JsonObject network && bool(network, "enabled")) {
            parts.add(CodexLocalize.permissionsNetwork().get());
        }
        if (permissions.get("fileSystem") instanceof JsonObject fileSystem) {
            for (String access : List.of("read", "write")) {
                if (fileSystem.get(access) instanceof JsonArray paths) {
                    for (JsonElement path : paths) {
                        parts.add(access + " " + path.getAsString());
                    }
                }
            }
            if (fileSystem.get("entries") instanceof JsonArray entries) {
                for (JsonElement entry : entries) {
                    if (entry instanceof JsonObject object && object.get("path") instanceof JsonObject path) {
                        String where = string(path, "path");
                        parts.add(string(object, "access") + " " + (where.isEmpty() ? nonEmpty(string(path, "pattern"), string(path, "value")) : where));
                    }
                }
            }
        }
        return parts.isEmpty() ? CodexLocalize.permissionsMore().get() : String.join(", ", parts);
    }

    private static String nonEmpty(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /**
     * An MCP server of Codex asks the user for something - a form, a field a question, or a page to open.
     */
    private CompletableFuture<JsonElement> elicit(JsonObject params) {
        Turn turn = myTurns.get(string(params, "threadId"));
        AIStreamListener listener = turn == null ? null : turn.myListener;
        if (listener == null) {
            return CompletableFuture.completedFuture(elicitation("cancel", null));
        }
        String server = string(params, "serverName");
        String message = string(params, "message");
        JsonObject meta = params.get("_meta") instanceof JsonObject object ? object : new JsonObject();
        if ("mcp_tool_call".equals(string(meta, "codex_approval_kind"))) {
            return approveMcpTool(listener, server, message, meta);
        }
        if ("url".equals(string(params, "mode"))) {
            String url = string(params, "url");
            String open = CodexLocalize.elicitationOpened().get();
            AIQuestion question = new AIQuestion(message + "\n" + url, server, List.of(
                new AIQuestionOption(open, url),
                new AIQuestionOption(CodexLocalize.elicitationDecline().get(), "")
            ), false);
            return listener.onQuestion(List.of(question)).handle((replies, error) -> {
                String reply = replies == null ? null : replies.get(question.question());
                return elicitation(reply == null ? "cancel" : open.equals(reply) ? "accept" : "decline", null);
            });
        }

        JsonObject schema = params.get("requestedSchema") instanceof JsonObject requested ? requested : new JsonObject();
        JsonObject properties = schema.get("properties") instanceof JsonObject fields ? fields : new JsonObject();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        List<AIQuestion> questions = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject field)) {
                continue;
            }
            String title = nonEmpty(string(field, "title"), entry.getKey());
            String description = string(field, "description");
            String text = questions.isEmpty() && !message.isEmpty() ? message + "\n" + nonEmpty(description, title) : nonEmpty(description, title);
            List<AIQuestionOption> options = new ArrayList<>();
            for (String[] choice : choices(field)) {
                options.add(new AIQuestionOption(choice[1], ""));
            }
            questions.add(new AIQuestion(text, title, options, "array".equals(string(field, "type"))));
            names.put(text, entry.getKey());
            fields.put(entry.getKey(), field);
        }
        if (questions.isEmpty()) {
            questions.add(new AIQuestion(message, server, List.of(
                new AIQuestionOption(CodexLocalize.elicitationAccept().get(), ""),
                new AIQuestionOption(CodexLocalize.elicitationDecline().get(), "")
            ), false));
        }
        return listener.onQuestion(questions).handle((replies, error) -> {
            if (replies == null || replies.isEmpty()) {
                return elicitation("cancel", null);
            }
            if (fields.isEmpty()) {
                return elicitation(CodexLocalize.elicitationAccept().get().equals(replies.get(message)) ? "accept" : "decline", null);
            }
            JsonObject content = new JsonObject();
            replies.forEach((question, reply) -> {
                String name = names.get(question);
                JsonObject field = name == null ? null : fields.get(name);
                if (field != null) {
                    content.add(name, value(field, reply));
                }
            });
            return elicitation("accept", content);
        });
    }

    /**
     * Codex asks before a tool of an MCP server changes something - an approval like the one of a command, which may
     * be remembered for the chat or for good when Codex offers that.
     */
    private CompletableFuture<JsonElement> approveMcpTool(AIStreamListener listener, String server, String message, JsonObject meta) {
        List<String> persist = new ArrayList<>();
        if (meta.get("persist") instanceof JsonArray values) {
            values.forEach(value -> persist.add(value.getAsString()));
        }
        List<AIToolPermissionOption> options = new ArrayList<>();
        options.add(new AIToolPermissionOption("accept", CodexLocalize.approvalAccept().get(), AIToolPermissionOptionKind.ALLOW_ONCE));
        if (persist.contains("session")) {
            options.add(new AIToolPermissionOption("session", CodexLocalize.approvalMcpSession().get(), AIToolPermissionOptionKind.ALLOW_ALWAYS));
        }
        if (persist.contains("always")) {
            options.add(new AIToolPermissionOption("always", CodexLocalize.approvalMcpAlways().get(), AIToolPermissionOptionKind.ALLOW_ALWAYS));
        }
        options.add(new AIToolPermissionOption("decline", CodexLocalize.approvalDecline().get(), AIToolPermissionOptionKind.REJECT_ONCE));

        String description = string(meta, "tool_description");
        // the card asks "Allow ...?" itself - it names the tool, the message of Codex is the whole question
        Matcher tool = MCP_TOOL.matcher(message);
        AIToolPermissionRequest request = new AIToolPermissionRequest(
            "mcp:" + server,
            "mcp",
            tool.find() ? server + "/" + tool.group(1) : message,
            description.isEmpty() ? null : description,
            meta.get("tool_params") instanceof JsonObject toolParams ? toolParams.toString() : "{}",
            options,
            List.of(),
            false
        );
        return listener.onToolPermission(request).handle((value, error) -> {
            if (value == null) {
                return elicitation("cancel", null);
            }
            String optionId = value.optionId();
            if (optionId == null) {
                optionId = value.allowed() ? (value.always() && persist.contains("session") ? "session" : "accept") : "decline";
            }
            if ("decline".equals(optionId)) {
                return elicitation("decline", null);
            }
            JsonObject result = elicitation("accept", null);
            if (!"accept".equals(optionId)) {
                JsonObject resultMeta = new JsonObject();
                resultMeta.addProperty("persist", optionId);
                result.add("_meta", resultMeta);
            }
            return result;
        });
    }

    /**
     * The choices of a field as {value, title} - an enum, titled or not, a boolean as yes and no.
     */
    private static List<String[]> choices(JsonObject field) {
        List<String[]> choices = new ArrayList<>();
        if ("boolean".equals(string(field, "type"))) {
            choices.add(new String[]{"true", CodexLocalize.elicitationYes().get()});
            choices.add(new String[]{"false", CodexLocalize.elicitationNo().get()});
            return choices;
        }
        JsonObject items = field.get("items") instanceof JsonObject arrayItems ? arrayItems : field;
        JsonElement titled = items.has("oneOf") ? items.get("oneOf") : items.get("anyOf");
        if (titled instanceof JsonArray options) {
            for (JsonElement option : options) {
                if (option instanceof JsonObject object) {
                    choices.add(new String[]{string(object, "const"), nonEmpty(string(object, "title"), string(object, "const"))});
                }
            }
            return choices;
        }
        if (items.get("enum") instanceof JsonArray values) {
            JsonArray titles = items.get("enumNames") instanceof JsonArray enumNames ? enumNames : null;
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i).getAsString();
                choices.add(new String[]{value, titles != null && i < titles.size() ? titles.get(i).getAsString() : value});
            }
        }
        return choices;
    }

    /**
     * The answer as the type of the field wants it - a chosen title back to its value, a number, a boolean.
     */
    private static JsonElement value(JsonObject field, String reply) {
        String type = string(field, "type");
        List<String[]> choices = choices(field);
        if ("array".equals(type)) {
            JsonArray values = new JsonArray();
            for (String part : reply.split(",\\s*")) {
                values.add(choiceValue(choices, part));
            }
            return values;
        }
        String chosen = choiceValue(choices, reply);
        return switch (type) {
            case "boolean" -> new JsonPrimitive(Boolean.parseBoolean(chosen));
            case "number", "integer" -> {
                try {
                    double number = Double.parseDouble(chosen.strip());
                    yield "integer".equals(type) ? new JsonPrimitive((long) number) : new JsonPrimitive(number);
                }
                catch (NumberFormatException e) {
                    yield new JsonPrimitive(chosen);
                }
            }
            default -> new JsonPrimitive(chosen);
        };
    }

    private static String choiceValue(List<String[]> choices, String reply) {
        for (String[] choice : choices) {
            if (choice[1].equals(reply)) {
                return choice[0];
            }
        }
        return reply;
    }

    private static JsonObject elicitation(String action, @Nullable JsonObject content) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        if (content != null) {
            result.add("content", content);
        }
        return result;
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

    /**
     * The message of the user as Codex takes it - a file is a mention, an image a data url, a selection the link of its
     * file and its text.
     */
    private static JsonArray inputs(List<AIContent> content) {
        JsonArray inputs = new JsonArray();
        for (AIContent part : content) {
            JsonObject input = new JsonObject();
            switch (part) {
                case AIContent.Text text -> {
                    input.addProperty("type", "text");
                    input.addProperty("text", text.text());
                }
                case AIContent.Image image -> {
                    input.addProperty("type", "image");
                    input.addProperty("url", "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data()));
                }
                case AIContent.ResourceLink link when link.uri().startsWith("file:") -> {
                    input.addProperty("type", "mention");
                    input.addProperty("name", link.name());
                    input.addProperty("path", Path.of(URI.create(link.uri())).toString());
                }
                case AIContent.ResourceLink link -> {
                    input.addProperty("type", "text");
                    input.addProperty("text", "[@" + link.name() + "](" + link.uri() + ")");
                }
                case AIContent.Resource resource -> {
                    String uri = resource.uri();
                    input.addProperty("type", "text");
                    input.addProperty("text", "[@" + uri.substring(uri.lastIndexOf('/') + 1) + "](" + uri + ")\n<context ref=\"" + uri + "\">\n"
                        + resource.text() + "\n</context>");
                }
                default -> {
                    continue;
                }
            }
            inputs.add(input);
        }
        return inputs;
    }

    /**
     * The MCP servers of the IDE in the configuration of a thread - the model searches and navigates with the indexes
     * of the IDE through them.
     */
    private static @Nullable JsonObject mcpConfig() {
        JsonObject servers = new JsonObject();
        Application.get().getExtensionPoint(AIMcpServerProvider.class).forEach(provider -> {
            for (AIMcpServer server : provider.getServers()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("url", server.url());
                JsonObject headers = new JsonObject();
                server.headers().forEach(headers::addProperty);
                entry.add("http_headers", headers);
                servers.add(server.name(), entry);
            }
        });
        if (servers.isEmpty()) {
            return null;
        }
        JsonObject config = new JsonObject();
        config.add("mcp_servers", servers);
        return config;
    }

    private static JsonObject threadSettings(Path workingDirectory, @Nullable String model, CodexPermissionMode mode) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().toString());
        if (model != null) {
            params.addProperty("model", model);
        }
        params.addProperty("approvalPolicy", mode.getApprovalPolicy());
        params.addProperty("sandbox", mode.getSandbox());
        JsonObject config = mcpConfig();
        if (config != null) {
            params.add("config", config);
        }
        return params;
    }

    private static AIChatInfo info(JsonObject thread) {
        String name = string(thread, "name");
        if (name.isBlank()) {
            // the preview is the first prompt as it was sent - the hidden rules in front of it are not its title
            name = AIChatHiddenContext.strip(string(thread, "preview")).strip().lines().findFirst().orElse("");
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
                List<AIContent> parts = new ArrayList<>();
                if (!text.isEmpty()) {
                    parts.add(AIContent.text(text.toString()));
                }
                if (content != null && content.isJsonArray()) {
                    for (JsonElement input : content.getAsJsonArray()) {
                        if (input.isJsonObject() && "mention".equals(string(input.getAsJsonObject(), "type"))) {
                            JsonObject mention = input.getAsJsonObject();
                            parts.add(AIContent.resourceLink(Path.of(string(mention, "path")).toUri().toString(), string(mention, "name"), null));
                        }
                        else if (input.isJsonObject() && "image".equals(string(input.getAsJsonObject(), "type"))) {
                            AIContent.Image image = dataUrl(string(input.getAsJsonObject(), "url"));
                            if (image != null) {
                                parts.add(image);
                            }
                        }
                    }
                }
                if (!parts.isEmpty()) {
                    messages.add(new AIMessage(AIRole.USER, parts));
                }
            }
            case "agentMessage" -> append(messages, AIContent.text(string(item, "text")));
            default -> {
                AIContent.ToolCall call = CodexItems.toolCall(item, null);
                if (call != null) {
                    append(messages, call);
                }
            }
        }
    }

    /**
     * An image sent as a data url - the way the chat sends its images.
     */
    private static AIContent.@Nullable Image dataUrl(String url) {
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0 || !url.substring(0, comma).endsWith(";base64")) {
            return null;
        }
        try {
            return AIContent.image(url.substring("data:".length(), url.indexOf(';')), Base64.getDecoder().decode(url.substring(comma + 1)));
        }
        catch (IllegalArgumentException e) {
            return null;
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
