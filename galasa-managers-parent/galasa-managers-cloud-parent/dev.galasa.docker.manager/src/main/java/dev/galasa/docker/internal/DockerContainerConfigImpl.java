package dev.galasa.docker.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import dev.galasa.docker.IDockerContainerConfig;
import dev.galasa.docker.IDockerVolume;

/**
 * Implementation for the object that represents the container configurations that can be edited for container startup
 * 
 * @author James Davies
 */
public class DockerContainerConfigImpl implements IDockerContainerConfig {
    private List<IDockerVolume> volumes = new ArrayList<>();
    private HashMap<String,String> envs = new HashMap<>();

    /**
     * Consturctors that sets all requested volumes to the config
     * 
     * @param volumes
     */
    public DockerContainerConfigImpl(List<IDockerVolume> volumes) {
        this.volumes = volumes;
    }

    /**
     * Sets environment variables
     * 
     * @param envs
     */
    @Override
    public void setEnvs(HashMap<String, String> envs) {
       this.envs = envs;
    }

    /**
     * Returns set Environment variables for this configuration.
     * 
     * @return envs
     */
    @Override
    public HashMap<String,String> getEnvs() {
        return this.envs;
    }

    /**
     * Retruns a list of all the volumes in this configuration
     * 
     * @return volumes
     */
    @Override
    public List<IDockerVolume> getVolumes() {
        return this.volumes;
    }
    
}