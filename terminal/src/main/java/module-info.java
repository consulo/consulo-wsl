import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.wsl.terminal {
    requires consulo.wsl.api;
    requires org.jetbrains.plugins.terminal.api;

    requires consulo.application.api;
    requires consulo.project.api;
    requires consulo.ui.ex.api;
}
