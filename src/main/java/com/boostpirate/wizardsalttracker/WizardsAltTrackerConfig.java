package com.boostpirate.wizardsalttracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("wizardsalttracker")
public interface WizardsAltTrackerConfig extends Config
{
    @ConfigItem(
            keyName = "enabledForThisAccount",
            name = "Enable on this account",
            description = "Turn Wizards Alt Tracker on/off for this RuneLite profile."
    )
    default boolean enabledForThisAccount()
    {
        return true;
    }

    @ConfigItem(
            keyName = "endpointUrl",
            name = "Endpoint / Webhook URL",
            description = "HTTP endpoint to send mule balance updates to (e.g., your Wizards Ltd API or a Discord webhook while testing)."
    )
    default String endpointUrl()
    {
        return "";
    }

    @ConfigItem(
            keyName = "muleRsns",
            name = "Mule RSNs (comma-separated)",
            description = "Optional: list of mule RSNs. If set, the plugin only runs while logged into these accounts."
    )
    default String muleRsns()
    {
        return "";
    }
}
