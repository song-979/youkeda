package com.youkeda.project.wechatproject.bot.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.artifacts")
public class ArtifactProperties {

    private String indexPath = "data/artifacts/index.db";
    private String blobPath = "data/artifacts/blobs";

    public String getIndexPath() { return indexPath; }
    public void setIndexPath(String indexPath) { this.indexPath = indexPath; }
    public String getBlobPath() { return blobPath; }
    public void setBlobPath(String blobPath) { this.blobPath = blobPath; }
}
