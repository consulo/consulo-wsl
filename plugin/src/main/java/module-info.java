import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.wsl {
    requires transitive consulo.wsl.api;

    requires consulo.ide.api;
    requires consulo.application.api;
    requires consulo.component.api;
    requires consulo.configurable.api;
    requires consulo.container.api;
    requires consulo.disposer.api;
    requires consulo.http.api;
    requires consulo.logging.api;
    requires consulo.file.chooser.api;
    requires consulo.project.api;
    requires consulo.project.ui.api;
    requires consulo.ui.api;
    requires consulo.ui.ex.api;
    requires consulo.remote.server.api;
    requires consulo.base.icon.library;
    requires consulo.util.xml.serializer;

    opens consulo.wsl.impl to consulo.util.xml.serializer;
}
