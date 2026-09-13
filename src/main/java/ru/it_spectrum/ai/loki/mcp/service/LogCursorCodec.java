package ru.it_spectrum.ai.loki.mcp.service;

import java.time.Clock;
import java.util.*;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.loki.mcp.model.ErrorCode;
import tools.jackson.databind.json.JsonMapper;

/** Stateless, authenticated and confidential cursor. Only the process key is retained. */
@Component
public final class LogCursorCodec {
    public static final long TTL_SECONDS = 900;
    public static final int MAX_TOKEN_CHARACTERS = 24000;
    private final byte[] key = new byte[32];
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper mapper = new JsonMapper();
    private final Clock clock;
    public LogCursorCodec() { this(Clock.systemUTC()); }
    public LogCursorCodec(Clock clock) { this.clock = clock; random.nextBytes(key); }
    public record State(String connection, String query, String start, String end, String direction,
                        int pageSize, List<String> fields, long expiresAt, String boundary, int consumed, String digest) {
        public State { fields = List.copyOf(fields); }
        @Override public String toString() { return "LogCursorState[redacted]"; }
    }
    public long expiry() { return clock.instant().getEpochSecond() + TTL_SECONDS; }
    public String encode(State state) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(state));
            byte[] token = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(encrypted, 0, token, nonce.length, encrypted.length);
            String result = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            return result.length() <= MAX_TOKEN_CHARACTERS ? result : null;
        } catch (Exception ignored) { throw new IllegalStateException("Cannot create cursor."); }
    }
    public State decode(String connection, String token) {
        State state;
        try {
            if (token == null || token.length() > MAX_TOKEN_CHARACTERS || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length < 29) throw new IllegalArgumentException();
            byte[] plain = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, 12)).doFinal(Arrays.copyOfRange(bytes, 12, bytes.length));
            state = mapper.readValue(plain, State.class);
            if (!state.connection().equals(connection)) throw new IllegalArgumentException();
        } catch (Exception ignored) { throw Errors.failure(ErrorCode.INVALID_CURSOR, "Cursor is invalid for this connection or server process."); }
        if (clock.instant().getEpochSecond() >= state.expiresAt()) throw Errors.failure(ErrorCode.CURSOR_EXPIRED, "Cursor expired. Start a new query.");
        return state;
    }
    private Cipher cipher(int mode, byte[] nonce) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("loki-log-cursor-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher;
    }
}
