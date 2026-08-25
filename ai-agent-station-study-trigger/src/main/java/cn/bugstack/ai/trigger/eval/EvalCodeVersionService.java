package cn.bugstack.ai.trigger.eval;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import cn.bugstack.ai.infrastructure.dao.po.AiEvalCodeVersion;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCodeVersionService {

    private static final String HASH_ALGORITHM = "GIT_TREE_SHA1";
    private static final String SCOPE_VERSION = "evalops-v2";
    private static final int MAX_GIT_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final List<String> DEFAULT_IGNORE_PATTERNS = List.of(
            "**/target/**", "**/build/**", "**/node_modules/**", "**/src/test/**", "**/*.log",
            "logs/**", "tmp/**", "docs/dev-ops/log/**", ".idea/**", ".understand-anything/**",
            "ai-agent-station-study-app/src/main/resources/application-dev.yml",
            "docs/dev-ops/docker-compose-*.yml", "面试/**",
            "docs/dev-ops/test/**", "docs/dev-ops/sql-backups/**", "docs/dev-ops/_*",
            "docs/images/**", "**/api-docs/**", "**/*.md", "**/*.tsv", "**/*.mp4", "**/*.pdf"
    );

    private final IAiEvalOpsDao dao;

    @Value("${eval.ops.git.repository-path:}")
    private String configuredRepositoryPath;

    private volatile Path repositoryRoot;
    private final Map<String, String> commitTreeCache = new ConcurrentHashMap<>();
    private volatile Map<String, Object> runtimeSnapshot = Map.of(
            "available", false,
            "capturedAt", LocalDateTime.now().toString(),
            "warning", "代码快照尚未初始化"
    );

    @PostConstruct
    public void initializeRuntimeSnapshot() {
        captureRuntimeSnapshot("APPLICATION_START");
    }

    private synchronized Map<String, Object> captureRuntimeSnapshot(String captureTrigger) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("capturedAt", LocalDateTime.now().toString());
        snapshot.put("captureTrigger", captureTrigger);
        snapshot.put("hashAlgorithm", HASH_ALGORITHM);
        snapshot.put("scopeVersion", SCOPE_VERSION);
        try {
            repositoryRoot = findRepositoryRoot();
            List<String> ignorePatterns = loadIgnorePatterns(repositoryRoot);
            String head = gitText(repositoryRoot, Map.of(), null, "rev-parse", "HEAD").trim();
            String branch = gitText(repositoryRoot, Map.of(), null, "branch", "--show-current").trim();
            String sourceTreeHash = filteredTreeHash(head, ignorePatterns, true);
            String headTreeHash = filteredTreeHash(head, ignorePatterns, false);
            List<String> headTags = tagNamesPointingAt(head);

            snapshot.put("available", true);
            snapshot.put("repository", repositoryRoot.getFileName() == null
                    ? repositoryRoot.toString() : repositoryRoot.getFileName().toString());
            snapshot.put("branch", branch.isBlank() ? "DETACHED" : branch);
            snapshot.put("headCommit", head);
            snapshot.put("headTags", headTags);
            snapshot.put("dirty", !sourceTreeHash.equals(headTreeHash));
            snapshot.put("sourceTreeHash", sourceTreeHash);
            snapshot.put("headTreeHash", headTreeHash);
            snapshot.put("ignorePatterns", ignorePatterns);
            snapshot.put("ignoreRulesHash", sha256(String.join("\n", ignorePatterns)));
            runtimeSnapshot = Map.copyOf(snapshot);
            log.info("EvalOps code snapshot captured branch={} head={} dirty={} tree={}",
                    snapshot.get("branch"), abbreviate(head), snapshot.get("dirty"), abbreviate(sourceTreeHash));
        } catch (Exception error) {
            log.warn("EvalOps code snapshot unavailable: {}", error.getMessage());
            snapshot.put("available", false);
            snapshot.put("warning", error.getMessage() == null ? "Git 代码快照读取失败" : error.getMessage());
            snapshot.put("ignorePatterns", DEFAULT_IGNORE_PATTERNS);
            runtimeSnapshot = Map.copyOf(snapshot);
        }
        return runtimeSnapshot;
    }

    public AiEvalCodeVersion createRunSnapshot(String evalRunId, LocalDateTime now) {
        // Do not reuse the application-start snapshot. A run must bind to the exact
        // working tree visible when the user starts that evaluation.
        Map<String, Object> snapshot = new LinkedHashMap<>(captureRuntimeSnapshot("RUN_CREATE"));
        boolean available = Boolean.TRUE.equals(snapshot.get("available"));
        boolean dirty = Boolean.TRUE.equals(snapshot.get("dirty"));
        List<String> tags = stringList(snapshot.get("headTags"));
        String head = stringValue(snapshot.get("headCommit"));
        String bindingStatus = "CAPTURE_UNAVAILABLE";
        String boundTag = null;
        String boundCommit = null;
        String bindingMethod = null;
        LocalDateTime boundAt = null;
        if (available && !dirty && !tags.isEmpty()) {
            bindingStatus = "AUTO_VERIFIED";
            boundTag = tags.get(0);
            boundCommit = head;
            bindingMethod = "AUTO_AT_CAPTURE";
            boundAt = now;
        } else if (available) {
            bindingStatus = "WAITING_TAG";
        }
        return AiEvalCodeVersion.builder()
                .evalRunId(evalRunId)
                .repositoryName(stringValue(snapshot.get("repository")))
                .branchName(stringValue(snapshot.get("branch")))
                .capturedHeadSha(head)
                .capturedTagsJson(JSON.toJSONString(tags))
                .dirty(dirty)
                .sourceTreeHash(stringValue(snapshot.get("sourceTreeHash")))
                .hashAlgorithm(HASH_ALGORITHM)
                .scopeVersion(SCOPE_VERSION)
                .ignoreRulesJson(JSON.toJSONString(stringList(snapshot.get("ignorePatterns"))))
                .snapshotJson(JSON.toJSONString(snapshot))
                .bindingStatus(bindingStatus)
                .boundTag(boundTag)
                .boundCommitSha(boundCommit)
                .matchedTagsJson(boundTag == null ? null : JSON.toJSONString(tags))
                .bindingMethod(bindingMethod)
                .boundAt(boundAt)
                .lastCheckedAt(boundAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public Map<String, Object> getCodeVersion(String evalRunId) {
        AiEvalCodeVersion codeVersion = dao.findCodeVersion(evalRunId);
        if (codeVersion == null) return null;
        return toView(codeVersion);
    }

    public Map<String, Object> reconcilePendingBindings() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", 0);
        result.put("bound", 0);
        if (repositoryRoot == null) {
            result.put("available", false);
            result.put("message", "当前运行实例无法访问 Git 仓库");
            return result;
        }
        try {
            List<AiEvalCodeVersion> pending = dao.listPendingCodeVersions(200);
            if (pending.isEmpty()) {
                result.put("available", true);
                return result;
            }
            List<TagRef> tags = tagRefs(100);
            if (commitTreeCache.size() > 2_000) commitTreeCache.clear();
            int bound = 0;
            LocalDateTime checkedAt = LocalDateTime.now();
            for (AiEvalCodeVersion codeVersion : pending) {
                List<String> patterns = parsePatterns(codeVersion.getIgnoreRulesJson());
                List<TagRef> matches = new ArrayList<>();
                for (TagRef tag : tags) {
                    String cacheKey = tag.commitSha() + "\n" + codeVersion.getIgnoreRulesJson();
                    String treeHash = commitTreeCache.computeIfAbsent(cacheKey, ignored -> {
                        try {
                            return filteredTreeHash(tag.commitSha(), patterns, false);
                        } catch (Exception error) {
                            log.warn("Cannot hash tag {} for EvalOps: {}", tag.name(), error.getMessage());
                            return null;
                        }
                    });
                    if (codeVersion.getSourceTreeHash() != null
                            && codeVersion.getSourceTreeHash().equals(treeHash)) {
                        matches.add(tag);
                        break;
                    }
                }
                if (!matches.isEmpty()) {
                    TagRef primary = matches.get(0);
                    List<String> names = matches.stream().map(TagRef::name).toList();
                    dao.updateCodeVersionBinding(codeVersion.getEvalRunId(), "AUTO_VERIFIED",
                            primary.name(), primary.commitSha(), JSON.toJSONString(names),
                            "AUTO_BEFORE_NEXT_RUN", "下次评测前自动匹配源码指纹", checkedAt, checkedAt);
                    bound++;
                } else {
                    dao.touchCodeVersionCheck(codeVersion.getEvalRunId(), checkedAt);
                }
            }
            result.put("available", true);
            result.put("checked", pending.size());
            result.put("bound", bound);
            return result;
        } catch (Exception error) {
            log.warn("EvalOps automatic tag reconciliation failed: {}", error.getMessage());
            result.put("available", false);
            result.put("message", error.getMessage());
            return result;
        }
    }

    @Transactional
    public Map<String, Object> bindTag(String evalRunId, BindTagCommand command) {
        AiEvalCodeVersion codeVersion = dao.findCodeVersion(evalRunId);
        if (codeVersion == null) throw new IllegalArgumentException("该评测没有代码快照");
        if (command == null || command.getTag() == null || command.getTag().isBlank()) {
            throw new IllegalArgumentException("Tag 不能为空");
        }
        String tag = command.getTag().trim();
        if (tag.length() > 255 || tag.contains("\u0000")) throw new IllegalArgumentException("Tag 格式不合法");
        boolean force = Boolean.TRUE.equals(command.getForce());
        String note = trimToNull(command.getNote());
        TagRef resolved = null;
        try {
            if (repositoryRoot != null) resolved = resolveTag(tag);
        } catch (Exception error) {
            if (!force) throw new IllegalArgumentException("Tag 不存在或无法解析：" + tag);
        }

        boolean verifiable = resolved != null && codeVersion.getSourceTreeHash() != null;
        boolean exact = false;
        if (verifiable) {
            try {
                String tagTree = filteredTreeHash(resolved.commitSha(),
                        parsePatterns(codeVersion.getIgnoreRulesJson()), false);
                exact = tagTree.equals(codeVersion.getSourceTreeHash());
            } catch (Exception error) {
                log.warn("Manual EvalOps tag verification failed tag={}: {}", tag, error.getMessage());
                verifiable = false;
            }
        }
        if (!exact && !force) {
            String reason = verifiable ? "Tag 与评测时有效源码不一致" : "当前环境无法校验 Tag";
            throw new IllegalArgumentException(reason + "；如需强制关联，请勾选强制绑定并填写原因");
        }
        if (!exact && note == null) throw new IllegalArgumentException("强制绑定必须填写原因");

        LocalDateTime now = LocalDateTime.now();
        dao.updateCodeVersionBinding(evalRunId, exact ? "MANUAL_VERIFIED" : "MANUAL_UNVERIFIED",
                tag, resolved == null ? null : resolved.commitSha(), JSON.toJSONString(List.of(tag)), "MANUAL",
                note, now, now);
        return getCodeVersion(evalRunId);
    }

    public List<Map<String, Object>> listTags(int limit) {
        if (repositoryRoot == null) return List.of();
        try {
            return tagRefs(Math.max(1, Math.min(limit, 200))).stream().map(tag -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("tag", tag.name());
                view.put("commitSha", tag.commitSha());
                return view;
            }).toList();
        } catch (Exception error) {
            log.warn("Cannot list EvalOps Git tags: {}", error.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> toView(AiEvalCodeVersion value) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("evalRunId", value.getEvalRunId());
        view.put("repository", value.getRepositoryName());
        view.put("branch", value.getBranchName());
        view.put("capturedHeadSha", value.getCapturedHeadSha());
        view.put("capturedTags", parseList(value.getCapturedTagsJson()));
        view.put("dirty", value.getDirty());
        view.put("sourceTreeHash", value.getSourceTreeHash());
        view.put("hashAlgorithm", value.getHashAlgorithm());
        view.put("scopeVersion", value.getScopeVersion());
        view.put("bindingStatus", value.getBindingStatus());
        view.put("boundTag", value.getBoundTag());
        view.put("boundCommitSha", value.getBoundCommitSha());
        view.put("matchedTags", parseList(value.getMatchedTagsJson()));
        view.put("bindingMethod", value.getBindingMethod());
        view.put("bindingNote", value.getBindingNote());
        view.put("boundAt", value.getBoundAt());
        view.put("lastCheckedAt", value.getLastCheckedAt());
        JSONObject snapshot = parseObject(value.getSnapshotJson());
        view.put("capturedAt", snapshot == null ? null : snapshot.getString("capturedAt"));
        view.put("captureTrigger", snapshot == null ? null : snapshot.getString("captureTrigger"));
        view.put("ignoreRulesHash", snapshot == null ? null : snapshot.getString("ignoreRulesHash"));
        view.put("warning", snapshot == null ? null : snapshot.getString("warning"));
        return view;
    }

    private String filteredTreeHash(String ref, List<String> ignorePatterns, boolean includeWorkingTree) throws Exception {
        Path temp = Files.createTempDirectory("evalops-git-tree-");
        try {
            Path objects = Files.createDirectories(temp.resolve("objects"));
            Path common = gitCommonDirectory();
            Map<String, String> environment = new HashMap<>();
            environment.put("GIT_INDEX_FILE", temp.resolve("index").toString());
            environment.put("GIT_OBJECT_DIRECTORY", objects.toString());
            environment.put("GIT_ALTERNATE_OBJECT_DIRECTORIES", common.resolve("objects").toString());

            git(repositoryRoot, environment, null, "read-tree", ref);
            if (includeWorkingTree) {
                Path excludes = temp.resolve("evalops-excludes");
                Files.writeString(excludes, String.join(System.lineSeparator(), ignorePatterns), StandardCharsets.UTF_8);
                Map<String, String> addEnvironment = new HashMap<>(environment);
                addEnvironment.put("GIT_CONFIG_COUNT", "1");
                addEnvironment.put("GIT_CONFIG_KEY_0", "core.excludesFile");
                addEnvironment.put("GIT_CONFIG_VALUE_0", excludes.toString());
                git(repositoryRoot, addEnvironment, null, "add", "-A", "--", ".");
            }
            removeIgnoredIndexEntries(environment, ignorePatterns);
            return gitText(repositoryRoot, environment, null, "write-tree").trim();
        } finally {
            deleteRecursively(temp);
        }
    }

    private void removeIgnoredIndexEntries(Map<String, String> environment, List<String> patterns) throws Exception {
        byte[] files = git(repositoryRoot, environment, null, "ls-files", "-z").output();
        IgnoreMatcher matcher = new IgnoreMatcher(patterns);
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        for (String path : nullSeparated(files)) {
            if (matcher.ignored(path)) {
                ignored.write(path.getBytes(StandardCharsets.UTF_8));
                ignored.write(0);
            }
        }
        if (ignored.size() > 0) {
            git(repositoryRoot, environment, ignored.toByteArray(),
                    "update-index", "--force-remove", "-z", "--stdin");
        }
    }

    private List<TagRef> tagRefs(int limit) throws Exception {
        String output = gitText(repositoryRoot, Map.of(), null, "for-each-ref",
                "--sort=-creatordate", "--format=%(refname:short)", "refs/tags");
        List<TagRef> tags = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String name = line.trim();
            if (name.isBlank()) continue;
            try {
                tags.add(resolveTag(name));
                if (tags.size() >= limit) break;
            } catch (Exception error) {
                log.debug("Skip unresolved Git tag {}: {}", name, error.getMessage());
            }
        }
        return tags;
    }

    private TagRef resolveTag(String tag) throws Exception {
        if (tag.length() > 255 || tag.contains("\u0000")) throw new IllegalArgumentException("Tag 格式不合法");
        git(repositoryRoot, Map.of(), null, "check-ref-format", "refs/tags/" + tag);
        String commit = gitText(repositoryRoot, Map.of(), null,
                "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}").trim();
        return new TagRef(tag, commit);
    }

    private List<String> tagNamesPointingAt(String commit) throws Exception {
        String output = gitText(repositoryRoot, Map.of(), null, "tag", "--points-at", commit);
        return output.lines().map(String::trim).filter(value -> !value.isBlank()).sorted().toList();
    }

    private Path findRepositoryRoot() throws Exception {
        Path start = configuredRepositoryPath == null || configuredRepositoryPath.isBlank()
                ? Paths.get(System.getProperty("user.dir")) : Paths.get(configuredRepositoryPath.trim());
        start = start.toAbsolutePath().normalize();
        String root = gitText(start, Map.of(), null, "rev-parse", "--show-toplevel").trim();
        if (root.isBlank()) throw new IllegalStateException("应用运行目录不在 Git 仓库中");
        return Paths.get(root).toAbsolutePath().normalize();
    }

    private Path gitCommonDirectory() throws Exception {
        String value = gitText(repositoryRoot, Map.of(), null, "rev-parse", "--git-common-dir").trim();
        Path path = Paths.get(value);
        return (path.isAbsolute() ? path : repositoryRoot.resolve(path)).normalize();
    }

    private List<String> loadIgnorePatterns(Path root) throws IOException {
        Path ignoreFile = root.resolve(".evalopsignore");
        if (!Files.isRegularFile(ignoreFile)) return DEFAULT_IGNORE_PATTERNS;
        List<String> patterns = Files.readAllLines(ignoreFile, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        return patterns.isEmpty() ? DEFAULT_IGNORE_PATTERNS : patterns;
    }

    private List<String> parsePatterns(String json) {
        List<String> patterns = parseList(json);
        return patterns.isEmpty() ? DEFAULT_IGNORE_PATTERNS : patterns;
    }

    private GitResult git(Path directory, Map<String, String> environment, byte[] stdin,
                          String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-c");
        command.add("core.quotepath=false");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return readLimited(process.getInputStream());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
        if (stdin != null) {
            try (OutputStream output = process.getOutputStream()) {
                output.write(stdin);
            }
        } else {
            process.getOutputStream().close();
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Git 命令执行超时");
        }
        byte[] output = outputFuture.get(3, TimeUnit.SECONDS);
        if (process.exitValue() != 0) {
            String message = new String(output, StandardCharsets.UTF_8).trim();
            throw new IllegalStateException(message.isBlank() ? "Git 命令执行失败" : message);
        }
        return new GitResult(output);
    }

    private String gitText(Path directory, Map<String, String> environment, byte[] stdin,
                           String... arguments) throws Exception {
        return new String(git(directory, environment, stdin, arguments).output(), StandardCharsets.UTF_8);
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_GIT_OUTPUT_BYTES) {
                throw new IllegalStateException("Git 命令输出超过 16MB 上限");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    log.debug("Cannot delete EvalOps Git temp path {}: {}", path, error.getMessage());
                }
            });
        } catch (IOException error) {
            log.debug("Cannot clean EvalOps Git temp directory {}: {}", root, error.getMessage());
        }
    }

    private List<String> nullSeparated(byte[] value) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= value.length; index++) {
            if (index == value.length || value[index] == 0) {
                if (index > start) result.add(new String(value, start, index - start, StandardCharsets.UTF_8));
                start = index + 1;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parseObject(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String abbreviate(String value) {
        return value == null ? "—" : value.substring(0, Math.min(12, value.length()));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean ignoredForTest(String path, List<String> patterns) {
        return new IgnoreMatcher(patterns).ignored(path);
    }

    private record GitResult(byte[] output) { }

    private record TagRef(String name, String commitSha) { }

    private static final class IgnoreMatcher {
        private final List<IgnoreRule> rules;

        private IgnoreMatcher(List<String> patterns) {
            this.rules = patterns.stream().map(String::trim)
                    .filter(value -> !value.isBlank() && !value.startsWith("#"))
                    .map(IgnoreRule::new).toList();
        }

        private boolean ignored(String path) {
            String normalized = path.replace('\\', '/');
            boolean ignored = false;
            for (IgnoreRule rule : rules) {
                if (rule.pattern().matcher(normalized).matches()) ignored = !rule.negated();
            }
            return ignored;
        }
    }

    private record IgnoreRule(boolean negated, Pattern pattern) {
        private IgnoreRule(String raw) {
            this(raw.startsWith("!"), compile(raw.startsWith("!") ? raw.substring(1) : raw));
        }

        private static Pattern compile(String source) {
            String value = source.replace('\\', '/');
            boolean anchored = value.startsWith("/");
            if (anchored) value = value.substring(1);
            boolean directory = value.endsWith("/");
            if (directory) value = value.substring(0, value.length() - 1);
            boolean hasSlash = value.contains("/");
            StringBuilder regex = new StringBuilder(anchored || hasSlash ? "^" : "^(?:.*/)?");
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '*') {
                    boolean doubleStar = index + 1 < value.length() && value.charAt(index + 1) == '*';
                    if (doubleStar) {
                        index++;
                        if (index + 1 < value.length() && value.charAt(index + 1) == '/') {
                            index++;
                            regex.append("(?:.*/)?");
                        } else {
                            regex.append(".*");
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (current == '?') {
                    regex.append("[^/]");
                } else {
                    if (".[]{}()+-^$|".indexOf(current) >= 0) regex.append('\\');
                    regex.append(current);
                }
            }
            regex.append(directory || source.endsWith("/**") ? "(?:/.*)?$" : "$");
            return Pattern.compile(regex.toString());
        }
    }

    @Data
    public static class BindTagCommand {
        private String tag;
        private Boolean force;
        private String note;
    }
}
