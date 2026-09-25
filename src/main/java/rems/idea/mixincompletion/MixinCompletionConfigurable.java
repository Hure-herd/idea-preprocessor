package rems.idea.mixincompletion;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

public final class MixinCompletionConfigurable implements Configurable {
    private JCheckBox autoInsert;

    @Override
    public @Nls String getDisplayName() {
        return "Preprocessor Support";
    }

    @Override
    public @Nullable JComponent createComponent() {
        autoInsert = new JCheckBox("在主版本不生效的预处理分支中换行时自动插入 //$$");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(autoInsert, BorderLayout.NORTH);
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return autoInsert != null
                && autoInsert.isSelected()
                != MixinCompletionSettings.getInstance().isAutoInsertPreprocessorComment();
    }

    @Override
    public void apply() {
        if (autoInsert != null) {
            MixinCompletionSettings.getInstance()
                    .setAutoInsertPreprocessorComment(autoInsert.isSelected());
        }
    }

    @Override
    public void reset() {
        if (autoInsert != null) {
            autoInsert.setSelected(MixinCompletionSettings.getInstance()
                    .isAutoInsertPreprocessorComment());
        }
    }

    @Override
    public void disposeUIResources() {
        autoInsert = null;
    }
}
