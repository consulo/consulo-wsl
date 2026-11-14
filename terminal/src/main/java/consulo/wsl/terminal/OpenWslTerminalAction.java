package consulo.wsl.terminal;

import consulo.application.dumb.DumbAware;
import consulo.application.platform.PlatformTarget;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.AnAction;
import consulo.ui.ex.action.AnActionEvent;
import consulo.wsl.WslDistributionIcons;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

import java.nio.file.Path;
import java.util.List;

public final class OpenWslTerminalAction extends AnAction implements DumbAware {
    private final Project myProject;
    private final PlatformTarget myLocalTarget;
    private final Path myExecutable;
    private final String myDistribution;

    public OpenWslTerminalAction(Project project, PlatformTarget localTarget, Path executable, String distribution) {
        super(LocalizeValue.of(distribution), LocalizeValue.empty(), WslDistributionIcons.forDistribution(distribution));
        myProject = project;
        myLocalTarget = localTarget;
        myExecutable = executable;
        myDistribution = distribution;
    }

    @RequiredUIAccess
    @Override
    public void actionPerformed(AnActionEvent e) {
        myProject.getInstance(TerminalToolWindowManager.class)
            .createNewSession(myLocalTarget, null, List.of(myExecutable.toString(), "-d", myDistribution), myDistribution);
    }
}
