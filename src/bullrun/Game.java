package bullrun;

import java.io.IOException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Game {
    static final class Sub {
        final String realm;
        final BlockingQueue<String> q = new ArrayBlockingQueue<>(64);
        volatile boolean alive = true;

        Sub(String realm) {
            this.realm = realm;
        }
    }

    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis());
    private final SecureRandom rng = new SecureRandom();
    final Map<String, Realm> realms = new LinkedHashMap<>();
    final Map<String, Player> players = new ConcurrentHashMap<>();
    final Map<String, String> tokens = new ConcurrentHashMap<>();
    final List<Sub> subs = new CopyOnWriteArrayList<>();
    final String adminKey;
    volatile String graphKey = System.getenv().getOrDefault("BULLRUN_GRAPH", "graph");
    private final Path file;

    Game(Path dir, String adminKey) throws IOException {
        Files.createDirectories(dir);
        this.file = dir.resolve("save.json");
        this.adminKey = adminKey;
        for (Defs.RealmDef d : Defs.REALMS) realms.put(d.id(), new Realm(this, d));
    }

    static long nextId() {
        return IDS.incrementAndGet();
    }

    Realm realm(String id) {
        Realm r = realms.get(id);
        if (r == null) throw new ApiError(404, "Unknown server");
        return r;
    }

    Realm realm(Player p) {
        return realm(p.realm);
    }

    int online(Realm r) {
        int n = 0;
        for (Sub s : subs) if (s.realm.equals(r.def.id())) n++;
        return n;
    }

    void push(String realm, String json) {
        String frame = "data: " + json + "\n\n";
        for (Sub s : subs) if (s.realm.equals(realm) && !s.q.offer(frame)) s.alive = false;
    }

    private static String hash(String pw, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), Base64.getDecoder().decode(salt), 20000, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomB64(int n) {
        byte[] b = new byte[n];
        rng.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    Player register(String name, String pw, String avatar, String realmId) {
        if (!name.matches("[A-Za-z0-9_]{3,16}")) throw new ApiError(400, "Name must be 3-16 letters, numbers or _");
        if (pw.length() < 4) throw new ApiError(400, "Password needs at least 4 characters");
        Realm r = realm(realmId);
        if (!r.open) throw new ApiError(423, "That arena is closed right now");
        Player p = new Player(name);
        byte[] salt = new byte[16];
        rng.nextBytes(salt);
        p.salt = Base64.getEncoder().encodeToString(salt);
        p.hash = hash(pw, p.salt);
        p.avatar = avatar.isBlank() || avatar.length() > 8 ? "🦊" : avatar;
        p.realm = realmId;
        if (players.putIfAbsent(name.toLowerCase(), p) != null) throw new ApiError(409, "That name is taken");
        r.join(p);
        return p;
    }

    Player login(String name, String pw) {
        Player p = players.get(name.toLowerCase());
        if (p == null || !MessageDigest.isEqual(hash(pw, p.salt).getBytes(), p.hash.getBytes()))
            throw new ApiError(401, "Wrong name or password");
        if (p.banned) throw new ApiError(403, "This account is banned");
        if (!realm(p).open) throw new ApiError(423, "Your arena is closed right now");
        return p;
    }

    String token(Player p) {
        String t = randomB64(24);
        tokens.put(t, p.name.toLowerCase());
        return t;
    }

    Player auth(String token) {
        String key = token == null ? null : tokens.get(token);
        Player p = key == null ? null : players.get(key);
        if (p == null) throw new ApiError(401, "Please sign in again");
        if (p.banned) throw new ApiError(403, "This account is banned");
        if (!realm(p).open) throw new ApiError(423, "This arena was closed by the admin");
        p.lastSeen = System.currentTimeMillis();
        return p;
    }

    boolean isAdmin(String key) {
        return key != null && MessageDigest.isEqual(key.getBytes(), adminKey.getBytes());
    }

    boolean isGraph(String key) {
        return key != null && (MessageDigest.isEqual(key.getBytes(), graphKey.getBytes()) || isAdmin(key));
    }

    List<Object> realmList() {
        List<Object> out = new ArrayList<>();
        for (Realm r : realms.values())
            out.add(Json.of("id", r.def.id(), "name", r.def.name(), "blurb", r.def.blurb(),
                    "open", r.open, "players", r.players.size(), "online", online(r)));
        return out;
    }

    List<Object> adminRealms() {
        List<Object> out = new ArrayList<>();
        for (Realm r : realms.values())
            out.add(Json.of("id", r.def.id(), "name", r.def.name(), "paused", r.paused, "open", r.open,
                    "players", r.players.size(), "online", online(r)));
        return out;
    }

    void start() {
        ScheduledExecutorService ex = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "game");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> {
            for (Realm r : realms.values()) {
                if (!r.open) continue;
                try {
                    r.tick();
                    if (online(r) > 0) push(r.def.id(), r.frame(online(r)));
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        ex.scheduleAtFixedRate(this::save, 20, 20, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::save));
    }

    synchronized void save() {
        try {
            Map<String, Object> rs = new LinkedHashMap<>();
            realms.forEach((k, v) -> rs.put(k, v.dump()));
            List<Object> ps = new ArrayList<>();
            for (Player p : players.values()) ps.add(p.toMap());
            String json = Json.write(Json.of("realms", rs, "players", ps, "tokens", tokens, "graphKey", graphKey));
            Path tmp = file.resolveSibling("save.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("save failed: " + e);
        }
    }

    void load() throws IOException {
        if (!Files.exists(file)) return;
        Map<String, Object> root = Json.obj(Files.readString(file));
        Json.map(root.get("realms")).forEach((k, v) -> {
            Realm r = realms.get(k);
            if (r != null) r.restore(Json.map(v));
        });
        for (Object o : Json.list(root.get("players"))) {
            Player p = Player.from(Json.map(o));
            Realm r = realms.get(p.realm);
            if (r == null) continue;
            players.put(p.name.toLowerCase(), p);
            r.players.put(p.name.toLowerCase(), p);
        }
        Json.map(root.get("tokens")).forEach((k, v) -> tokens.put(k, String.valueOf(v)));
        String gk = Json.str(root.get("graphKey"), "");
        if (!gk.isEmpty()) graphKey = gk;
        realms.values().forEach(Realm::reconcile);
    }
}
