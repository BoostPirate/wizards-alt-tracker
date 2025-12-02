package com.boostpirate.wizardsalttracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.io.IOException;
import java.time.Instant;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
        name = "Wizards Alt Tracker",
        description = "Tracks total coins (inventory + bank) on mule accounts and sends updates to a remote endpoint.",
        tags = {"mule", "tracker", "gp", "logging"}
)
public class WizardsAltTrackerPlugin extends Plugin
{
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    // To avoid spam: only send if change >= this and at least this many ms since last send
    private static final long MIN_CHANGE_FOR_UPDATE = 1_000_000L;      // 1m gp
    private static final long MIN_MILLIS_BETWEEN_UPDATES = 5_000L;     // 5 seconds

    @Inject
    private Client client;

    @Inject
    private WizardsAltTrackerConfig config;

    @Inject
    private OkHttpClient httpClient;

    // ✅ Use injected Gson instead of new Gson()
    @Inject
    private Gson gson;

    private long lastSentTotalCoins = -1;
    private long lastSentAtMillis = 0L;

    @Provides
    WizardsAltTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WizardsAltTrackerConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        log.info("Wizards Alt Tracker started");
        resetState();
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Wizards Alt Tracker stopped");
        resetState();
    }

    private void resetState()
    {
        lastSentTotalCoins = -1;
        lastSentAtMillis = 0L;
    }

    private boolean isPluginActiveForCurrentAccount()
    {
        if (!config.enabledForThisAccount())
        {
            return false;
        }

        String cfg = config.muleRsns().trim();
        if (cfg.isEmpty())
        {
            // If no mule RSNs configured, run on any account
            return true;
        }

        Player local = client.getLocalPlayer();
        if (local == null || local.getName() == null)
        {
            return false;
        }

        String localName = local.getName()
                .replace('\u00A0', ' ')
                .toLowerCase()
                .trim();

        String[] names = cfg.split(",");
        for (String raw : names)
        {
            String n = raw.replace('\u00A0', ' ').toLowerCase().trim();
            if (!n.isEmpty() && localName.equals(n))
            {
                return true;
            }
        }

        return false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN
                || event.getGameState() == GameState.HOPPING)
        {
            resetState();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!isPluginActiveForCurrentAccount())
        {
            return;
        }

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }

        long totalCoins = getTotalCoins();
        if (totalCoins < 0)
        {
            return;
        }

        long now = System.currentTimeMillis();

        // First value after login – send once
        if (lastSentTotalCoins == -1)
        {
            log.debug("Initial mule coin total for this session: {}", totalCoins);
            sendBalanceUpdate(totalCoins);
            lastSentTotalCoins = totalCoins;
            lastSentAtMillis = now;
            return;
        }

        long diff = Math.abs(totalCoins - lastSentTotalCoins);

        if (diff >= MIN_CHANGE_FOR_UPDATE && (now - lastSentAtMillis) >= MIN_MILLIS_BETWEEN_UPDATES)
        {
            log.debug("Mule coins changed: old={} new={} diff={}", lastSentTotalCoins, totalCoins, diff);
            sendBalanceUpdate(totalCoins);
            lastSentTotalCoins = totalCoins;
            lastSentAtMillis = now;
        }
    }

    /**
     * Returns total coins in inventory + bank.
     * Bank values update whenever the bank has been opened.
     */
    private long getTotalCoins()
    {
        long total = 0;

        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv != null)
        {
            total += countCoins(inv);
        }

        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank != null)
        {
            total += countCoins(bank);
        }

        return total;
    }

    private long countCoins(ItemContainer container)
    {
        long coins = 0;
        for (Item item : container.getItems())
        {
            if (item == null)
            {
                continue;
            }
            if (item.getId() == ItemID.COINS_995)
            {
                coins += item.getQuantity();
            }
        }
        return coins;
    }

    private void sendBalanceUpdate(long totalCoins)
    {
        String endpoint = config.endpointUrl().trim();
        if (endpoint.isEmpty())
        {
            log.warn("Wizards Alt Tracker: endpoint URL is empty, cannot send balance update");
            return;
        }

        Player local = client.getLocalPlayer();
        String localName = local != null ? local.getName() : "Unknown";
        String amountFormatted = String.format("%,d", totalCoins);
        String timestamp = Instant.now().toString();

        JsonObject payload = new JsonObject();

        // If the user drops a Discord webhook URL in here, send a human-readable message
        if (endpoint.contains("discord.com/api/webhooks"))
        {
            String content = String.format(
                    "**Mule Balance Update**\n`%s` now has **%s** gp (inv + bank)\n`%s`",
                    localName,
                    amountFormatted,
                    timestamp
            );
            payload.addProperty("content", content);
        }
        else
        {
            // Clean JSON for your Wizards Ltd backend
            payload.addProperty("rsn", localName);
            payload.addProperty("totalCoins", totalCoins);
            payload.addProperty("timestamp", timestamp);
        }

        String json = gson.toJson(payload);
        log.debug("Sending mule balance payload: {}", json);

        RequestBody body = RequestBody.create(JSON_MEDIA, json);
        Request request = new Request.Builder()
                .url(endpoint)
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Wizards Alt Tracker: failed to POST balance update", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (response)
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("Wizards Alt Tracker: balance update POST failed, code={} body={}",
                                response.code(),
                                response.body() != null ? response.body().string() : "");
                    }
                    else
                    {
                        log.debug("Wizards Alt Tracker: balance update POST success");
                    }
                }
            }
        });
    }
}
