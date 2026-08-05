package com.youkeda.project.wechatproject.bot.artifact;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface ArtifactStore {

    ArtifactRef put(ArtifactWriteRequest request) throws IOException;

    Optional<ArtifactRef> find(String artifactId);

    byte[] read(String artifactId) throws IOException;

    ArtifactRef updateStatus(String artifactId, ArtifactStatus status);

    List<ArtifactRef> findByRun(String runId);

    void expireOlderRevisions(String runId, String nodeId, int currentRevision);
}
