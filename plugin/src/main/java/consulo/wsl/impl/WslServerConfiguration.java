package consulo.wsl.impl;

import consulo.remoteServer.configuration.ServerConfigurationBase;
import consulo.remoteServer.platformAware.PlatformAwareServerConfiguration;
import consulo.remoteServer.platformAware.PredefinedBundlesMode;
import consulo.util.xml.serializer.annotation.Attribute;

/**
 * A WSL distribution as a Clouds server: the agent is deployed and started on connect.
 */
public class WslServerConfiguration extends ServerConfigurationBase<WslServerConfiguration> implements PlatformAwareServerConfiguration {
    private String myDistribution = "";
    private String myUser = "";
    private boolean myDownloadAgent = true;
    private String myAgentPath = "";
    private int myPort;
    private String myPermissions = "*";
    private PredefinedBundlesMode myPredefinedBundlesMode = PredefinedBundlesMode.ON_FIRST_CONNECT;
    // relaunching the agent in a distribution is cheap: a lost connection (wsl --shutdown, sleep) comes back on its own
    private boolean myReconnectAutomatically = true;

    @Attribute("distribution")
    public String getDistribution() {
        return myDistribution;
    }

    public void setDistribution(String distribution) {
        myDistribution = distribution.strip();
    }

    @Attribute("user")
    public String getUser() {
        return myUser;
    }

    public void setUser(String user) {
        myUser = user.strip();
    }

    @Attribute("downloadAgent")
    public boolean isDownloadAgent() {
        return myDownloadAgent;
    }

    public void setDownloadAgent(boolean downloadAgent) {
        myDownloadAgent = downloadAgent;
    }

    @Attribute("agentPath")
    public String getAgentPath() {
        return myAgentPath;
    }

    public void setAgentPath(String agentPath) {
        myAgentPath = agentPath.strip();
    }

    @Attribute("port")
    public int getPort() {
        return myPort;
    }

    public void setPort(int port) {
        myPort = port;
    }

    @Attribute("permissions")
    public String getPermissions() {
        return myPermissions;
    }

    public void setPermissions(String permissions) {
        myPermissions = permissions.strip();
    }

    @Override
    @Attribute("predefinedBundles")
    public PredefinedBundlesMode getPredefinedBundlesMode() {
        return myPredefinedBundlesMode;
    }

    @Override
    public void setPredefinedBundlesMode(PredefinedBundlesMode mode) {
        myPredefinedBundlesMode = mode;
    }

    @Override
    @Attribute("reconnectAutomatically")
    public boolean isReconnectAutomatically() {
        return myReconnectAutomatically;
    }

    @Override
    public void setReconnectAutomatically(boolean reconnectAutomatically) {
        myReconnectAutomatically = reconnectAutomatically;
    }

    public WslAgentOptions toOptions() {
        return new WslAgentOptions(myDistribution, myUser, myDownloadAgent, myAgentPath, myPort, myPermissions);
    }
}
