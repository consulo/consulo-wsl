package consulo.wsl.impl;

/**
 * Everything needed to deploy and start the agent in one distribution.
 *
 * @param user            Linux user, empty for the distribution default user
 * @param downloadAgent   {@code true} to use the published agent matching the distribution architecture
 * @param customAgentPath Linux ELF agent on the Windows disk, used when {@code downloadAgent} is off
 * @param port            Windows/WSL localhost port, {@code 0} to choose a free one
 * @param permissions     {@code *} or comma-separated agent permission groups
 */
public record WslAgentOptions(String distribution,
                              String user,
                              boolean downloadAgent,
                              String customAgentPath,
                              int port,
                              String permissions) {
}
