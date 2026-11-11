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

import consulo.ai.AIProvider;
import consulo.codex.localize.CodexLocalize;
import consulo.localize.LocalizeValue;

import java.util.Map;

/**
 * When Codex asks before it acts, and where its sandbox lets commands write - the approval policy and the sandbox
 * mode of its threads. Kept as the {@value #OPTION} option of the account.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
enum CodexPermissionMode {
    ON_REQUEST("onRequest", CodexLocalize.permissionModeOnRequest(), "on-request", "workspace-write"),
    UNTRUSTED("untrusted", CodexLocalize.permissionModeUntrusted(), "untrusted", "workspace-write"),
    READ_ONLY("readOnly", CodexLocalize.permissionModeReadOnly(), "on-request", "read-only"),
    FULL_ACCESS("fullAccess", CodexLocalize.permissionModeFullAccess(), "never", "danger-full-access");

    static final String OPTION = "codex.permission";

    private final String myId;
    private final LocalizeValue myDisplayName;
    private final String myApprovalPolicy;
    private final String mySandbox;

    CodexPermissionMode(String id, LocalizeValue displayName, String approvalPolicy, String sandbox) {
        myId = id;
        myDisplayName = displayName;
        myApprovalPolicy = approvalPolicy;
        mySandbox = sandbox;
    }

    LocalizeValue getDisplayName() {
        return myDisplayName;
    }

    String getApprovalPolicy() {
        return myApprovalPolicy;
    }

    String getSandbox() {
        return mySandbox;
    }

    void store(Map<String, String> options) {
        options.put(OPTION, myId);
    }

    static CodexPermissionMode of(AIProvider provider) {
        return of(provider.getOptions());
    }

    static CodexPermissionMode of(Map<String, String> options) {
        String id = options.get(OPTION);
        for (CodexPermissionMode mode : values()) {
            if (mode.myId.equals(id)) {
                return mode;
            }
        }
        return ON_REQUEST;
    }
}
