package consulo.wsl.terminal;

import consulo.annotation.component.ExtensionImpl;
import consulo.application.platform.PlatformTargetManager;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnSeparator;
import consulo.wsl.WslDistributions;
import consulo.wsl.agent.WslExe;
import jakarta.inject.Inject;
import org.jetbrains.plugins.terminal.ui.OpenPredefinedTerminalActionProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ExtensionImpl
public final class WslTerminalActionProvider implements OpenPredefinedTerminalActionProvider {
    private final Project myProject;
    private final PlatformTargetManager myTargetManager;

    @Inject
    public WslTerminalActionProvider(Project project, PlatformTargetManager targetManager) {
        myProject = project;
        myTargetManager = targetManager;
    }

    @Override
    public List<AnAction> listOpenPredefinedTerminalActions() {
        Path executable = WslExe.findExecutable();
        if (executable == null) {
            return List.of();
        }

        List<String> distributions = WslDistributions.list();
        if (distributions.isEmpty()) {
            return List.of();
        }

        List<AnAction> actions = new ArrayList<>();
        actions.add(AnSeparator.create(LocalizeValue.of("WSL")));
        for (String distribution : distributions) {
            actions.add(new OpenWslTerminalAction(myProject, myTargetManager.getLocalTarget(), executable, distribution));
        }
        return actions;
    }
}
