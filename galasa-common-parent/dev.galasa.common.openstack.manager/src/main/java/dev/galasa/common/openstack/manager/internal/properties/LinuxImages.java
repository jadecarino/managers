package dev.galasa.common.openstack.manager.internal.properties;

import java.util.List;

import javax.validation.constraints.NotNull;

import dev.galasa.common.linux.OperatingSystem;
import dev.galasa.common.openstack.manager.OpenstackManagerException;
import dev.galasa.framework.spi.ConfigurationPropertyStoreException;
import dev.galasa.framework.spi.cps.CpsProperties;

/**
 * OpenStack Linux images
 * <p>
 * A comma separated list of what images are available to build servers from. 
 * </p><p>
 * The cascading properties can be:-<br><br>
 * openstack.linux.[os].[version].images=ubuntu-withjava,ubuntu-k8s<br>
 * openstack.linux.[os].images=ubuntu-withjava,ubuntu-k8s<br>
 * openstack.linux.images=ubuntu-withjava,ubuntu-k8s<br>
 * Where os = the operating system {@link OperatingSystem} and version is version string<br>
 * Example openstack.linux.ubuntu.16-04.images=ubuntu-1604
 * </p>
 * <p>
 * There are no defaults
 * </p>
 * 
 * @author Michael Baylis
 *
 */
public class LinuxImages extends CpsProperties {
	
	public static @NotNull List<String> get(
			@NotNull OperatingSystem operatingSystem, 
			String version) 
					throws ConfigurationPropertyStoreException, OpenstackManagerException {
		
		if (version != null) {
			return getStringList(OpenstackPropertiesSingleton.cps(), 
		               "linux", 
		               "images",
		               operatingSystem.name(),
		               version);
		}
		
		return getStringList(OpenstackPropertiesSingleton.cps(), 
	               "linux", 
	               "images",
	               operatingSystem.name());
		
		
		
	}

}