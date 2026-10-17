package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.WorkItemsCursorInvalidException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limit and cursor of the work-item reads (CAP:550 S19, #2515; ruling 8 of 2026-10-17): oldest first, {@code itemId}
 * the tiebreak, a page resuming after the key of the last item it served, so an item that has since vanished is no
 * error. The cursor is the URL-safe Base64 of {@code v1|scope|occurredOn|itemId} and an HMAC-SHA256 of that text; the
 * scope is the area the cursor was issued for, or the {@code since} of the automatic list. A cursor that is malformed,
 * altered or issued for another scope is {@link WorkItemsCursorInvalidException}.
 *
 * <p>The cursor holds a position only, never an authority: the HMAC catches a hand-edited or truncated value, it does
 * not hide the position. The key is {@code pos.accounting.work-items.cursor-secret}; its default is the same on every
 * instance, so a cursor survives a restart and a request landing on another instance.
 */
@Component
public class WorkItemPaging {

    /** The page size when the caller names none. */
    public static final int DEFAULT_LIMIT = 50;

    /** The largest page size. */
    public static final int MAX_LIMIT = 200;

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final String MAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public WorkItemPaging(
            @Value("${pos.accounting.work-items.cursor-secret:durion-accounting-work-items-cursor}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** The position after which a page resumes. */
    public record Key(@NonNull LocalDate occurredOn, @NonNull String itemId) {}

    /** A page: its items and the cursor of the next, null on the last. */
    public record Slice<T>(@NonNull List<T> items, @Nullable String nextCursor) {}

    /**
     * The effective page size.
     *
     * @throws InvalidRequestParameterException {@code VALIDATION_ERROR} (field {@code limit}) outside 1 to {@value
     *     #MAX_LIMIT}
     */
    public int limit(@Nullable Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw InvalidRequestParameterException.forField("limit", "must be from 1 to " + MAX_LIMIT);
        }
        return limit;
    }

    /** The cursor's position, null for the first page; refuses one not issued for {@code scope}. */
    public @Nullable Key decode(@Nullable String cursor, @NonNull String scope) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int dot = cursor.indexOf('.');
        if (dot <= 0 || dot == cursor.length() - 1) {
            throw invalid();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(cursor.substring(0, dot));
            byte[] mac = Base64.getUrlDecoder().decode(cursor.substring(dot + 1));
            if (!MessageDigest.isEqual(mac, mac(payload))) {
                throw invalid();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", 4);
            if (parts.length != 4 || !VERSION.equals(parts[0]) || !scope.equals(parts[1]) || parts[3].isEmpty()) {
                throw invalid();
            }
            return new Key(LocalDate.parse(parts[2]), parts[3]);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    /** The cursor of a position, for {@code scope}. */
    public @NonNull String encode(@NonNull String scope, @NonNull Key key) {
        byte[] payload = String.join(SEPARATOR, VERSION, scope, key.occurredOn().toString(), key.itemId())
                .getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload));
    }

    /**
     * {@code items} sorted oldest first (item id the tiebreak), the part after {@code after} up to {@code limit} items,
     * and the cursor of the next page when more follow.
     */
    public <T> @NonNull Slice<T> slice(
            @NonNull List<T> items,
            @NonNull Function<T, LocalDate> occurredOn,
            @NonNull Function<T, String> itemId,
            @NonNull String scope,
            @Nullable Key after,
            int limit) {
        Comparator<T> order = Comparator.comparing(occurredOn).thenComparing(itemId);
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(order);
        List<T> page = new ArrayList<>(Math.min(limit, sorted.size()));
        boolean more = false;
        for (T item : sorted) {
            if (after != null && compare(occurredOn.apply(item), itemId.apply(item), after) <= 0) {
                continue;
            }
            if (page.size() == limit) {
                more = true;
                break;
            }
            page.add(item);
        }
        String next = null;
        if (more) {
            T last = page.getLast();
            next = encode(scope, new Key(occurredOn.apply(last), itemId.apply(last)));
        }
        return new Slice<>(page, next);
    }

    private static int compare(LocalDate date, String itemId, Key key) {
        int byDate = date.compareTo(key.occurredOn());
        return byDate != 0 ? byDate : itemId.compareTo(key.itemId());
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, MAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static WorkItemsCursorInvalidException invalid() {
        return new WorkItemsCursorInvalidException(
                "The cursor is not valid for this list; start again from the first page");
    }
}
