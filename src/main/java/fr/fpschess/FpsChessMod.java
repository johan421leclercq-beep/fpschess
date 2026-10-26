package fr.fpschess;

import com.github.bhlangonijr.chesslib.Square;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class FpsChessMod implements ModInitializer {

    public static final String MOD_ID = "fpschess";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Une seule partie pour l'instant.
    private static final ChessGame GAME = new ChessGame();
    private static BoardRenderer renderer = null; // null tant que /fpschess board n'a pas été lancé
    private static ClickHandler handler = null;
    private static boolean protection = true; // empêche de casser ou construire sur le plateau et l'arène

    private static boolean isOp(ServerCommandSource src) {
        return src.hasPermissionLevel(2);
    }

    private static boolean duelBusy(ServerCommandSource src) {
        if (DuelManager.isActive()) {
            src.sendError(Text.literal("Un duel est en cours."));
            return true;
        }
        return false;
    }

    /** Bouton cliquable dans le chat. */
    private static Text button(String label, String command, Formatting color, String hover) {
        return Text.literal(label).styled(s -> s
            .withColor(color)
            .withBold(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
    }

    private static Text modeMenu() {
        return Text.literal("Plateau prêt ! Comment veux-tu jouer ?  ").formatted(Formatting.GOLD)
            .append(button("[Jouer seul (test)]", "/fpschess mode solo", Formatting.GREEN,
                "Tu joues les deux camps. Les duels se font contre un zombie équipé."))
            .append(Text.literal("  "))
            .append(button("[Jouer avec un ami]", "/fpschess mode friend", Formatting.AQUA,
                "Tu joues les Blancs, ton ami rejoint les Noirs."));
    }

    @Override
    public void onInitialize() {
        GAME.reset();

        // Clic droit sur le sol d'une case (et protection contre la construction)
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || renderer == null || world != renderer.world()) {
                return ActionResult.PASS;
            }
            Square sq = renderer.squareAt(hit.getBlockPos());
            if (sq != null) {
                if (hand == Hand.MAIN_HAND && handler != null && player instanceof ServerPlayerEntity sp) {
                    handler.onClick(sp, sq);
                }
                return ActionResult.SUCCESS; // jamais de bloc posé sur le plateau
            }
            if (protection && (renderer.isProtected(hit.getBlockPos())
                    || renderer.isProtected(hit.getBlockPos().offset(hit.getSide())))) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Interdit de casser le plateau et l'arène
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (protection && renderer != null && world == renderer.world() && renderer.isProtected(pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Clic droit directement sur une pièce
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || renderer == null || handler == null) {
                return ActionResult.PASS;
            }
            if (!(player instanceof ServerPlayerEntity sp)) {
                return ActionResult.PASS;
            }
            for (String tag : entity.getCommandTags()) {
                if (tag.startsWith(BoardRenderer.SQ_TAG_PREFIX)) {
                    String name = tag.substring(BoardRenderer.SQ_TAG_PREFIX.length());
                    handler.onClick(sp, Square.fromValue(name.toUpperCase(Locale.ROOT)));
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // Duels
        ServerTickEvents.END_SERVER_TICK.register(DuelManager::tick);
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> DuelManager.allowDeath(entity));
        ServerPlayConnectionEvents.DISCONNECT.register((netHandler, server) -> DuelManager.onDisconnect(netHandler.player));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("fpschess")

                // /fpschess board : construit le plateau et l'arène, puis propose le choix du mode
                .then(CommandManager.literal("board").requires(FpsChessMod::isOp).executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    if (duelBusy(src)) return 0;
                    ServerPlayerEntity player = src.getPlayerOrThrow();
                    ServerWorld world = src.getWorld();
                    BlockPos p = player.getBlockPos();

                    if (renderer != null) renderer.removeAll();

                    int size = 8 * BoardRenderer.CELL;
                    int x0 = p.getX() - size / 2;
                    int z0 = p.getZ() - size - 3;
                    int y0 = p.getY() - 1;

                    renderer = new BoardRenderer(src.getServer(), world, x0, y0, z0);
                    renderer.buildBoard();
                    renderer.buildArena();
                    renderer.renderPieces(GAME.board());
                    handler = new ClickHandler(GAME, renderer);
                    src.sendFeedback(FpsChessMod::modeMenu, false);
                    return 1;
                }))

                // /fpschess mode solo | friend
                .then(CommandManager.literal("mode").requires(FpsChessMod::isOp)
                    .then(CommandManager.literal("solo").executes(ctx -> {
                        if (handler == null) {
                            ctx.getSource().sendError(Text.literal("Crée d'abord le plateau avec /fpschess board."));
                            return 0;
                        }
                        if (duelBusy(ctx.getSource())) return 0;
                        handler.setSolo(ctx.getSource().getPlayerOrThrow());
                        ctx.getSource().sendFeedback(() -> Text.literal(
                            "Mode solo : tu joues les deux camps. Les duels se font contre un zombie."
                            + " (La difficulté ne doit pas être en pacifique : /difficulty normal)")
                            .formatted(Formatting.GREEN), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("friend").executes(ctx -> {
                        if (handler == null) {
                            ctx.getSource().sendError(Text.literal("Crée d'abord le plateau avec /fpschess board."));
                            return 0;
                        }
                        if (duelBusy(ctx.getSource())) return 0;
                        ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
                        handler.setFriend(me);
                        Text msg = Text.literal(me.getName().getString() + " joue les Blancs.  ")
                            .formatted(Formatting.AQUA)
                            .append(button("[Rejoindre en Noir]", "/fpschess join", Formatting.YELLOW,
                                "Clique pour jouer les Noirs"));
                        ctx.getSource().getServer().getPlayerManager().broadcast(msg, false);
                        return 1;
                    })))

                // /fpschess join : ouvert à tous
                .then(CommandManager.literal("join").executes(ctx -> {
                    if (handler == null) {
                        ctx.getSource().sendError(Text.literal("Aucune partie en cours."));
                        return 0;
                    }
                    ServerPlayerEntity me = ctx.getSource().getPlayerOrThrow();
                    String err = handler.join(me);
                    if (err != null) {
                        ctx.getSource().sendError(Text.literal(err));
                        return 0;
                    }
                    ctx.getSource().getServer().getPlayerManager().broadcast(
                        Text.literal(me.getName().getString() + " joue les Noirs. Que la partie commence !")
                            .formatted(Formatting.GREEN), false);
                    return 1;
                }))

                // /fpschess resign : abandonner la partie (ouvert à tous)
                .then(CommandManager.literal("resign").executes(ctx -> {
                    if (handler == null) {
                        ctx.getSource().sendError(Text.literal("Aucune partie en cours."));
                        return 0;
                    }
                    String err = handler.resign(ctx.getSource().getPlayerOrThrow());
                    if (err != null) {
                        ctx.getSource().sendError(Text.literal(err));
                        return 0;
                    }
                    return 1;
                }))

                // /fpschess protection on|off
                .then(CommandManager.literal("protection").requires(FpsChessMod::isOp)
                    .then(CommandManager.literal("on").executes(ctx -> {
                        protection = true;
                        ctx.getSource().sendFeedback(() -> Text.literal("Protection du plateau et de l'arène : activée."), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("off").executes(ctx -> {
                        protection = false;
                        ctx.getSource().sendFeedback(() -> Text.literal("Protection du plateau et de l'arène : désactivée."), false);
                        return 1;
                    })))

                .then(CommandManager.literal("clear").requires(FpsChessMod::isOp).executes(ctx -> {
                    if (duelBusy(ctx.getSource())) return 0;
                    if (renderer != null) {
                        renderer.removeAll();
                        renderer = null;
                        handler = null;
                    }
                    ctx.getSource().sendFeedback(() -> Text.literal("Plateau supprimé."), false);
                    return 1;
                }))

                .then(CommandManager.literal("new").requires(FpsChessMod::isOp).executes(ctx -> {
                    if (duelBusy(ctx.getSource())) return 0;
                    GAME.reset();
                    if (renderer != null) {
                        handler.newGame();
                        renderer.renderPieces(GAME.board());
                    }
                    ctx.getSource().sendFeedback(() -> Text.literal("Nouvelle partie."), false);
                    return 1;
                }))

                .then(CommandManager.literal("show").requires(FpsChessMod::isOp).executes(ctx -> {
                    ctx.getSource().sendFeedback(
                        () -> Text.literal(GAME.asciiBoard() + "\n" + GAME.statusText()), false);
                    return 1;
                }))

                // /fpschess move e2e4 : test du moteur, sans duel
                .then(CommandManager.literal("move").requires(FpsChessMod::isOp)
                    .then(CommandManager.argument("coup", StringArgumentType.word()).executes(ctx -> {
                        if (duelBusy(ctx.getSource())) return 0;
                        String text = StringArgumentType.getString(ctx, "coup");
                        MoveResult r = GAME.tryMove(text);
                        if (!r.legal()) {
                            ctx.getSource().sendError(Text.literal(r.message()));
                            return 0;
                        }
                        GAME.applyMove(r.move());
                        if (renderer != null) {
                            handler.reset();
                            renderer.renderPieces(GAME.board());
                        }
                        String out = GAME.asciiBoard() + "\n" + GAME.statusText();
                        ctx.getSource().sendFeedback(() -> Text.literal(out), true);
                        return 1;
                    })))
            )
        );

        LOGGER.info("FPS Chess chargé.");
    }
}
