import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.wsl.api {
    requires transitive consulo.ui.api;
    requires consulo.logging.api;

    exports consulo.wsl;
    exports consulo.wsl.agent;
    exports consulo.wsl.icon;
}
