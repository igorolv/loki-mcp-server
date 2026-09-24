package ru.it_spectrum.ai.loki.mcp.connection;

import ru.it_spectrum.ai.loki.mcp.service.Errors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The directories exportLogs may write into (exportRoots of the connections file); the first one is the default.
 * A directory the model names must lie inside one of them after normalization and after symbolic links are resolved,
 * so that text in the logs cannot steer a write anywhere else on the disk.
 */
public record ExportRoots(List<Path> roots) {
    public static final int MAX_ROOTS = 16;

    public ExportRoots {
        if (roots == null || roots.isEmpty() || roots.size() > MAX_ROOTS
                || roots.stream().anyMatch(r -> r == null || !r.isAbsolute())) throw Errors.configuration();
        roots = roots.stream().map(Path::normalize).toList();
    }

    /**
     * The directory to write into, created when missing: blank is the first root, a relative path is resolved against
     * it, an absolute path must lie inside one of the roots.
     */
    public Path resolve(String directory) {
        Path target;
        try {
            target = directory == null || directory.isBlank() ? roots.getFirst() : Path.of(directory.strip());
        } catch (InvalidPathException e) {
            throw outside();
        }
        if (!target.isAbsolute()) target = roots.getFirst().resolve(target);
        target = target.toAbsolutePath().normalize();
        Path candidate = target;
        var root = roots.stream().filter(candidate::startsWith).findFirst().orElseThrow(this::outside);
        try {
            Files.createDirectories(target);
            if (!target.toRealPath().startsWith(root.toRealPath())) throw outside();
        } catch (IOException e) {
            throw Errors.invalid("Cannot create the directory " + target + ". Pass another directory inside: " + list() + ".");
        }
        return target;
    }

    private RuntimeException outside() {
        return Errors.invalid("directory must be inside one of the export directories: " + list()
                + ". Leave it out to write into the first one, or pass a subdirectory name like \"incident-42\".");
    }

    private String list() {
        return roots.stream().map(Path::toString).collect(Collectors.joining(", "));
    }
}
