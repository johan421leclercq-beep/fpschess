package fr.fpschess;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Annonces en gros titre au milieu de l'écran, avec la possibilité de les différer de quelques ticks. */
public final class Titles {

    private Titles() {}

    private static class Delayed {
        int ticks;
        Runnable task;
    }

    private static final List<Delayed> QUEUE = new ArrayList<>();

    /** Affiche un titre. selector : "@a" pour tout le monde, ou "@a[name=Pseudo]" pour un joueur. */
    public static void show(MinecraftServer server, String selector, String title, String subtitle, String color) {
        run(server, "title " + selector + " times 10 60 20");
        run(server, "title " + selector + " subtitle {\"text\":\"" + esc(subtitle) + "\",\"color\":\"gray\"}");
        run(server, "title " + selector + " title {\"text\":\"" + esc(title) + "\",\"color\":\"" + color + "\",\"bold\":true}");
    }

    /** Exécute une action dans 'ticks' ticks (20 ticks = 1 seconde). */
    public static void later(int ticks, Runnable task) {
        Delayed d = new Delayed();
        d.ticks = ticks;
        d.task = task;
        QUEUE.add(d);
    }

    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        List<Runnable> due = new ArrayList<>();
        Iterator<Delayed> it = QUEUE.iterator();
        while (it.hasNext()) {
            Delayed d = it.next();
            if (--d.ticks <= 0) {
                due.add(d.task);
                it.remove();
            }
        }
        for (Runnable r : due) r.run();
    }

    /** Première lettre en majuscule. */
    public static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withSilent(), command);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
