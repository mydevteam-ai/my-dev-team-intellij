/** The Settings page (Settings > Tools > My Dev Team). */
package ai.mydevteam.intellij.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class DevTeamConfigurable : Configurable {
    private var panel: JPanel? = null
    private val nodePath = JBTextField()
    private val sidecarScript = JBTextField()
    private val ollamaEndpoint = JBTextField()
    private val model = JBTextField()

    override fun getDisplayName(): String = "My Dev Team"

    override fun createComponent(): JComponent {
        val built = FormBuilder.createFormBuilder()
            .addLabeledComponent("Node.js executable:", nodePath)
            .addTooltip("Used to run the engine sidecar; \"node\" resolves from PATH")
            .addLabeledComponent("Sidecar script (blank = bundled):", sidecarScript)
            .addTooltip("Path to dist/sidecar-stdio.js from the engine build")
            .addLabeledComponent("Ollama endpoint (blank = default):", ollamaEndpoint)
            .addTooltip("Origin only, e.g. http://localhost:11434")
            .addLabeledComponent("Model:", model)
            .addTooltip("A model id, provider:<name>, or auto")
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = DevTeamSettings.instance().state
        return nodePath.text != s.nodePath ||
            sidecarScript.text != s.sidecarScript ||
            ollamaEndpoint.text != s.ollamaEndpoint ||
            model.text != s.model
    }

    override fun apply() {
        val s = DevTeamSettings.instance().state
        s.nodePath = nodePath.text.trim().ifBlank { "node" }
        s.sidecarScript = sidecarScript.text.trim()
        s.ollamaEndpoint = ollamaEndpoint.text.trim()
        s.model = model.text.trim().ifBlank { "auto" }
    }

    override fun reset() {
        val s = DevTeamSettings.instance().state
        nodePath.text = s.nodePath
        sidecarScript.text = s.sidecarScript
        ollamaEndpoint.text = s.ollamaEndpoint
        model.text = s.model
    }

    override fun disposeUIResources() {
        panel = null
    }
}
