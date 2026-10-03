package com.positivity.inventory.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@code inventory:} permission a controller names is one pos-inventory still declares and
 * has not retired (#2422).
 *
 * <p>The controller sources carry the {@code @Operation} descriptions that become
 * {@code openapi.yaml}, which the SDKs, the frontend and the assistant read. After the purchase-order
 * aggregate moved to pos-order (CAP-320 #1334), {@code PurchaseSuggestionController} kept telling
 * callers they needed {@code inventory:purchase_order:create} while its gate checked
 * {@code order:purchase_order:create}: a description naming a code nobody can use. This scans the
 * sources (descriptions and javadoc alike) rather than the annotations, so a stale mention in either
 * fails here.
 */
@DisplayName("inventory permissions named by controllers are declared and live (#2422)")
class ControllerPermissionMentionsTest {

    private static final Path CONTROLLER_ROOT = Path.of("src", "main", "java", "com", "positivity", "inventory");
    private static final Path MANIFEST = Path.of("src", "main", "resources", "permissions.yaml");

    private static final Pattern MENTION = Pattern.compile("\\binventory:[a-z_\\-]+:[a-z_\\-]+\\b");
    private static final Pattern MANIFEST_ENTRY = Pattern.compile("(?m)^\\s*-\\s*name:\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("no controller names an undeclared or deprecated inventory permission")
    void controllersNameOnlyLiveInventoryPermissions() throws IOException {
        Set<String> live = livePermissions();
        assertThat(live)
                .as("no live permissions parsed out of permissions.yaml")
                .isNotEmpty();

        Map<String, Set<String>> offending = new TreeMap<>();
        int controllers = 0;
        try (Stream<Path> files = Files.walk(CONTROLLER_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith("Controller.java"))
                    .toList()) {
                controllers++;
                Matcher matcher = MENTION.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!live.contains(matcher.group())) {
                        offending
                                .computeIfAbsent(file.getFileName().toString(), key -> new TreeSet<>())
                                .add(matcher.group());
                    }
                }
            }
        }

        assertThat(controllers).as("no controller sources found").isPositive();
        assertThat(offending)
                .as("controllers naming an inventory permission permissions.yaml does not declare as live")
                .isEmpty();
    }

    @Test
    @DisplayName("the retired purchase-order codes are not live, so the scan would catch them")
    void theRetiredPurchaseOrderCodesAreNotLive() throws IOException {
        assertThat(livePermissions())
                .doesNotContain(
                        "inventory:purchase_order:create",
                        "inventory:purchase_order:view",
                        "inventory:purchase_order:approve",
                        "inventory:purchase_order:receive");
    }

    /** Manifest names, less those whose entry carries {@code deprecated: true}. */
    private static Set<String> livePermissions() throws IOException {
        String manifest = Files.readString(MANIFEST, StandardCharsets.UTF_8);
        Set<String> live = new TreeSet<>();
        Matcher matcher = MANIFEST_ENTRY.matcher(manifest);
        int previousStart = -1;
        String previousName = null;
        while (matcher.find()) {
            addIfLive(live, previousName, manifest, previousStart, matcher.start());
            previousName = matcher.group(1);
            previousStart = matcher.start();
        }
        addIfLive(live, previousName, manifest, previousStart, manifest.length());
        return live;
    }

    private static void addIfLive(Set<String> live, String name, String manifest, int start, int end) {
        if (name != null && !manifest.substring(start, end).contains("deprecated: true")) {
            live.add(name);
        }
    }
}
