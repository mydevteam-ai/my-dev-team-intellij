/**
 * The user's plugin settings, persisted application-wide. The IntelliJ
 * counterpart of the VS Code extension's `myDevTeam.*` settings - only the
 * subset Phase 1 needs; the rest arrives with the features that read them.
 */
package ai.mydevteam.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(name = "MyDevTeamSettings", storages = [Storage("myDevTeam.xml")])
class DevTeamSettings : PersistentStateComponent<DevTeamSettings.State> {
    class State {
        /** The Node.js executable used to spawn the engine sidecar. */
        var nodePath: String = "node"

        /** Path to dist/sidecar-stdio.js; empty uses the bundle shipped inside the plugin. */
        var sidecarScript: String = ""

        /** Base URL of the Ollama server; empty falls back to the engine default. */
        var ollamaEndpoint: String = ""

        /** The work-agent model choice: a model id, "provider:<name>", or "auto". */
        var model: String = "auto"
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun instance(): DevTeamSettings =
            ApplicationManager.getApplication().getService(DevTeamSettings::class.java)
    }
}
