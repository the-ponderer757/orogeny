package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * Who may use Orogeny's teleport and debug commands (/orogeny-find, /orogeny-column): operators (permission level 2,
 * like /tp), the server console, and in singleplayer the world's owner even with cheats off (Calvin, Nov 2: on a
 * public server anyone could teleport anywhere with /orogeny-find).
 */
public final class CommandAccess {
    private static final Predicate<CommandSourceStack> OPERATORS = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private CommandAccess() {
    }

    public static boolean operatorOrOwner(CommandSourceStack source) {
        if (OPERATORS.test(source)) return true;
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayer()
                && source.getServer().isSingleplayerOwner(player.nameAndId());
    }
}
