package jetbrains.buildServer.deployer.agent.smb;

import com.intellij.openapi.util.text.StringUtil;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jetbrains.buildServer.RunBuildException;
import jetbrains.buildServer.agent.BuildRunnerContext;
import jetbrains.buildServer.agent.impl.artifacts.ArtifactsCollection;
import jetbrains.buildServer.deployer.agent.SyncBuildProcessAdapter;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;


class SMBBuildProcessAdapter extends SyncBuildProcessAdapter {
    public static final String SMB = "smb://";

    private final String myTarget;
    private final String myUsername;
    private final String myPassword;
    private final List<ArtifactsCollection> myArtifactsCollections;
    private final String myDomain;

    public SMBBuildProcessAdapter(@NotNull final BuildRunnerContext context,
                                  @NotNull final String username,
                                  @NotNull final String password,
                                  @Nullable final String domain,
                                  @NotNull final String target,
                                  @NotNull final List<ArtifactsCollection> artifactsCollections) {
        super(context.getBuild().getBuildLogger());
        myTarget = target;
        myUsername = username;
        myPassword = password;
        myDomain = domain;
        myArtifactsCollections = artifactsCollections;
    }

    @Override
    public void runProcess() throws RunBuildException {

        jcifs.Config.setProperty("jcifs.smb.client.disablePlainTextPasswords", "false");

        String targetWithProtocol;
        if (myTarget.startsWith("\\\\")) {
            targetWithProtocol = SMB + myTarget.substring(2);
        } else if (!myTarget.startsWith(SMB)) {
            targetWithProtocol = SMB + myTarget;
        } else {
            targetWithProtocol = myTarget;
        }

        // Share and directories names require trailing /
        if (!targetWithProtocol.endsWith("/")) {
            targetWithProtocol = targetWithProtocol + "/";
        }

        targetWithProtocol = targetWithProtocol.replaceAll("\\\\", "/");

        NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication(myDomain == null ? "" : myDomain,
                myUsername, myPassword);

        final String settingsString = "Trying to connect with following parameters:\n" +
                "username=[" + myUsername + "]\n" +
                "domain=[" + (myDomain == null ? "" : myDomain) + "]\n" +
                "target=[" + targetWithProtocol + "]";
        try {
            Loggers.AGENT.debug(settingsString);
            myLogger.message("Starting upload via SMB to " + myTarget);
            SmbFile destinationDir = new SmbFile(targetWithProtocol, auth);

            for (ArtifactsCollection artifactsCollection : myArtifactsCollections) {
                final int numOfUploadedFiles = upload(artifactsCollection.getFilePathMap(), destinationDir);
                myLogger.message("Uploaded [" + numOfUploadedFiles + "] files for [" + artifactsCollection.getSourcePath() + "] pattern");
            }

        } catch (Exception e) {
            Loggers.AGENT.error(settingsString, e);
            throw new RunBuildException(e);
        }
    }

    private int upload(Map<File, String> filePathMap, SmbFile destination) throws IOException {
        int count = 0;
        for (Map.Entry<File, String> fileDestEntry : filePathMap.entrySet()) {
            final File source = fileDestEntry.getKey();
            final String targetPath = fileDestEntry.getValue();
            final SmbFile destDirectory;
            if (StringUtil.isEmpty(targetPath)) {
                destDirectory = destination;
            } else {
                destDirectory = new SmbFile(destination, targetPath + "/");
            }

            final SmbFile destFile = new SmbFile(destDirectory, source.getName());

            Loggers.AGENT.debug("Uploading source=[" + source.getAbsolutePath() + "] to \n" +
                    "destDirectory=[" + destDirectory.getCanonicalPath() +
                    "] destFile=[" +  destFile.getCanonicalPath() +"]");

            FileInputStream inputStream = null;
            OutputStream outputStream = null;

            try {
                if (!destDirectory.exists()) {
                    destDirectory.mkdirs();
                }
                inputStream = new FileInputStream(source);
                outputStream = destFile.getOutputStream();
                FileUtil.copy(inputStream, outputStream);
                outputStream.flush();
            } finally {
                FileUtil.close(inputStream);
                FileUtil.close(outputStream);
            }
            count++;
        }
        return count;
    }

}
