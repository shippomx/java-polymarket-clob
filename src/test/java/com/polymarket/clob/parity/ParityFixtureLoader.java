package com.polymarket.clob.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polymarket.clob.http.JsonCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 从 test-resources 加载 parity fixture 文件。
 *
 * <ul>
 *   <li>{@code parity/inputs/*.json}：deterministic 输入向量（id / category / frozen / config / call）</li>
 *   <li>{@code parity/expected/&lt;id&gt;.json}：Rust oracle 输出的 ParityRequestSnapshot golden</li>
 * </ul>
 *
 * <p>当资源目录尚未填充（fixtures 在 Task 12/13 加入）时，{@link #loadAllInputs()}
 * 返回空 list 而非抛错——便于 fixture 子集渐进添加。</p>
 */
public final class ParityFixtureLoader {

    private static final ObjectMapper MAPPER = JsonCodec.objectMapper();

    /** 输入向量 classpath 资源根。 */
    public static final String INPUTS_RESOURCE = "/parity/inputs";

    /** 期望快照 classpath 资源根。 */
    public static final String EXPECTED_RESOURCE = "/parity/expected";

    private ParityFixtureLoader() {}

    /**
     * 加载所有 input fixtures，按文件路径字典序排序。资源目录不存在时返回空 list。
     */
    public static List<JsonNode> loadAllInputs() {
        Path inputs = optionalResourceDir(INPUTS_RESOURCE);
        if (inputs == null) {
            return List.of();
        }
        return walkJsonFiles(inputs);
    }

    /**
     * 按 {@code id} 加载 expected 快照（Rust oracle 输出）。
     *
     * @throws UncheckedIOException 如果 expected 文件不存在或读取失败
     */
    public static JsonNode loadExpected(String id) {
        Objects.requireNonNull(id, "id");
        Path p = requireResourceDir(EXPECTED_RESOURCE).resolve(id + ".json");
        try {
            return MAPPER.readTree(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read expected fixture " + id, e);
        }
    }

    private static Path optionalResourceDir(String relPath) {
        try {
            var url = ParityFixtureLoader.class.getResource(relPath);
            return url == null ? null : Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path requireResourceDir(String relPath) {
        Path p = optionalResourceDir(relPath);
        if (p == null) {
            throw new IllegalStateException("missing resource directory " + relPath);
        }
        return p;
    }

    private static List<JsonNode> walkJsonFiles(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            List<JsonNode> out = new ArrayList<>();
            for (Path f : files) {
                out.add(MAPPER.readTree(Files.readAllBytes(f)));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
