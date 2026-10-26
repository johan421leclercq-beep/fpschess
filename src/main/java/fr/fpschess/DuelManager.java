package fr.fpschess;

import com.github.bhlangonijr.chesslib.PieceType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Gère un duel à la fois : sauvegarde de l'état des joueurs, téléportation dans l'arène,
 * kits, détection de la fin (mort, temps écoulé, déconnexion) et retour au plateau.
 *
 * Joueur contre joueur, ou joueur contre zombie (mode solo, quand defender == null).
 */
public final class DuelManager {

    private static final int GRACE_TICKS = 60;          // 3 s de préparation
    private static final int DURATION_TICKS = 20 * 90;  // 90 s de combat max

    private DuelManager() {}

    private static class Saved {
        ItemStack[] stacks;
        String gameMode;
        double x, y, z;
        float yaw, pitch;
        String dimension;
    }

    private static class Duel {
        BoardRenderer renderer;
        UUID attacker, defender, dummy; // defender == null -> zombie (dummy)
        Map<UUID, Saved> saved = new HashMap<>();
        int ticks = 0;
        Boolean attackerWon = null;
        Consumer<Boolean> onFinish;
    }

    private static Duel current = null;

    public static boolean isActive() {
        return current != null;
    }

    // ---------- Démarrage ----------

    public static void start(MinecraftServer server, BoardRenderer r, CaptureInfo c,
                             ServerPlayerEntity attacker, ServerPlayerEntity defender,
                             Consumer<Boolean> onFinish) {
        Duel d = new Duel();
        d.renderer = r;
        d.attacker = attacker.getUuid();
        d.defender = defender == null ? null : defender.getUuid();
        d.dummy = defender == null ? UUID.randomUUID() : null;
        d.onFinish = onFinish;

        PieceType atkType = c.attacker().getPieceType();
        PieceType defType = c.defender().getPieceType();

        save(d, attacker);
        prepare(d, attacker, atkType, 0);
        if (defender != null) {
            save(d, defender);
            prepare(d, defender, defType, 1);
        } else {
            spawnDummy(d, defType);
        }
        current = d;

        String subtitle = Kits.pieceName(atkType) + " contre " + Kits.pieceName(defType);
        announce(d, server, subtitle);
    }

