package consulo.wsl.impl;

import consulo.application.concurrent.ApplicationConcurrency;
import consulo.configurable.ConfigurationException;
import consulo.disposer.Disposable;
import consulo.fileChooser.FileChooserDescriptorFactory;
import consulo.fileChooser.FileChooserTextBoxBuilder;
import consulo.localize.LocalizeValue;
import consulo.remoteServer.RemoteServerConfigurable;
import consulo.remoteServer.platformAware.PredefinedBundlesMode;
import consulo.ui.*;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.model.FlatDataModel;
import consulo.ui.model.MutableFlatDataModel;
import consulo.ui.util.FormBuilder;
import consulo.wsl.WslDistributionIcons;
import consulo.wsl.WslDistributions;
import consulo.wsl.agent.WslAgentDeployer;
import consulo.wsl.agent.WslExe;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

final class WslServerConfigurable extends RemoteServerConfigurable {
    private final WslServerConfiguration myConfiguration;
    private final ApplicationConcurrency myConcurrency;

    private @Nullable MutableFlatDataModel<String> myDistributionModel;
    private @Nullable ComboBox<String> myDistribution;
    private @Nullable TextBox myUser;
    private @Nullable CheckBox myDownloadAgent;
    private @Nullable TextBox myAgentPath;
    private @Nullable IntBox myPort;
    private @Nullable TextBox myPermissions;
    private @Nullable ComboBox<PredefinedBundlesMode> myPredefinedBundles;
    private @Nullable CheckBox myReconnect;
    private @Nullable CompletableFuture<?> myListing;

    WslServerConfigurable(WslServerConfiguration configuration, ApplicationConcurrency concurrency) {
        myConfiguration = configuration;
        myConcurrency = concurrency;
    }

    @Override
    public boolean canCheckConnection() {
        // a check deploys and starts the agent: only on an explicit connect
        return false;
    }

    @RequiredUIAccess
    @Override
    public Component createUIComponent(Disposable parentDisposable) {
        List<String> initial = new ArrayList<>();
        if (!myConfiguration.getDistribution().isEmpty()) {
            initial.add(myConfiguration.getDistribution());
        }
        MutableFlatDataModel<String> model = FlatDataModel.of(initial);
        myDistributionModel = model;
        myDistribution = ComboBox.create(model);
        myDistribution.setRenderer((presentation, item) -> {
            String distribution = item.getValue();
            if (distribution != null) {
                presentation.withIcon(WslDistributionIcons.forDistribution(distribution));
                presentation.append(distribution);
            }
        });

        myUser = TextBox.create();
        myUser.setPlaceholder(LocalizeValue.localizeTODO("Default user of the distribution"));

        myDownloadAgent = CheckBox.create(LocalizeValue.localizeTODO("Download the remote agent automatically"));

        FileChooserTextBoxBuilder chooser = FileChooserTextBoxBuilder.create(null);
        chooser.uiDisposable(parentDisposable);
        chooser.fileChooserDescriptor(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor());
        chooser.dialogTitle(LocalizeValue.localizeTODO("Select Linux Remote Agent"));
        TextBox agentPath = chooser.build().getComponent();
        agentPath.setPlaceholder(LocalizeValue.localizeTODO("Linux remote-agent executable on Windows"));
        myAgentPath = agentPath;
        myDownloadAgent.addValueListener(event -> agentPath.setEnabled(!Boolean.TRUE.equals(event.getValue())));

        myPort = IntBox.create(0);
        myPermissions = TextBox.create();
        myPermissions.setPlaceholder(LocalizeValue.localizeTODO("* or comma-separated groups: fs,process,..."));

        ComboBox<PredefinedBundlesMode> predefinedBundles = ComboBox.create(PredefinedBundlesMode.values());
        predefinedBundles.setTextRenderer(value -> value == null ? LocalizeValue.empty() : value.getText());
        myPredefinedBundles = predefinedBundles;

        myReconnect = CheckBox.create(LocalizeValue.localizeTODO("Reconnect when the connection is lost"));

        reset();

        UIAccess uiAccess = UIAccess.current();
        myListing = CompletableFuture.supplyAsync(WslDistributions::list, myConcurrency.executor())
            .thenAccept(found -> uiAccess.give(() -> updateDistributions(model, found)));

        return FormBuilder.create()
            .addLabeled(LocalizeValue.localizeTODO("Distribution:"), myDistribution)
            .addLabeled(LocalizeValue.localizeTODO("Linux user:"), myUser)
            .addLabeled(LocalizeValue.localizeTODO("Agent:"), myDownloadAgent)
            .addLabeled(LocalizeValue.localizeTODO("Custom agent binary:"), agentPath)
            .addLabeled(LocalizeValue.localizeTODO("Port (0 - any free):"), myPort)
            .addLabeled(LocalizeValue.localizeTODO("Agent permissions:"), myPermissions)
            .addLabeled(LocalizeValue.localizeTODO("Detect SDKs:"), predefinedBundles)
            .addLabeled(LocalizeValue.localizeTODO("Always online:"), myReconnect)
            .build();
    }

