package com.jdeiss.scriptpacks.handlers;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.rpc.RpcDispatcher;

public final class CommandHandler {

    private CommandHandler() {}

    public static void register(RpcDispatcher dispatcher) {
        // runCommand — fallback for everything not worth wrapping
        dispatcher.registerMutating("runCommand", (server, params) -> {
            String command = params.get("command").getAsString();
            var source = server.createCommandSourceStack();
            server.getCommands().performPrefixedCommand(source, command);
            return JsonNull.INSTANCE;
        });
    }
}