    private static void save(Duel d, ServerPlayerEntity p) {
        Saved s = new Saved();
        int size = p.getInventory().size();
        s.stacks = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            s.stacks[i] = p.getInventory().getStack(i).copy();
        }
        s.gameMode = p.interactionManager.getGameMode().getName();
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yaw = p.getYaw();
        s.pitch = p.getPitch();
        s.dimension = p.getWorld().getRegistryKey().getValue().toString();
        d.saved.put(p.getUuid(), s);
    }

    private static void prepare(Duel d, ServerPlayerEntity p, PieceType type, int spawnIndex) {
        BoardRenderer r = d.renderer;
        String sel = p.getUuidAsString();
        p.getInventory().clear();
        p.clearStatusEffects();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        r.run("gamemode adventure " + sel);
        double[] sp = r.arenaSpawn(spawnIndex);
        r.run(String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f %.1f 0",
                r.dimension(), sel, sp[0], sp[1], sp[2], sp[3]));
        Kits.apply(r, sel, type, false);
        r.run("effect give " + sel + " minecraft:resistance 3 4 true"); // protection pendant la préparation
    }

    private static void spawnDummy(Duel d, PieceType type) {
        BoardRenderer r = d.renderer;
        double[] sp = r.arenaSpawn(1);
        long msb = d.dummy.getMostSignificantBits();
        long lsb = d.dummy.getLeastSignificantBits();
        String uuidNbt = String.format(Locale.ROOT, "[I;%d,%d,%d,%d]",
                (int) (msb >> 32), (int) msb, (int) (lsb >> 32), (int) lsb);
        r.run(String.format(Locale.ROOT,
                "execute in %s run summon minecraft:zombie %.2f %.2f %.2f "
              + "{UUID:%s,NoAI:1b,PersistenceRequired:1b,CanPickUpLoot:0b,IsBaby:0b,"
              + "DeathLootTable:\"minecraft:empty\",Tags:[\"fpschess_dummy\"],"
              + "CustomName:'{\"text\":\"%s adverse\"}'}",
                r.dimension(), sp[0], sp[1], sp[2], uuidNbt, Kits.pieceName(type)));
        Kits.apply(r, d.dummy.toString(), type, true);
    }

    private static void announce(Duel d, MinecraftServer server, String subtitle) {
        for (UUID id : d.saved.keySet()) {
            String sel = id.toString();
            d.renderer.run("title " + sel + " subtitle {\"text\":\"" + subtitle + "\",\"color\":\"gray\"}");
            d.renderer.run("title " + sel + " title {\"text\":\"DUEL\",\"color\":\"red\",\"bold\":true}");
        }
    }

    // ---------- À chaque tick ----------

    public static void tick(MinecraftServer server) {
        Duel d = current;
        if (d == null) return;
        d.ticks++;

        if (d.attackerWon == null) {
            if (d.ticks == GRACE_TICKS && d.dummy != null) {
                d.renderer.run("data modify entity " + d.dummy + " NoAI set value 0b");
            }
            if (d.ticks >= GRACE_TICKS + DURATION_TICKS) {
                d.attackerWon = false; // temps écoulé : le défenseur garde la case
            }
            if (d.ticks % 20 == 0) {
                String text = d.ticks < GRACE_TICKS
                        ? "Début dans " + ((GRACE_TICKS - d.ticks) / 20 + 1) + " s"
                        : "Temps restant : " + ((GRACE_TICKS + DURATION_TICKS - d.ticks) / 20) + " s";
                for (UUID id : d.saved.keySet()) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) p.sendMessage(Text.literal(text), true);
                }
            }
        }

        if (d.attackerWon != null) {
            finish(server, d);
        }
    }

    // ---------- Événements ----------

    /** Appelé quand une entité va mourir. Renvoie false pour annuler la mort (joueurs en duel). */
    public static boolean allowDeath(LivingEntity entity) {
        Duel d = current;
        if (d == null) return true;
        UUID id = entity.getUuid();
        if (id.equals(d.dummy)) {
            if (d.attackerWon == null) d.attackerWon = true;
            return true;
        }
        if (id.equals(d.attacker) || id.equals(d.defender)) {
            if (d.attackerWon == null) d.attackerWon = !id.equals(d.attacker);
            entity.setHealth(1.0F); // le joueur ne meurt pas vraiment ; tout est remis en ordre à la fin
            return false;
        }
        return true;
    }

    /** Un joueur quitte en plein duel : il perd, et on lui rend son inventaire tout de suite. */
    public static void onDisconnect(ServerPlayerEntity p) {
        Duel d = current;
        if (d == null || !d.saved.containsKey(p.getUuid())) return;
        if (d.attackerWon == null) d.attackerWon = !p.getUuid().equals(d.attacker);
        restore(d, p);
    }

    // ---------- Fin ----------

    private static void finish(MinecraftServer server, Duel d) {
        for (UUID id : d.saved.keySet().toArray(new UUID[0])) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) restore(d, p);
        }
        if (d.dummy != null) {
            d.renderer.run("kill " + d.dummy);
        }
        d.renderer.clearArenaItems();
        current = null;
        d.onFinish.accept(d.attackerWon);
    }

    private static void restore(Duel d, ServerPlayerEntity p) {
        Saved s = d.saved.remove(p.getUuid());
        if (s == null) return;
        BoardRenderer r = d.renderer;
        p.getInventory().clear();
        for (int i = 0; i < s.stacks.length; i++) {
            p.getInventory().setStack(i, s.stacks[i]);
        }
        p.clearStatusEffects();
        p.extinguish();
        p.setHealth(p.getMaxHealth());
        p.getHungerManager().setFoodLevel(20);
        String sel = p.getUuidAsString();
        r.run("gamemode " + s.gameMode + " " + sel);
        r.run(String.format(Locale.ROOT, "execute in %s run tp %s %.2f %.2f %.2f %.1f %.1f",
                s.dimension, sel, s.x, s.y, s.z, s.yaw, s.pitch));
    }
}
