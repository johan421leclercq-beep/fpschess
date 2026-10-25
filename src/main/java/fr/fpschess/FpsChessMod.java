package fr.fpschess;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import com.github.bhlangonijr.chesslib.Square;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FpsChessMod implements ModInitializer {

    public static final String MOD_ID = "fpschess";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Une seule partie pour l'instant. Plusieurs parties viendront plus tard.
    private static final ChessGame GAME = new ChessGame();
    private static BoardRenderer renderer = null; // null tant que /fpschess board n'a pas été lancé
    private static ClickHandler handler = null;

    @Override
    public void onInitialize() {
        GAME.reset();

        // Clic droit sur une case du plateau
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || renderer == null || handler == null) {
                return ActionResult.PASS;
            }
            if (!(player instanceof ServerPlayerEntity sp) || world != renderer.world()) {
                return ActionResult.PASS;
            }
            Square sq = renderer.squareAt(hit.getBlockPos());
            if (sq == null) {
                return ActionResult.PASS;
            }
            handler.onClick(sp, sq);
            return ActionResult.SUCCESS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("fpschess")
                .requires(src -> src.hasPermissionLevel(2))

                // /fpschess board : construit le plateau 3D au nord du joueur
                .then(CommandManager.literal("board").executes(ctx -> {
                    ServerCommandSource src = ctx.getSource();
                    ServerPlayerEntity player = src.getPlayerOrThrow();
                    ServerWorld world = src.getWorld();
                    BlockPos p = player.getBlockPos();

                    if (renderer != null) renderer.removeAll(); // supprime l'ancien plateau

                    int size = 8 * BoardRenderer.CELL;
                    int x0 = p.getX() - size / 2;
                    int z0 = p.getZ() - size - 3; // plateau 3 blocs devant (nord) le joueur
                    int y0 = p.getY() - 1;        // sol du plateau = sol sous les pieds

                    renderer = new BoardRenderer(src.getServer(), world, x0, y0, z0);
                    renderer.buildBoard();
                    renderer.renderPieces(GAME.board());
                    handler = new ClickHandler(GAME, renderer);
                    src.sendFeedback(() -> Text.literal(
                        "Plateau créé au nord (direction -Z). Les Blancs sont au sud."), false);
                    return 1;
                }))

                .then(CommandManager.literal("clear").executes(ctx -> {
                    if (renderer != null) {
                        renderer.removeAll();
                        renderer = null;
                        handler = null;
                    }
                    ctx.getSource().sendFeedback(() -> Text.literal("Plateau supprimé."), false);
                    return 1;
                }))

                .then(CommandManager.literal("new").executes(ctx -> {
                    GAME.reset();
                    if (renderer != null) {
                        handler.reset();
                        renderer.renderPieces(GAME.board());
                    }
                    ctx.getSource().sendFeedback(() -> Text.literal("Nouvelle partie.\n" + GAME.asciiBoard()), false);
                    return 1;
                }))

                .then(CommandManager.literal("show").executes(ctx -> {
                    ctx.getSource().sendFeedback(
                        () -> Text.literal(GAME.asciiBoard() + "\n" + GAME.statusText()), false);
                    return 1;
                }))

                // /fpschess move e2e4 (test sans duel : le coup est joué directement)
                .then(CommandManager.literal("move")
                    .then(CommandManager.argument("coup", StringArgumentType.word()).executes(ctx -> {
                        String text = StringArgumentType.getString(ctx, "coup");
                        MoveResult r = GAME.tryMove(text);
                        if (!r.legal()) {
                            ctx.getSource().sendError(Text.literal(r.message()));
                            return 0;
                        }
                        String info = "";
                        if (r.capture() != null) {
                            CaptureInfo c = r.capture();
                            info = "\n>>> PRISE : " + c.attacker() + " prend " + c.defender()
                                 + " en " + c.capturedSquare() + " -> un DUEL se déclencherait ici.";
                        }
                        GAME.applyMove(r.move());
                        if (renderer != null) {
                            handler.reset();
                            renderer.renderPieces(GAME.board());
                        }
                        String out = GAME.asciiBoard() + info + "\n" + GAME.statusText();
                        ctx.getSource().sendFeedback(() -> Text.literal(out), true);
                        return 1;
                    })))
            )
        );

        LOGGER.info("FPS Chess chargé.");
    }
}
