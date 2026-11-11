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

import consulo.codex.localize.CodexLocalize;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.ui.Button;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.InputBoxBuilder;
import consulo.ui.Label;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The login of Codex - what it is signed in as, and the buttons to sign in with ChatGPT or an API key, or out - and
 * the permissions of its threads. The login is the one of the command line, shared by every account of Codex.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
final class CodexAccountComponent {
    private final CodexAIProviderType myType;
    private final UIAccess myUIAccess;
    private final CompletableFuture<CodexAppServer> myServer;

    private final VerticalLayout myRoot = VerticalLayout.create();
    private final Label myStatus = Label.create(CodexLocalize.statusChecking());
    private final Button myLogin;
    private final Button myApiKeyLogin;
    private final Button myLogout;

    private boolean myDisposed;

    @RequiredUIAccess
    CodexAccountComponent(CodexAIProviderType type, Map<String, String> options, Disposable uiDisposable) {
        myType = type;
        myUIAccess = UIAccess.current();
        myServer = type.startServer();

        ComboBox<CodexPermissionMode> permissionBox = ComboBox.create(CodexPermissionMode.values());
        permissionBox.setTextRenderer(mode -> mode == null ? LocalizeValue.empty() : mode.getDisplayName());
        permissionBox.setValue(CodexPermissionMode.of(options));
        permissionBox.addValueListener(event -> {
            CodexPermissionMode mode = event.getValue();
            if (mode != null) {
                mode.store(options);
            }
        });
        myRoot.add(DockLayout.create().left(Label.create(CodexLocalize.labelPermissions())).right(permissionBox));

        myLogin = Button.create(CodexLocalize.buttonLogIn(), event -> loginWithChatGpt());
        myApiKeyLogin = Button.create(CodexLocalize.buttonApiKeyLogIn(), event -> loginWithApiKey());
        myLogout = Button.create(CodexLocalize.buttonLogOut(), event -> logout());
        myRoot.add(DockLayout.create().left(Label.create(CodexLocalize.labelCommandLine())).right(myStatus));
        myRoot.add(DockLayout.create().right(HorizontalLayout.create().add(myLogin).add(myApiKeyLogin).add(myLogout)));

        Disposer.register(uiDisposable, () -> {
            myDisposed = true;
            myServer.thenAccept(CodexAppServer::close);
        });

        refresh();
    }

    Component getComponent() {
        return myRoot;
    }

    @RequiredUIAccess
    private void refresh() {
        setBusy(true);
        myServer.thenCompose(CodexAppServer::readAccount).whenCompleteAsync((account, error) -> {
            if (myDisposed) {
                return;
            }
            setBusy(false);
            if (account == null) {
                myStatus.setText(CodexLocalize.statusCannotRead(message(error)));
                return;
            }
            if (!account.signedIn()) {
                myStatus.setText(CodexLocalize.statusNotSignedIn());
            }
            else if (account.email() != null) {
                myStatus.setText(CodexLocalize.statusSignedInAs(account.email(), account.plan() == null ? account.type() : account.plan()));
            }
            else {
                myStatus.setText(CodexLocalize.statusSignedIn(account.type()));
            }
            myLogout.setEnabled(account.signedIn());
        }, myUIAccess);
    }

    @RequiredUIAccess
    private void loginWithChatGpt() {
        setBusy(true);
        myStatus.setText(CodexLocalize.statusWaitingBrowser());
        myServer.thenCompose(CodexAppServer::loginWithChatGpt).thenCompose(login -> {
            Platform.current().openInBrowser(login.getKey());
            return login.getValue();
        }).whenCompleteAsync((done, error) -> loginFinished(error), myUIAccess);
    }

    @RequiredUIAccess
    private void loginWithApiKey() {
        InputBoxBuilder.password()
            .title(CodexLocalize.dialogApiKeyTitle())
            .text(CodexLocalize.dialogApiKeyPrompt())
            .showAsync(myRoot)
            .whenCompleteAsync((apiKey, error) -> {
                if (error != null || apiKey == null || apiKey.isBlank()) {
                    return;
                }
                setBusy(true);
                myServer.thenCompose(server -> server.loginWithApiKey(apiKey.strip()))
                    .whenCompleteAsync((result, loginError) -> loginFinished(loginError), myUIAccess);
            }, myUIAccess);
    }

    @RequiredUIAccess
    private void logout() {
        setBusy(true);
        myServer.thenCompose(CodexAppServer::logout).whenCompleteAsync((result, error) -> loginFinished(error), myUIAccess);
    }

    @RequiredUIAccess
    private void loginFinished(@Nullable Throwable error) {
        if (myDisposed) {
            return;
        }
        // the app servers of the accounts read the login when they start
        myType.stopServers();
        if (error != null) {
            setBusy(false);
            myStatus.setText(CodexLocalize.statusSignInFailed(message(error)));
            return;
        }
        refresh();
    }

    @RequiredUIAccess
    private void setBusy(boolean busy) {
        myLogin.setEnabled(!busy);
        myApiKeyLogin.setEnabled(!busy);
        myLogout.setEnabled(!busy);
    }

    private static String message(@Nullable Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause == null ? "" : String.valueOf(cause.getMessage());
    }
}
