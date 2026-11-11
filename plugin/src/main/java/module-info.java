import org.jspecify.annotations.NullMarked;

/**
 * @author VISTALL
 * @since 2026-11-10
 */
@NullMarked
module consulo.codex {
    requires consulo.ai.api;
    requires consulo.ai.acp.api;
    requires consulo.process.api;
    requires consulo.ui.api;
    requires com.google.gson;
}