    @RequiredUIAccess
    private void updateDistributions(MutableFlatDataModel<String> model, List<String> found) {
        if (myDistributionModel != model || myDistribution == null) {
            return;
        }
        String selected = myDistribution.getValue();
        List<String> items = new ArrayList<>(found);
        if (selected != null && !items.contains(selected)) {
            items.add(selected);
        }
        model.replaceAll(items);
        myDistribution.setValue(selected != null ? selected : items.isEmpty() ? null : items.getFirst());
    }

    @RequiredUIAccess
    @Override
    public boolean isModified() {
        if (myDistribution == null || myUser == null || myDownloadAgent == null || myAgentPath == null || myPort == null
            || myPermissions == null || myPredefinedBundles == null || myReconnect == null) {
            return false;
        }
        return !Objects.equals(Objects.toString(myDistribution.getValue(), ""), myConfiguration.getDistribution())
            || !Objects.equals(text(myUser), myConfiguration.getUser())
            || myDownloadAgent.getValueOrError() != myConfiguration.isDownloadAgent()
            || !Objects.equals(text(myAgentPath), myConfiguration.getAgentPath())
            || !Objects.equals(myPort.getValue(), myConfiguration.getPort())
            || !Objects.equals(text(myPermissions), myConfiguration.getPermissions())
            || myPredefinedBundles.getValue() != myConfiguration.getPredefinedBundlesMode()
            || myReconnect.getValueOrError() != myConfiguration.isReconnectAutomatically();
    }

    @RequiredUIAccess
    @Override
    public void reset() {
        if (myDistribution == null || myUser == null || myDownloadAgent == null || myAgentPath == null || myPort == null
            || myPermissions == null || myPredefinedBundles == null || myReconnect == null) {
            return;
        }
        myDistribution.setValue(myConfiguration.getDistribution().isEmpty() ? null : myConfiguration.getDistribution());
        myUser.setValue(myConfiguration.getUser());
        myDownloadAgent.setValue(myConfiguration.isDownloadAgent());
        myAgentPath.setValue(myConfiguration.getAgentPath());
        myAgentPath.setEnabled(!myConfiguration.isDownloadAgent());
        myPort.setValue(myConfiguration.getPort());
        myPermissions.setValue(myConfiguration.getPermissions());
        myPredefinedBundles.setValue(myConfiguration.getPredefinedBundlesMode());
        myReconnect.setValue(myConfiguration.isReconnectAutomatically());
    }

    @RequiredUIAccess
    @Override
    public void apply() throws ConfigurationException {
        if (myDistribution == null || myUser == null || myDownloadAgent == null || myAgentPath == null || myPort == null
            || myPermissions == null || myPredefinedBundles == null || myReconnect == null) {
            return;
        }
        String distribution = Objects.toString(myDistribution.getValue(), "");
        if (!WslExe.isValidDistributionName(distribution)) {
            throw new ConfigurationException(LocalizeValue.localizeTODO("Select a WSL distribution"));
        }
        String user = text(myUser);
        if (!WslExe.isValidUserName(user)) {
            throw new ConfigurationException(LocalizeValue.localizeTODO("Invalid Linux user name: " + user));
        }
        boolean download = myDownloadAgent.getValueOrError();
        String binary = text(myAgentPath);
        if (!download) {
            try {
                if (!Files.isRegularFile(Path.of(binary))) {
                    throw new InvalidPathException(binary, "Not a file");
                }
            }
            catch (InvalidPathException e) {
                throw new ConfigurationException(LocalizeValue.localizeTODO("Select the Linux remote-agent binary stored on Windows"));
            }
        }
        Integer port = myPort.getValue();
        if (port == null || port < 0 || port > 65535) {
            throw new ConfigurationException(LocalizeValue.localizeTODO("Port must be between 0 and 65535"));
        }
        String permissions;
        try {
            permissions = WslAgentDeployer.normalizePermissions(text(myPermissions));
        }
        catch (IllegalArgumentException e) {
            throw new ConfigurationException(LocalizeValue.localizeTODO(Objects.toString(e.getMessage(), "Invalid agent permissions")));
        }
        PredefinedBundlesMode predefinedBundles = myPredefinedBundles.getValue();

        myConfiguration.setDistribution(distribution);
        myConfiguration.setUser(user);
        myConfiguration.setDownloadAgent(download);
        myConfiguration.setAgentPath(binary);
        myConfiguration.setPort(port);
        myConfiguration.setPermissions(permissions);
        if (predefinedBundles != null) {
            myConfiguration.setPredefinedBundlesMode(predefinedBundles);
        }
        myConfiguration.setReconnectAutomatically(myReconnect.getValueOrError());
    }

    @RequiredUIAccess
    @Override
    public void disposeUIResources() {
        if (myListing != null) {
            myListing.cancel(false);
        }
        myListing = null;
        myDistributionModel = null;
        myDistribution = null;
        myUser = null;
        myDownloadAgent = null;
        myAgentPath = null;
        myPort = null;
        myPermissions = null;
        myPredefinedBundles = null;
        myReconnect = null;
    }

    private static String text(TextBox box) {
        return Objects.toString(box.getValue(), "").strip();
    }
}
