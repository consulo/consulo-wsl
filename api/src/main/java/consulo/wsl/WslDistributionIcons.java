package consulo.wsl;

import consulo.ui.image.Image;
import consulo.wsl.icon.WslIconGroup;

import java.util.Locale;

/** Icons of registered distributions by their name ({@code Ubuntu-24.04}, {@code Ubuntu}, ...); generic Linux otherwise. */
public final class WslDistributionIcons {
    private WslDistributionIcons() {
    }

    public static Image forDistribution(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ubuntu")) {
            return WslIconGroup.ubuntu();
        }
        return WslIconGroup.linux();
    }
}
