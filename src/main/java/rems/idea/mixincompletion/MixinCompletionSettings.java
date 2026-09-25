package rems.idea.mixincompletion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@State(name = "MixinDescriptorCompletionSettings",
        storages = @Storage("mixinDescriptorCompletion.xml"))
public final class MixinCompletionSettings
        implements PersistentStateComponent<MixinCompletionSettings.SettingsState> {
    public static final class SettingsState {
        public boolean autoInsertPreprocessorComment = true;
    }

    private SettingsState state = new SettingsState();

    static MixinCompletionSettings getInstance() {
        return ApplicationManager.getApplication().getService(MixinCompletionSettings.class);
    }

    boolean isAutoInsertPreprocessorComment() {
        return state.autoInsertPreprocessorComment;
    }

    void setAutoInsertPreprocessorComment(boolean enabled) {
        state.autoInsertPreprocessorComment = enabled;
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }
}
