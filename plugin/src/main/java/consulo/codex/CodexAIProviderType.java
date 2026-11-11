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

import consulo.ai.AIChatInfo;
import consulo.ai.AIContent;
import consulo.ai.AIMessage;
import consulo.ai.AIModel;
import consulo.ai.AIProvider;
import consulo.ai.AIProviderTypeWithChats;
import consulo.ai.AIRequest;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStreamListener;
import consulo.annotation.component.ExtensionImpl;
import consulo.codex.icon.CodexIconGroup;
import consulo.codex.localize.CodexLocalize;
import consulo.disposer.Disposable;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.process.cmd.GeneralCommandLine;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Codex through its native app server: it signs in on its own, keeps the threads - the chats - on its side and runs its
 * own tools. One app server runs per account and is started again when it died.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
@ExtensionImpl
@Singleton
public class CodexAIProviderType implements AIProviderTypeWithChats {
    public static final String ID = "codex";
    static final String EXECUTABLE = "codex";

    private static final CodexModel DEFAULT_MODEL = new CodexModel(CodexModel.DEFAULT_ID, CodexLocalize.modelDefault());

    private final ProcessHandlerBuilderFactory myProcessHandlerBuilderFactory;
    private final Map<String, CompletableFuture<CodexAppServer>> myServers = new ConcurrentHashMap<>();
    /**
     * The models of the account as the app server last listed them - known once a server ran.
     */
    private volatile List<AIModel> myModels = List.of(DEFAULT_MODEL);

    @Inject
    public CodexAIProviderType(ProcessHandlerBuilderFactory processHandlerBuilderFactory) {
        myProcessHandlerBuilderFactory = processHandlerBuilderFactory;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return CodexLocalize.providerName();
    }

    @Override
    public Image getIcon() {
        return CodexIconGroup.codex();
    }

    @Override
    public List<AIModel> getModels() {
        return myModels;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @RequiredUIAccess
    @Override
    public @Nullable Component createAccountComponent(Map<String, String> options, Disposable uiDisposable) {
        return new CodexAccountComponent(this, options, uiDisposable).getComponent();
    }

    /**
     * Configured when the command line is there - whether it is signed in shows on the first answer, which carries the
     * reason.
     */
    @Override
    public CompletableFuture<Boolean> isConfigured(AIProvider provider) {
        return CompletableFuture.completedFuture(findExecutable() != null);
    }

    // region servers

    static @Nullable Path findExecutable() {
        return Platform.current().findExecutableInPath(EXECUTABLE);
    }

    /**
     * Starts an app server of its own - the settings sign in through one, which is stopped with them.
     */
    CompletableFuture<CodexAppServer> startServer() {
        Path executable = findExecutable();
        if (executable == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(CodexLocalize.errorNoCommandLine().get()));
        }
        GeneralCommandLine commandLine = new GeneralCommandLine()
            .withExecutablePath(executable)
            .withParameters(List.of("app-server"))
            .withWorkingDirectory(Platform.current().user().homePath())
            .withCharset(StandardCharsets.UTF_8);
        return CodexAppServer.start(myProcessHandlerBuilderFactory, commandLine);
    }

    private CompletableFuture<CodexAppServer> getServer(AIProvider provider) {
        return myServers.compute(provider.getName(), (name, current) -> {
            if (current != null && !(current.isCompletedExceptionally() || current.isDone() && !current.join().isAlive())) {
                return current;
            }
            CompletableFuture<CodexAppServer> server = startServer();
            server.thenCompose(CodexAppServer::listModels).thenAccept(this::updateModels);
            return server;
        });
    }

    /**
     * Stops every app server - after a sign-in or out they start again with the new login.
     */
    void stopServers() {
        for (CompletableFuture<CodexAppServer> server : myServers.values()) {
            server.thenAccept(CodexAppServer::close);
        }
        myServers.clear();
    }

    private void updateModels(List<CodexAppServer.Model> listed) {
        List<AIModel> models = new ArrayList<>();
        models.add(DEFAULT_MODEL);
        for (CodexAppServer.Model model : listed) {
            models.add(new CodexModel(model.id(), LocalizeValue.of(model.displayName())));
        }
        myModels = List.copyOf(models);
    }

    // endregion

    // region chats

    @Override
    public CompletableFuture<String> newChat(AIProvider provider, Project project, @Nullable Path workingDirectory) {
        Path directory = workingDirectory != null ? workingDirectory : directory(project);
        return getServer(provider).thenCompose(server -> server.startThread(directory, null, CodexPermissionMode.of(provider)));
    }

    @Override
    public CompletableFuture<List<AIChatInfo>> getChats(AIProvider provider, Project project) {
        return getServer(provider).thenCompose(server -> server.listThreads(directory(project)));
    }

    @Override
    public CompletableFuture<@Nullable AIChatInfo> getChat(AIProvider provider, Project project, String chatId) {
        return getServer(provider).thenCompose(server -> server.readThreadInfo(chatId));
    }

    @Override
    public CompletableFuture<List<AIMessage>> loadChat(AIProvider provider, Project project, String chatId) {
        return getServer(provider).thenCompose(server -> server.readThread(chatId));
    }

    @Override
    public CompletableFuture<?> renameChat(AIProvider provider, Project project, String chatId, String title) {
        return getServer(provider).thenCompose(server -> server.renameThread(chatId, title));
    }

    /**
     * The thread is archived in Codex, not deleted - it can be brought back with {@code codex unarchive}.
     */
    @Override
    public CompletableFuture<?> deleteChat(AIProvider provider, Project project, String chatId) {
        return getServer(provider).thenCompose(server -> server.archiveThread(chatId));
    }

    @Override
    public void closeSession(AIProvider provider, String sessionId) {
        CompletableFuture<CodexAppServer> server = myServers.get(provider.getName());
        if (server != null) {
            server.thenAccept(running -> running.unloadThread(sessionId));
        }
    }

    // endregion

    @Override
    public CompletableFuture<AIResponse> chat(AIProvider provider, AIRequest request, @Nullable AIStreamListener listener) {
        String text = lastUserText(request);
        if (text == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Nothing to send - the request has no user text"));
        }
        Path workingDirectory = request.getWorkingDirectory();
        Path directory = workingDirectory != null ? workingDirectory : Platform.current().user().homePath();
        String model = CodexModel.DEFAULT_ID.equals(request.getModel().getId()) ? null : request.getModel().getId();
        CodexPermissionMode mode = CodexPermissionMode.of(provider);
        String chatId = request.getSessionId();

        return getServer(provider).thenCompose(server -> {
            if (chatId == null) {
                return server.startThread(directory, model, mode).thenCompose(threadId -> server.startTurn(threadId, text, model, listener)
                    .whenComplete((response, error) -> server.unloadThread(threadId)));
            }
            return server.resumeThread(chatId, directory, model, mode).thenCompose(ignored -> server.startTurn(chatId, text, model, listener));
        });
    }

    private static Path directory(Project project) {
        String basePath = project.getBasePath();
        return basePath != null ? Path.of(basePath) : Platform.current().user().homePath();
    }

    private static @Nullable String lastUserText(AIRequest request) {
        List<AIMessage> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AIMessage message = messages.get(i);
            if (message.role() != AIRole.USER) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (AIContent content : message.content()) {
                if (content instanceof AIContent.Text part) {
                    text.append(part.text());
                }
            }
            return text.isEmpty() ? null : text.toString();
        }
        return null;
    }
}
