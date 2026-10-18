package com.positivity.extractionworker.internal.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Test-only child entry point, selected through {@code pos.extraction-worker.sandbox.child-main-class}. Each
 * operation name misbehaves in one way the parent has to survive.
 */
public final class SandboxTestMain {

    private SandboxTestMain() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        String op = args[0];
        Path dir = Path.of(args[1]);
        // Through the API the operation is always inspect or render: the input's text picks the misbehaviour.
        Path input = dir.resolve("input");
        if (Files.isRegularFile(input) && ("inspect".equals(op) || "render".equals(op))) {
            String text = new String(Files.readAllBytes(input), StandardCharsets.ISO_8859_1);
            if (text.contains("SLEEP-PLEASE")) {
                op = "sleep";
            } else if (text.contains("OOM-PLEASE")) {
                op = "oom";
            }
        }
        switch (op) {
            case "sleep" -> Thread.sleep(120_000);
            case "oom" -> {
                List<long[]> hoard = new ArrayList<>();
                while (true) {
                    hoard.add(new long[1_000_000]);
                }
            }
            case "exit2" -> System.exit(2);
            case "junk" -> Files.writeString(dir.resolve("result.json"), "this is not json");
            case "huge" -> Files.writeString(dir.resolve("result.json"), " ".repeat(10_000) + "{}");
            case "env" -> {
                Files.writeString(
                        dir.resolve("env.txt"),
                        String.join(",", new TreeSet<>(System.getenv().keySet())),
                        StandardCharsets.UTF_8);
                Files.writeString(dir.resolve("result.json"), "{\"outcome\":\"ACCEPTED\",\"pageCount\":1}");
            }
            case "stderr" -> {
                System.err.println("SECRET-FILE-CONTENT-IN-STDERR");
                System.out.println("SECRET-FILE-CONTENT-IN-STDOUT");
                Files.writeString(dir.resolve("result.json"), "{\"outcome\":\"ACCEPTED\",\"pageCount\":2}");
            }
            case "badpages" ->
                Files.writeString(dir.resolve("result.json"), "{\"outcome\":\"ACCEPTED\",\"pageCount\":-5}");
            default -> Files.writeString(dir.resolve("result.json"), "{\"outcome\":\"ACCEPTED\",\"pageCount\":7}");
        }
    }
}
