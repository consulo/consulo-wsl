package consulo.wsl.impl;

import consulo.annotation.component.ExtensionImpl;
import consulo.localize.LocalizeValue;
import consulo.project.ui.notification.NotificationGroup;
import consulo.project.ui.notification.NotificationGroupContributor;

import java.util.function.Consumer;

@ExtensionImpl
public final class WslNotifications implements NotificationGroupContributor {
    public static final NotificationGroup GROUP = NotificationGroup.balloonGroup("WSL", LocalizeValue.of("WSL"));

    @Override
    public void contribute(Consumer<NotificationGroup> registrator) {
        registrator.accept(GROUP);
    }
}
