/*
 * Licensed Materials - Property of IBM
 * 
 * (c) Copyright IBM Corp. 2019,2020.
 */
package dev.galasa.zosrseapi.internal.properties;

import javax.validation.constraints.NotNull;

import dev.galasa.framework.spi.cps.CpsProperties;
import dev.galasa.zosrseapi.RseapiManagerException;

/**
 * RSE API Server Image
 * 
 * @galasa.cps.property
 * 
 * @galasa.name rseapi.server.SERVERID.image
 * 
 * @galasa.description The z/OS image ID this RSE API server lives on 
 * 
 * @galasa.required No
 * 
 * @galasa.default The SERVERID value is used as the z/OS image ID
 * 
 * @galasa.valid_values  z/OS image IDs
 * 
 * @galasa.examples 
 * <code>rseapi.server.RSESYSA.image=SYSA</code><br>
 *
 */
public class ServerImage extends CpsProperties {

    public static @NotNull String get(@NotNull String serverId) throws RseapiManagerException {
        return getStringWithDefault(RseapiPropertiesSingleton.cps(), serverId, "server." + serverId, "image");
    }

}
