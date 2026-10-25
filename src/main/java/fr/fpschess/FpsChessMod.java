package fr.fpschess;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FpsChessMod implements ModInitializer {

    public static final String MOD_ID = "fpschess";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Partie unique pour l'instant (étape 2). Plusieurs parties viendront plus tard.
    private static final ChessGame GAME = new ChessGame();

    @Override
    public void onInitialize() {
        GAME.reset();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("fpschess")
                .requires(src -> src.hasPermissionLevel(2))

                .then(CommandManager.literal("new").executes(ctx -> {
                    GAME.reset();
                    ctx.getSource().sendFeedback(() -> Text.literal("Nouvelle partie.\n" + GAME.asciiBoard()), false);
                    return 1;
                }))

                .then(CommandManager.literal("show").executes(ctx -> {
                    ctx.getSource().sendFeedback(
                        () -> Text.literal(GAME.asciiBoard() + "\n" + GAME.statusText()), false);
                    return 1;
                }))

                // /fpschess move e2e4  (test du moteur, sans duel)
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
                        String out = GAME.asciiBoard() + info + "\n" + GAME.statusText();
                        ctx.getSource().sendFeedback(() -> Text.literal(out), true);
                        return 1;
                    })))
            )
        );

        LOGGER.info("FPS Chess chargé.");
    }
}
