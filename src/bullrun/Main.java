package bullrun;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String key = System.getenv().getOrDefault("BULLRUN_ADMIN", "letmein");
        Game game = new Game(Path.of(System.getenv().getOrDefault("BULLRUN_DATA", "data")), key);
        game.load();
        game.start();
        new Http(game).start(port);
        System.out.println("Bullrun is live on http://localhost:" + port);
        System.out.println("Admin panel: http://localhost:" + port + "/admin  (key: " + key + ")");
        if (key.equals("letmein")) System.out.println("Set BULLRUN_ADMIN to change the default admin key before going public.");
    }
}
